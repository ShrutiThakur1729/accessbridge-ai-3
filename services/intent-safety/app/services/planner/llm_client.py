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

CRITICAL OUTPUT FORMAT:
{
  "plan_id": "string",
  "user_goal": "string",
  "risk_level": "GREEN | YELLOW | RED",
  "total_steps": 1,
  "is_completed": false,
  "speech_response": "string",
  "steps": [
    {
      "step_id": 1,
      "action": "OPEN_APP | CLICK | TYPE_TEXT | SCROLL | SWIPE_UP | CONFIRM_BIOMETRIC | FINISH",
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
    ) -> str:
        return f"""
TASK CONTEXT:
- Task ID: "{task_id or 'N/A'}"
- User Intent: "{user_prompt}"

CURRENT EXECUTION STATE:
- Active App Package: "{current_app_package or 'Unknown / Launcher'}"
- Action Status: "{action_status}"
- Last Executed Action: "{last_attempted_action or 'None'}"
- Action History: {json.dumps(action_history[-8:])}

VISIBLE SCREEN STATE ({total_screen_elements} raw nodes detected):
- Active Input Focused: {screen_context.get('active_input_focused', False)}
- Focused Hint: "{screen_context.get('focused_hint') or 'None'}"
- UI Topology (functional zones): {json.dumps(screen_context.get('ui_topology', {}), indent=2)}

Analyze the screen state in relation to the user intent and action history. Return the next logical single action JSON object.
"""
