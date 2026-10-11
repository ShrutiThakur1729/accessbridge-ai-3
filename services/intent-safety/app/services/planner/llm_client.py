# Encapsulates system prompts and OpenAI/Groq client interactions with non-sensitive diagnostic logging.
import os
import json
import time
import logging
import uuid
from typing import List, Dict, Any, Optional
from openai import AsyncOpenAI
from app.config import settings
from app.schemas.action_plan import ActionType, RiskLevel

logger = logging.getLogger("intent-safety.llm")

DEBUG_LOG_LLM_CONTEXT = os.getenv("DEBUG_LOG_LLM_CONTEXT", "false").strip().lower() in {"1", "true", "yes", "on"}

SYSTEM_PROMPT = """You are an autonomous, universal Android UI Automation Agent operating turn-by-turn.

STRICT FORMAT REQUIREMENTS:
- Respond ONLY with a valid JSON object matching the requested schema.
- Do NOT include markdown code blocks (```json), conversational text, or commentary.
- Return raw JSON starting with '{' and ending with '}'.

UNIVERSAL EXECUTION RULES (STRICT):

1. FOREGROUND PACKAGE CHECK:
   - Examine `current_package_name`.
   - If `current_package_name` belongs to the target application (and the request is NOT an initial direct in-app search intent like opening YouTube to search/play X), NEVER output `OPEN_APP`.

2. DYNAMIC INPUT PARAMETER ISOLATION (UNIVERSAL):
   - When issuing `TYPE_TEXT`, `input_data` MUST contain ONLY the target search query parameter, NEVER the full voice prompt (e.g. "Taarak Mehta Ka Ooltah Chashmah episode 2", NOT "Open YouTube and play...").

3. VALID TARGET CHECK:
   - When issuing `CLICK`, `target_node` or `target_text` MUST contain a valid non-empty label matching an interactive element on screen.

4. YOUTUBE & IN-APP SEARCH RULES (e.g., "open YouTube and search/play X" in any language):
   - INITIAL TURN DIRECT INTENT: For "open YouTube and search/play X" in any language, emit ONE OPEN_APP step with package_name "com.google.android.youtube" and input_data = X only (strip the verbs like open, search, play, watch and the app name YouTube; keep X in its original script).
   - MANUAL FALLBACK SEQUENCE: The manual sequence CLICK search -> TYPE_TEXT -> SUBMIT_SEARCH becomes the fallback.
   - LATER TURNS (Turn t+1 onwards): Inspect the visible screen:
     - If search results for X are visible: CLICK the best-matching result (SCROLL if none visible).
     - If the YouTube home screen is visible instead: execute the manual sequence (CLICK search -> TYPE_TEXT X -> SUBMIT_SEARCH).
   - PLAYBACK CONFIRMATION: is_completed: true WITH action: "FINISH" MAY BE SET ONLY AFTER a result item was clicked AND the visible screen state confirms active video playback controls (e.g. player view, pause button, seekbar, video title, or playback overlay).

5. COMPLETION RULE:
   - Set `is_completed: true` with `action: "FINISH"` ONLY when the user's ultimate goal is visibly satisfied.

6. PARAMETER FULFILLMENT CHECK: Before outputting a commit action (e.g., Save, Submit, Done), verify whether requested parameter values (e.g., time digits, AM/PM, toggles) visible on screen have been applied. If interactive parameters are visible and unadjusted, execute parameter adjustment steps before tapping commit.

7. UNIVERSAL SCREEN QA RULE:
   - If the user asks an analytical, comparative, or informational question about what is currently visible on screen (e.g., "What is the price?", "Which item is cheapest?", "Summarize the options"):
   - Inspect the visible screen state and grouped objects.
   - Formulate a comprehensive, descriptive `speech_response` in the target response language.
   - Emit `action: "PAUSE_FOR_USER_INPUT"` with `is_completed: false` and `execution_state: "PAUSE_FOR_USER_INPUT"`.
   - Do NOT emit CLICK, TYPE_TEXT, or touch actions when responding to pure Screen QA questions.

8. UNIVERSAL SELF-HEALING & OVERLAY CLEARING RULE:
   - If a requested target element is off-screen or obscured, emit `action: "SCROLL"`.
   - If a generic modal dialog, system permission prompt, or pop-up overlay is blocking the UI, emit `action: "SYSTEM_BACK"` to dismiss the overlay and clear the screen state.

9. RESPONSE LANGUAGE & LOCALIZATION RULE:
   - Examine `target_response_language` ("en", "hi", or "mr").
   - Write `speech_response` in the specified target response language:
     - For "hi": Write speech_response in Hindi using Devanagari script.
     - For "mr": Write speech_response in Marathi using Devanagari script.
     - For "en": Write speech_response in English.
   - STRICT ENTITY PRESERVATION: Keep all app names (e.g. YouTube, WhatsApp), contact names (e.g. Rahul, Shruti), and typed search parameters EXACTLY as spoken or specified in English/native characters. Do NOT transliterate proper nouns awkwardly.
   - Include `"response_language": "en" | "hi" | "mr"` in your returned JSON payload.

CRITICAL GOAL SATISFACTION EVALUATION RULE:
On every turn t, before generating any UI action, perform the following cognitive goal assessment:
1. PURE LAUNCH CHECK: Was the user's primary intent purely a package launch request (e.g., "Open WhatsApp", "Launch Camera", "Start Blinkit") WITHOUT subsequent in-app instructions?
2. FOREGROUND VERIFICATION: Does current_package_name or the active screen state match the requested target application?
3. TERMINATION RULE: If PURE LAUNCH CHECK is true AND FOREGROUND VERIFICATION is true:
   - You MUST immediately terminate the task by emitting:
     {
       "is_completed": true,
       "execution_state": "COMPLETED",
       "steps": [{"step_id": 1, "action": "FINISH", "reason": "Target application is active in foreground; pure launch request satisfied."}],
       "speech_response": "[AppName] is open."
     }
   - You are STRICTLY FORBIDDEN from generating CLICK, TYPE_TEXT, or search actions targeting in-app elements (e.g., typing the app's name into its own search bar) once the target app is foregrounded.
4. MULTI-STEP CONTINUATION: If the prompt contains subsequent actions (e.g., "Open WhatsApp and text Mom"):
   - Bypass re-issuing OPEN_APP.
   - Transition directly to evaluating the in-app DOM elements to execute the next logical step.

CRITICAL OUTPUT FORMAT:
{
  "plan_id": "string",
  "thought_process": "string or null",
  "user_goal": "string",
  "risk_level": "GREEN | YELLOW | RED",
  "response_language": "en | hi | mr",
  "total_steps": 1,
  "is_completed": false,
  "speech_response": "string",
  "updated_session_state": {},
  "steps": [
    {
      "step_id": 1,
      "action": "OPEN_APP | CLICK | TYPE_TEXT | SUBMIT_SEARCH | SCROLL | SWIPE_UP | GO_HOME | CONFIRM_BIOMETRIC | SYSTEM_BACK | PAUSE_FOR_USER_INPUT | USER_TAKE_OVER | FINISH",
      "target_node": "string or null",
      "target_text": "string or null",
      "input_data": "string or null",
      "tier": 3,
      "requires_biometric": false,
      "package_name": "string or null",
      "fallback_package_name": "string or null",
      "reason": "string or null"
    }
  ]
}
"""

