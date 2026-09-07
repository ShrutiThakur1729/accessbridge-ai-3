#Encapsulates system prompts and OpenAI client interactions.
import os
import json
import uuid
from typing import List, Dict, Any, Optional
from openai import AsyncOpenAI
from app.config import settings
from app.schemas.action_plan import RiskLevel

SYSTEM_PROMPT = """You are an autonomous, universal Android UI Automation Agent operating turn-by-turn.

STRICT FORMAT REQUIREMENTS:
- Respond ONLY with a valid JSON object matching the requested schema.
- Do NOT include markdown code blocks (```json), conversational text, or commentary.
- Return raw JSON starting with '{' and ending with '}'.

UNIVERSAL EXECUTION RULES (STRICT):

1. FOREGROUND PACKAGE CHECK:
   - Examine `current_package_name`.
   - If `current_package_name` belongs to the target application, NEVER output `OPEN_APP`.

2. DYNAMIC INPUT PARAMETER ISOLATION (UNIVERSAL):
   - When issuing `TYPE_TEXT`, `input_data` MUST contain ONLY the target parameter, NEVER the full voice prompt.

3. VALID TARGET CHECK:
   - When issuing `CLICK`, `target_node` or `target_text` MUST contain a valid non-empty label matching an interactive element on screen.

4. COMPLETION RULE:
   - Set `is_completed: true` with `action: "FINISH"` ONLY when the user's ultimate goal is visibly satisfied.

5. PARAMETER FULFILLMENT CHECK: Before outputting a commit action (e.g., Save, Submit, Done), verify whether requested parameter values (e.g., time digits, AM/PM, toggles) visible on screen have been applied. If interactive parameters are visible and unadjusted, execute parameter adjustment steps before tapping commit.

6. UNIVERSAL SCREEN QA RULE:
   - If the user asks an analytical, comparative, or informational question about what is currently visible on screen (e.g., "What is the price?", "Which item is cheapest?", "Summarize the options"):
   - Inspect the visible screen state and grouped objects.
   - Formulate a comprehensive, descriptive `speech_response` answering the query.
   - Emit `action: "PAUSE_FOR_USER_INPUT"` with `is_completed: false` and `execution_state: "PAUSE_FOR_USER_INPUT"`.
   - Do NOT emit CLICK, TYPE_TEXT, or touch actions when responding to pure Screen QA questions.

7. UNIVERSAL SELF-HEALING & OVERLAY CLEARING RULE:
   - If a requested target element is off-screen or obscured, emit `action: "SCROLL"`.
   - If a generic modal dialog, system permission prompt, or pop-up overlay is blocking the UI, emit `action: "SYSTEM_BACK"` to dismiss the overlay and clear the screen state.

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
  "total_steps": 1,
  "is_completed": false,
  "speech_response": "string",
  "updated_session_state": {},
  "steps": [
    {
      "step_id": 1,
      "action": "OPEN_APP | CLICK | TYPE_TEXT | SCROLL | SWIPE_UP | GO_HOME | CONFIRM_BIOMETRIC | SYSTEM_BACK | PAUSE_FOR_USER_INPUT | USER_TAKE_OVER | FINISH",
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
        self.client = AsyncOpenAI(
            api_key=getattr(settings, "OPENAI_API_KEY", None) or "ollama",
            base_url=getattr(settings, "OPENAI_API_BASE", "http://localhost:11434/v1"),
        ) if not self.use_llm_mock else None

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
    ) -> str:
        dialogue_history = getattr(session_context, "conversation_history", []) if session_context else []
        primary_goal = getattr(session_context, "primary_goal", None) if session_context else None
        active_pkg = (getattr(session_context, "active_package", None) if session_context else None) or current_app_package

        grouped_objects = screen_context.get("grouped_objects", [])

        return f"""
TASK CONTEXT:
- Task ID: "{task_id or 'N/A'}"
- User Intent: "{user_prompt}"
- Primary Goal: "{primary_goal or user_prompt}"
- Dialogue History: {json.dumps(dialogue_history)}

CURRENT EXECUTION STATE:
- Active App Package: "{active_pkg or 'Unknown / Launcher'}"
- Action Status: "{action_status}"
- Last Executed Action: "{last_attempted_action or 'None'}"
- Action History: {json.dumps(action_history[-8:])}

VISIBLE SCREEN STATE ({total_screen_elements} raw nodes detected):
- Active Input Focused: {screen_context.get('active_input_focused', False)}
- Focused Hint: "{screen_context.get('focused_hint') or 'None'}"
- Grouped Visual Objects / Cards: {json.dumps(grouped_objects, indent=2)}
- UI Topology (functional zones): {json.dumps(screen_context.get('ui_topology', {}), indent=2)}

Analyze the screen state in relation to the user intent, multi-turn dialogue history, and action history. Return the next logical single action JSON object.
"""