class LLMPlannerClient:
    def __init__(self):
        self.use_llm_mock = os.getenv(
            "USE_LLM_MOCK", str(getattr(settings, "USE_LLM_MOCK", False))
        ).strip().lower() in {"1", "true", "yes", "on"}

        groq_key = getattr(settings, "GROQ_API_KEY", "") or os.getenv("GROQ_API_KEY", "")
        if not self.use_llm_mock and groq_key and groq_key.startswith("gsk_"):
            self.client = AsyncOpenAI(
                api_key=groq_key,
                base_url="https://api.groq.com/openai/v1"
            )
            self.model = "llama-3.3-70b-versatile"
            logger.info("LLM_CLIENT_READY=true provider=Groq model=llama-3.3-70b-versatile")
        elif not self.use_llm_mock:
            self.client = AsyncOpenAI(
                api_key=os.getenv("OPENAI_API_KEY", "ollama"),
                base_url=os.getenv("OPENAI_API_BASE", "http://localhost:11434/v1")
            )
            self.model = getattr(settings, "DEFAULT_LLM_MODEL", "llama3.1:8b")
            logger.info("LLM_CLIENT_READY=true provider=OpenAI/Ollama model=%s", self.model)
        else:
            self.client = None
            self.model = "mock"
            logger.info("LLM_CLIENT_READY=false provider=mock model=mock")

    def build_user_context_prompt(
        self,
        task_id: Optional[str],
        user_prompt: str,
        current_app_package: Optional[str],
        action_status: str,
        last_attempted_action: Optional[str],
        screen_context: Dict[str, Any],
        total_screen_elements: int,
        action_history: List[Dict[str, Any]],
        session_context: Optional[Any] = None,
        target_response_language: str = "en",
        raw_nodes: Optional[List[Dict[str, Any]]] = None
    ) -> str:
        dialogue_history = getattr(session_context, "conversation_history", []) if session_context else []
        primary_goal = getattr(session_context, "primary_goal", None) if session_context else None
        active_pkg = (getattr(session_context, "active_package", None) if session_context else None) or current_app_package

        grouped_objects = screen_context.get("grouped_objects", [])

        raw_interactive_nodes = []
        for node in (raw_nodes or [])[:40]:
            if isinstance(node, dict):
                label = str(node.get("text") or node.get("content_description") or node.get("contentDescription") or "").strip()
                if label or node.get("is_clickable") or node.get("is_editable") or node.get("view_id") or node.get("resource_id"):
                    raw_interactive_nodes.append({
                        "label": label,
                        "is_clickable": bool(node.get("is_clickable") or node.get("clickable")),
                        "is_editable": bool(node.get("is_editable") or node.get("editable")),
                        "resource_id": str(node.get("resource_id") or node.get("view_id") or node.get("viewId") or "")
                    })

        return f"""
TASK CONTEXT:
- Task ID: "{task_id or 'N/A'}"
- User Intent: "{user_prompt}"
- Primary Goal: "{primary_goal or user_prompt}"
- Dialogue History: {json.dumps(dialogue_history)}
- Target Response Language: "{target_response_language}" (Write speech_response in this language!)

CURRENT EXECUTION STATE:
- Active App Package: "{active_pkg or 'Unknown / Launcher'}"
- Action Status: "{action_status}"
- Last Executed Action: "{last_attempted_action or 'None'}"
- Action History: {json.dumps(action_history[-8:])}

VISIBLE SCREEN STATE ({total_screen_elements} raw nodes detected):
- Active Input Focused: {screen_context.get('active_input_focused', False)}
- Focused Hint: "{screen_context.get('focused_hint') or 'None'}"
- Grouped Visual Objects / Cards: {json.dumps(grouped_objects, indent=2)}
- Raw Interactive On-Screen Controls (Up to 40): {json.dumps(raw_interactive_nodes, indent=2)}
- UI Topology (functional zones): {json.dumps(screen_context.get('ui_topology', {}), indent=2)}

Analyze the screen state in relation to the user intent, multi-turn dialogue history, and action history. Return the next logical single action JSON object.
"""

    async def generate_plan_step(self, user_prompt: str, context_prompt: str) -> Optional[Dict[str, Any]]:
        if not self.client:
            logger.info("LLM Client in mock mode; skipping remote generation.")
            return None

        if DEBUG_LOG_LLM_CONTEXT:
            logger.info("DEBUG_LLM_CONTEXT_PROMPT:\n%s", context_prompt[:4000])

        start_time = time.time()
        provider = "Groq" if "groq.com" in str(self.client.base_url) else "OpenAI/Ollama"
        logger.info("Dispatching LLM request provider=%s model=%s", provider, self.model)

        try:
            response = await self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": context_prompt}
                ],
                temperature=0.1
            )
            duration = round(time.time() - start_time, 3)

            raw_content = response.choices[0].message.content
            if not raw_content or not raw_content.strip():
                logger.error("LLM_ERROR provider=%s model=%s status=EMPTY_RESPONSE duration_sec=%f", provider, self.model, duration)
                return None

            clean_json = raw_content.strip()
            if clean_json.startswith("```json"):
                clean_json = clean_json.replace("```json", "").replace("```", "").strip()

            try:
                parsed_json = json.loads(clean_json)
                steps = parsed_json.get("steps", [])
                action_names = [s.get("action") for s in steps if isinstance(s, dict)]
                logger.info("LLM_SUCCESS provider=%s model=%s duration_sec=%f LLM_STEPS_RETURNED=%d actions=%s",
                            provider, self.model, duration, len(steps), action_names)

                valid_steps = []
                for s in steps:
                    if not isinstance(s, dict):
                        continue
                    act_raw = str(s.get("action") or "").upper().strip()
                    if act_raw in ActionType.__members__:
                        valid_steps.append(s)
                    else:
                        logger.error("LLM_REJECTED_UNKNOWN_ACTION action_name=%s prompt=%s", act_raw, user_prompt[:50])

                parsed_json["steps"] = valid_steps
                return parsed_json

            except json.JSONDecodeError as json_err:
                logger.error("LLM_ERROR provider=%s model=%s error_type=UNPARSEABLE_JSON duration_sec=%f err=%s raw=%s",
                             provider, self.model, duration, str(json_err), clean_json[:100])
                return None

        except Exception as e:
            duration = round(time.time() - start_time, 3)
            logger.error("LLM_ERROR provider=%s model=%s error_type=%s duration_sec=%f err=%s",
                         provider, self.model, type(e).__name__, duration, str(e))

        return None
