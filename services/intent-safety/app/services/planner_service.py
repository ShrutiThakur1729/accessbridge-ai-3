import re
import uuid
import json
import os
from typing import List, Dict, Any, Optional
from openai import AsyncOpenAI
from app.config import settings
from app.schemas.action_plan import ActionPlanResponse, ActionType, PlanStep, RiskLevel, WaterfallTier
from app.services.safety_service import SafetyService
from app.services.vision_client import vision_client
from app.utils.screen_compressor import compress_screen_state


class PlannerService:
    # Common system and popular app hints. Dynamic resolution handles all unlisted apps.
    APP_PACKAGE_HINTS = {
        "whatsapp": ("com.whatsapp", None, "WhatsApp"),
        "uber": ("com.ubercab", None, "Uber"),
        "clock": ("com.google.android.deskclock", "com.sec.android.app.clockpackage", "Clock"),
        "alarm": ("com.google.android.deskclock", "com.sec.android.app.clockpackage", "Clock"),
        "google maps": ("com.google.android.apps.maps", None, "Google Maps"),
        "maps": ("com.google.android.apps.maps", None, "Google Maps"),
        "bookmyshow": ("com.bt.bms", None, "BookMyShow"),
        "youtube": ("com.google.android.youtube", None, "YouTube"),
        "gmail": ("com.google.android.gm", None, "Gmail"),
        "chrome": ("com.android.chrome", None, "Chrome"),
        "telegram": ("org.telegram.messenger", None, "Telegram"),
        "instagram": ("com.instagram.android", None, "Instagram"),
        "facebook": ("com.facebook.katana", None, "Facebook"),
        "spotify": ("com.spotify.music", None, "Spotify"),
        "calendar": ("com.google.android.calendar", None, "Calendar"),
        "camera": ("com.google.android.GoogleCamera", None, "Camera"),
        "contacts": ("com.google.android.contacts", None, "Contacts"),
        "messages": ("com.google.android.apps.messaging", None, "Messages"),
        "phone": ("com.google.android.dialer", None, "Phone"),
        "settings": ("com.android.settings", None, "Settings"),
    }

    TIER_STRING_TO_INT = {
        "TIER_1_INTENT": 1,
        "TIER_2_HEURISTIC": 2,
        "TIER_3_ACCESSIBILITY": 3,
        "TIER_4_VISION": 4,
        "1": 1,
        "2": 2,
        "3": 3,
        "4": 4,
    }

    SYSTEM_PROMPT = """You are an autonomous Android UI Automation Agent operating turn-by-turn.

STRICT FORMAT REQUIREMENTS:
- You MUST respond ONLY with a valid JSON object matching the requested schema.
- Do NOT include markdown code blocks (```json), conversational text, or commentary.
- Return raw JSON starting with '{' and ending with '}'.

UNIVERSAL EXECUTION RULES (STRICT):

1. FOREGROUND PACKAGE CHECK:
   - Examine `current_package_name`.
   - If `current_package_name` matches or belongs to the target application (or the target app is already active in foreground), NEVER output `action_type: OPEN_APP`.
   - Use `OPEN_APP` strictly as a bootstrapping command when the target app is not in the foreground.

2. ACTIVE INPUT FIELD RULE:
   - Examine `active_input_focused`.
   - If `active_input_focused` is `true` AND the user intent requires entering text, searching, or messaging, your next action MUST be `TYPE_TEXT` targeting the focused field.
   - Do NOT issue navigation commands (`CLICK`, `OPEN_APP`, `BACK`, `SWIPE`) when an input field is active and focused.

3. TARGET SELECTION:
   - Select targets strictly from `interactive_targets`.
   - Do NOT select targets from `secondary_metadata` (e.g., do not click message preview snippets).

4. COMPLETION RULE:
   - Return `is_completed: true` with `action_type: "FINISH"` ONLY when the user's ultimate goal is visibly satisfied on screen.

CRITICAL OUTPUT FORMAT:
You MUST return a JSON object strictly matching the following schema:
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

    def __init__(self):
        # Read the environment at construction time so local integration tests can
        # enable the no-cost path without reloading the settings singleton.
        self.use_llm_mock = os.getenv(
            "USE_LLM_MOCK", str(getattr(settings, "USE_LLM_MOCK", False))
        ).strip().lower() in {"1", "true", "yes", "on"}
        self.client = AsyncOpenAI(
            api_key=getattr(settings, "OPENAI_API_KEY", None) or "ollama", # Fallback dummy key
            base_url=getattr(settings, "OPENAI_API_BASE", "http://localhost:11434/v1"),
        ) if not self.use_llm_mock else None
    @staticmethod
    def _get_llm_mock_plan(user_prompt: str, risk_level: RiskLevel) -> ActionPlanResponse:
        """Return a deterministic, schema-valid plan without contacting Groq."""
        return ActionPlanResponse(
            plan_id="plan_llm_mock",
            user_goal=user_prompt,
            risk_level=risk_level,
            total_steps=1,
            current_step_index=0,
            is_completed=False,
            speech_response="LLM mock enabled. Returning a local test directive.",
            steps=[
                PlanStep(
                    step_id=1,
                    action=ActionType.SCROLL,
                    target_node=None,
                    target_text="mock_target",
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    requires_biometric=False,
                    reason="Static local ActionPlanResponse for WebSocket integration testing.",
                )
            ],
        )

    def _parse_tier_value(self, raw_tier: Any) -> int:
        if isinstance(raw_tier, int):
            return raw_tier if raw_tier in (1, 2, 3, 4) else 3
        tier_str = str(raw_tier).strip().upper()
        return self.TIER_STRING_TO_INT.get(tier_str, 3)

    def _normalize_and_wrap_plan_dict(
        self,
        plan_dict: Any,
        user_prompt: str,
        risk_level: RiskLevel
    ) -> Dict[str, Any]:
        steps_list = []
        is_completed = False

        if isinstance(plan_dict, list):
            steps_list = plan_dict
        elif isinstance(plan_dict, dict):
            is_completed = bool(plan_dict.get("is_completed", False))
            if "steps" in plan_dict and isinstance(plan_dict["steps"], list):
                steps_list = plan_dict["steps"]
            else:
                steps_list = [plan_dict]

        normalized_steps = []
        for idx, step in enumerate(steps_list, start=1):
            if not isinstance(step, dict):
                continue

            action_raw = str(step.get("action", step.get("action_type", "CLICK"))).upper()
            tier_val = self._parse_tier_value(step.get("tier"))

            normalized_step = {
                "step_id": step.get("step_id", idx),
                "action": action_raw,
                "target_node": step.get("target_node") or step.get("view_id") or None,
                "target_text": step.get("target_text") or step.get("text") or None,
                "input_data": step.get("input_data") or step.get("value") or step.get("text_to_type") or None,
                "tier": tier_val,
                "requires_biometric": bool(step.get("requires_biometric", False)),
                "package_name": step.get("package_name"),
                "fallback_package_name": step.get("fallback_package_name"),
                "reason": step.get("reason", "Automated step execution")
            }
            normalized_steps.append(normalized_step)

        if not normalized_steps:
            normalized_steps = [{
                "step_id": 1,
                "action": "SCROLL",
                "target_node": None,
                "target_text": "rescan_wait",
                "tier": 3,
                "requires_biometric": False,
                "reason": "Default step fallback"
            }]

        return {
            "plan_id": f"plan_{uuid.uuid4().hex[:8]}",
            "user_goal": user_prompt,
            "risk_level": risk_level.value if isinstance(risk_level, RiskLevel) else str(risk_level),
            "total_steps": len(normalized_steps),
            "current_step_index": 0,
            "is_completed": is_completed,
            "speech_response": plan_dict.get("speech_response") if isinstance(plan_dict, dict) else "",
            "steps": normalized_steps
        }

    @staticmethod
    def _enforce_completion_invariants(plan: ActionPlanResponse, screen_elements: List[Dict[str, Any]]) -> None:
        """Completion is valid only for an explicit, screen-verified FINISH directive."""
        current_step = (
            plan.steps[plan.current_step_index]
            if 0 <= plan.current_step_index < len(plan.steps)
            else None
        )

        if current_step and current_step.action == ActionType.HARD_STOP:
            plan.is_completed = True
            plan.error = plan.error or "HARD_STOP"
            return

        # GUARD: OPEN_APP is NEVER completed
        if current_step and current_step.action == ActionType.OPEN_APP:
            plan.is_completed = False
            return

        if current_step and current_step.action == ActionType.FINISH and screen_elements:
            plan.is_completed = True
            return

        plan.is_completed = False
        if current_step and current_step.action == ActionType.FINISH:
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=current_step.step_id,
                action=ActionType.SCROLL,
                target_node=None,
                target_text="rescan_wait",
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                requires_biometric=False,
                reason="Cannot verify task completion without visible screen evidence.",
            )

    @staticmethod
    def resolve_local_action(
        user_prompt: str,
        screen_state: List[Dict[str, Any]],
        risk_level: RiskLevel,
        active_input_focused: bool,
        focused_hint: Optional[str],
        session_language: str,
    ) -> Optional[ActionPlanResponse]:
        """Approach A: resolve one unambiguous action against the visible DOM."""
        prompt = user_prompt.strip().lower()
        if not prompt or any(word in prompt for word in (" then ", " after ", " and then ", "next ", "navigate")):
            return None

        type_match = re.match(r"^(?:type|enter|write)\s+(.+)$", prompt, re.IGNORECASE)
        if active_input_focused and type_match:
            target = focused_hint or "focused input"
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                session_language=session_language, execution_state="EXECUTING", approach="LOCAL_RESOLUTION",
                speech_response=f"Entering text into {target}.",
                steps=[PlanStep(step_id=1, action=ActionType.TYPE_TEXT, target_node=target,
                                target_text=target, input_data=type_match.group(1),
                                tier=WaterfallTier.TIER_3_ACCESSIBILITY, requires_biometric=False,
                                reason="Exact focused-input command resolved locally.")],
            )

        if not re.match(r"^(?:tap|click|select|open)\b", prompt, re.IGNORECASE):
            return None
        matches: List[str] = []
        for node in screen_state:
            if not isinstance(node, dict) or not bool(node.get("is_clickable", node.get("isClickable", node.get("clickable", False)))):
                continue
            label = next((str(node.get(field, "")).strip() for field in ("text", "content_description", "contentDescription", "view_id", "viewId") if str(node.get(field, "")).strip()), "")
            if len(label) >= 2 and label.lower() in prompt and label not in matches:
                matches.append(label)
        if len(matches) != 1:
            return None
        target = matches[0]
        return ActionPlanResponse(
            plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
            total_steps=1, current_step_index=0, is_completed=False,
            session_language=session_language, execution_state="EXECUTING", approach="LOCAL_RESOLUTION",
            speech_response=f"Tapping {target}.",
            steps=[PlanStep(step_id=1, action=ActionType.CLICK, target_node=target, target_text=target,
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY, requires_biometric=False,
                            reason="Single exact interactive DOM target resolved locally.")],
        )

    async def generate_plan(
        self,
        user_id: str,
        user_prompt: str,
        current_app_package: Optional[str] = None,
        screen_state: Optional[List[Dict[str, Any]]] = None,
        action_status: str = "INITIAL",
        target_action: Optional[str] = None,
        task_id: Optional[str] = None,
        action_history: Optional[List[Dict[str, Any]]] = None,
        active_input_focused: bool = False,
        focused_hint: Optional[str] = None,
        session_language: str = "en-US",
        screenshot_base64: Optional[str] = None,
        vision_elements: Optional[List[Dict[str, Any]]] = None,
    ) -> ActionPlanResponse:
        risk_level = SafetyService.evaluate_risk(user_prompt)
        red_reason = SafetyService.classify_red_reason(user_prompt)
        if risk_level == RiskLevel.RED or red_reason:
            return SafetyService.build_hard_stop_plan(user_prompt, red_reason or "generic")

        screen_state = list(screen_state or [])
        if vision_elements:
            screen_state.extend(node for node in vision_elements if node not in screen_state)
        clickable_present = any(
            isinstance(node, dict)
            and (node.get("is_clickable") or node.get("isClickable") or node.get("clickable") or node.get("tap_x") is not None)
            for node in screen_state
        )
        if screenshot_base64 and (not screen_state or not clickable_present):
            vision_payload = await vision_client.analyze(
                screenshot_base64,
                task_id=task_id,
                query_text=user_prompt,
            )
            vision_nodes = vision_client.to_screen_state(vision_payload)
            if vision_nodes:
                print(f"LLM Planner: enriched DOM with {len(vision_nodes)} Service 03 coordinates.")
                screen_state.extend(node for node in vision_nodes if node not in screen_state)

        def finalize(plan: ActionPlanResponse) -> ActionPlanResponse:
            self._attach_vision_coordinates(plan, screen_state)
            if screen_state and any(node.get("source") == "vision" for node in screen_state):
                plan.approach = plan.approach or "VISION_FALLBACK"
            return plan

        # This is intentionally before all planner branches. With
        # USE_LLM_MOCK=true every request receives the same local, validated
        # ActionPlanResponse and no Groq client is instantiated or called.
        if self.use_llm_mock:
            return finalize(self._get_llm_mock_plan(user_prompt, risk_level))

        requires_bio = SafetyService.requires_biometric_check(risk_level)
        app_launch = self._resolve_app_launch(user_prompt)
        action_history = action_history or []
        app_opened = self._app_was_opened(action_status, target_action, action_history)

        # GUARD 1: Handling OPEN_APP Failure (Trigger App Drawer Fallback)
        if action_status == "FAILED" and (target_action or "").upper() == ActionType.OPEN_APP.value:
            return finalize(ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                user_goal=user_prompt,
                risk_level=risk_level,
                total_steps=1,
                current_step_index=0,
                is_completed=False,
                speech_response="Direct application launch failed. Opening App Drawer to search manually.",
                steps=[
                    PlanStep(
                        step_id=1,
                        action=ActionType.SWIPE_UP,
                        target_node="home_screen",
                        target_text="Open App Drawer",
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="Direct intent launch failed. Swiping up to access App Drawer."
                    )
                ]
            ))

        # GUARD 2: Handling SUCCESS with 0 elements (App launched/transitioning)
        if action_status == "SUCCESS" and len(screen_state) == 0:
            is_multistep = self._is_multistep_goal(user_prompt, target_action)
            if is_multistep:
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    current_step_index=0,
                    is_completed=False,
                    speech_response="App launched. Waiting for screen contents to load.",
                    steps=[
                        PlanStep(
                            step_id=1,
                            action=ActionType.SCROLL,
                            target_node=None,
                            target_text="rescan_wait",
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            reason="Waiting for screen nodes to populate after transition"
                        )
                    ]
                ))

        # Categorize screen state into interactive_targets vs secondary_metadata
        screen_context = compress_screen_state(screen_state)
        if active_input_focused:
            screen_context["active_input_focused"] = True
        if focused_hint and not screen_context.get("focused_hint"):
            screen_context["focused_hint"] = focused_hint

        local_plan = self.resolve_local_action(
            user_prompt, screen_state, risk_level, active_input_focused, focused_hint, session_language
        )
        if local_plan:
            return finalize(local_plan)

        # Fallback if no LLM client is available
        if not self.client or not getattr(settings, "GROQ_API_KEY", None):
            plan = self._get_fallback_mock_plan(user_prompt, risk_level, requires_bio, app_launch)
            if app_opened or self._is_target_app_active(current_app_package, app_launch):
                self._replace_repeated_open_with_in_app_action(plan, user_prompt, screen_state)
            self._prioritize_focused_input(plan, user_prompt, screen_context)
            self._enforce_completion_invariants(plan, screen_state)
            return finalize(plan)

        user_content = self._build_user_context_prompt(
            task_id=task_id,
            user_prompt=user_prompt,
            current_app_package=current_app_package,
            action_status=action_status,
            last_attempted_action=target_action,
            screen_context=screen_context,
            total_screen_elements=len(screen_state),
            action_history=action_history,
        )

        try:
            model_name = getattr(settings, "DEFAULT_LLM_MODEL", "llama3.1:8b")
            completion = await self.client.chat.completions.create(
                model=model_name,
                messages=[
                    {"role": "system", "content": f"{self.SYSTEM_PROMPT}\n\nLANGUAGE REQUIREMENT: Respond in BCP-47 language `{session_language}`. Keep speech_response in that language."},
                    {"role": "user", "content": f"Respond in JSON format. User intent and screen state payload: {user_content}"}
                ],
                response_format={"type": "json_object"},
                max_tokens=600,
                temperature=0.0,
            )

            raw_content = completion.choices[0].message.content.strip()
            if raw_content.startswith("```"):
                lines = raw_content.splitlines()
                if lines[0].startswith("```"):
                    lines = lines[1:]
                if lines and lines[-1].startswith("```"):
                    lines = lines[:-1]
                raw_content = "\n".join(lines).strip()

            plan_dict = json.loads(raw_content)

            wrapped_payload = self._normalize_and_wrap_plan_dict(plan_dict, user_prompt, risk_level)
            plan = ActionPlanResponse(**wrapped_payload)

            # RULE 1: If on INITIAL turn and target app is not open, bootstrap with OPEN_APP
            if action_status == "INITIAL" and app_launch:
                if not self._is_target_app_active(current_app_package, app_launch):
                    self._enforce_app_launch_first_step(plan, app_launch)

            # RULE 2: If app was already opened or is currently active, prevent repeat OPEN_APP
            if app_opened or self._is_target_app_active(current_app_package, app_launch):
                self._replace_repeated_open_with_in_app_action(plan, user_prompt, screen_state)

            # RULE 3: If input field is focused, enforce TYPE_TEXT
            self._prioritize_focused_input(plan, user_prompt, screen_context)

            # GUARD: OPEN_APP is NEVER completed
            if plan.steps and plan.steps[0].action == ActionType.OPEN_APP:
                plan.is_completed = False

            self._enforce_completion_invariants(plan, screen_state)

            for step in plan.steps:
                if risk_level == RiskLevel.RED and step.step_id == plan.total_steps:
                    step.requires_biometric = True

            if not plan.speech_response and plan.steps:
                first_action = plan.steps[0].action
                target = plan.steps[0].target_text or plan.steps[0].target_node or ""
                if first_action == ActionType.OPEN_APP:
                    plan.speech_response = f"Opening {target or 'application'}."
                elif first_action == ActionType.CLICK:
                    plan.speech_response = f"Tapping {target}."
                elif first_action == ActionType.TYPE_TEXT:
                    plan.speech_response = f"Entering text into {target}."
                elif first_action == ActionType.SWIPE_UP:
                    plan.speech_response = "Swiping up."
                elif first_action == ActionType.FINISH:
                    plan.speech_response = "Goal completed."

            return finalize(plan)

        except Exception as e:
            print(f"LLM Planner warning: {e}. Falling back to default plan.")
            plan = self._get_fallback_mock_plan(user_prompt, risk_level, requires_bio, app_launch)
            if app_opened or self._is_target_app_active(current_app_package, app_launch):
                self._replace_repeated_open_with_in_app_action(plan, user_prompt, screen_state)
            self._prioritize_focused_input(plan, user_prompt, screen_context)
            self._enforce_completion_invariants(plan, screen_state)
            return finalize(plan)

    @staticmethod
    def _app_was_opened(
        action_status: str,
        target_action: Optional[str],
        action_history: List[Dict[str, Any]],
    ) -> bool:
        if action_status.upper() == "SUCCESS" and (target_action or "").upper() == ActionType.OPEN_APP.value:
            return True
        if not action_history:
            return False
        # Check any recent action in history for successful OPEN_APP
        for entry in reversed(action_history[-5:]):
            act = str(entry.get("action_type", entry.get("action", ""))).upper()
            status = str(entry.get("status", "")).upper()
            if act == ActionType.OPEN_APP.value and status == "SUCCESS":
                return True
        return False

    @staticmethod
    def _is_target_app_active(current_app_package: Optional[str], app_launch) -> bool:
        if not current_app_package:
            return False
        curr = current_app_package.lower()
        # If the user is on home screen / launcher, target app is definitely not active
        if any(launcher in curr for launcher in ("launcher", "home", "quickstep", "nexus")):
            return False
        if not app_launch:
            return True
        package_name, _, app_label = app_launch
        if package_name and (package_name.lower() in curr or curr in package_name.lower()):
            return True
        if app_label and app_label.lower() in curr:
            return True
        return False

    def _replace_repeated_open_with_in_app_action(
        self,
        plan: ActionPlanResponse,
        user_prompt: str,
        screen_state: List[Dict[str, Any]],
    ) -> None:
        """Never let a successful app launch or active foreground app be followed by another OPEN_APP."""
        if not plan.steps or plan.steps[plan.current_step_index].action != ActionType.OPEN_APP:
            return

        def node_text(node: Dict[str, Any]) -> str:
            return str(
                node.get("text")
                or node.get("content_description")
                or node.get("contentDescription")
                or node.get("view_id")
                or node.get("viewId")
                or ""
            ).strip()

        searchable_nodes = [
            node for node in screen_state
            if "search" in node_text(node).lower() and (node.get("is_clickable") or node.get("clickable") or node.get("isClickable"))
        ]
        focused_editable = [
            node for node in screen_state
            if (node.get("is_editable") or node.get("isEditable") or node.get("editable"))
            and (node.get("is_focused") or node.get("isFocused") or node.get("focused"))
        ]
        clickable_nodes = [
            node for node in screen_state
            if (node.get("is_clickable") or node.get("isClickable") or node.get("clickable"))
            and not ("keyboard" in node_text(node).lower())
        ]
        editable_nodes = [
            node for node in screen_state
            if node.get("is_editable") or node.get("isEditable") or node.get("editable")
        ]
        old_step = plan.steps[plan.current_step_index]

        if focused_editable:
            target = node_text(focused_editable[0])
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.TYPE_TEXT,
                target_node=target,
                target_text=target,
                input_data=self._extract_search_text(user_prompt) or user_prompt,
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is active; typing text into the focused field.",
            )
        elif searchable_nodes:
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.CLICK,
                target_node=node_text(searchable_nodes[0]),
                target_text=node_text(searchable_nodes[0]),
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is active; tapping the visible search control.",
            )
        elif clickable_nodes:
            target = node_text(clickable_nodes[0])
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.CLICK,
                target_node=target,
                target_text=target,
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is active; continuing with an interactive element.",
            )
        elif editable_nodes:
            target = node_text(editable_nodes[0])
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.TYPE_TEXT,
                target_node=target,
                target_text=target,
                input_data=self._extract_search_text(user_prompt) or user_prompt,
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is active; entering text into the input field.",
            )
        else:
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.SCROLL,
                target_text="rescan_wait",
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is active; waiting for actionable elements.",
            )
        plan.is_completed = False

    def _prioritize_focused_input(
        self,
        plan: ActionPlanResponse,
        user_prompt: str,
        screen_context: Dict[str, Any],
    ) -> None:
        """Apply the universal focused-input rule."""
        text_intent = any(word in user_prompt.lower() for word in ("search", "find", "send", "message", "type", "play", "watch"))
        if (
            not text_intent
            or not screen_context.get("active_input_focused")
            or not plan.steps
            or plan.steps[plan.current_step_index].action == ActionType.TYPE_TEXT
        ):
            return

        current_step = plan.steps[plan.current_step_index]
        target = screen_context.get("focused_hint") or "focused input field"
        plan.steps[plan.current_step_index] = PlanStep(
            step_id=current_step.step_id,
            action=ActionType.TYPE_TEXT,
            target_node=target,
            target_text=target,
            input_data=self._extract_search_text(user_prompt) or user_prompt,
            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
            reason="Focused input is active; typing requested query/payload.",
        )
        plan.is_completed = False

    def _build_user_context_prompt(
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
- Interactive Targets ({len(screen_context.get('interactive_targets', []))} controls):
{json.dumps(screen_context.get('interactive_targets', []), indent=2)}
- Secondary Metadata ({len(screen_context.get('secondary_metadata', []))} items):
{json.dumps(screen_context.get('secondary_metadata', [])[:15], indent=2)}

Analyze the screen state in relation to the user intent. Return the complete ActionPlanResponse JSON object.
"""

    def _is_multistep_goal(self, user_prompt: str, target_action: Optional[str]) -> bool:
        normalized = user_prompt.lower()
        keywords = ["search", "find", "send", "book", "play", "navigate", "type", "message", "saying", "open and"]
        if any(kw in normalized for kw in keywords):
            return True
        if target_action in [ActionType.OPEN_APP.value, ActionType.SWIPE_UP.value]:
            return True
        return False

    def _get_fallback_mock_plan(self, user_prompt: str, risk_level: RiskLevel, requires_bio: bool, app_launch=None) -> ActionPlanResponse:
        steps = []
        if app_launch:
            package_name, fallback_package_name, app_label = app_launch
            steps.append(PlanStep(
                step_id=1,
                action=ActionType.OPEN_APP,
                target_node=package_name or app_label,
                package_name=package_name,
                fallback_package_name=fallback_package_name,
                target_text=app_label,
                input_data=None,
                tier=WaterfallTier.TIER_1_INTENT,
                requires_biometric=False,
            ))
        search_text = self._extract_search_text(user_prompt)
        if search_text:
            steps.extend([
                PlanStep(step_id=len(steps) + 1, action=ActionType.CLICK, target_node="Search", input_data=None),
                PlanStep(step_id=len(steps) + 2, action=ActionType.TYPE_TEXT, target_node="search field", input_data=search_text),
            ])
        return ActionPlanResponse(
            plan_id=f"plan_{uuid.uuid4().hex[:8]}",
            user_goal=user_prompt,
            risk_level=risk_level,
            total_steps=len(steps),
            is_completed=False,
            steps=steps,
            speech_response=f"Executing plan for {user_prompt}"
        )

    def _extract_search_text(self, user_prompt: str) -> Optional[str]:
        match = re.search(r"\bsearch(?:\s+for)?\s+(.+)", user_prompt, flags=re.IGNORECASE)
        if match:
            return match.group(1).strip(" .?!")
        match = re.search(r"\b(?:play|watch)\s+(.+?)\s+(?:on|in)\s+youtube\b", user_prompt, flags=re.IGNORECASE)
        if match:
            return match.group(1).strip(" .?!")
        match = re.search(r"\b(?:send|message|text)\s+(.+?)\s+(?:to|on)\s+(.+)", user_prompt, flags=re.IGNORECASE)
        if match:
            return match.group(1).strip(" .?!")
        match = re.search(r"\b(?:play|listen to)\s+(.+?)\s+(?:on|in)\s+spotify\b", user_prompt, flags=re.IGNORECASE)
        if match:
            return match.group(1).strip(" .?!")
        return None

    def _resolve_app_launch(self, user_prompt: str):
        """
        Dynamically extracts the target application name or package hint from user prompt.
        Handles both mapped standard packages and arbitrary third-party apps for on-device resolution.
        """
        normalized_prompt = user_prompt.lower()

        for app_name in sorted(self.APP_PACKAGE_HINTS, key=len, reverse=True):
            if re.search(rf"\b{re.escape(app_name)}\b", normalized_prompt):
                return self.APP_PACKAGE_HINTS[app_name]

        # Dynamic regex for arbitrary apps: "open Swiggy", "on Spotify", "launch Slack", "in Zomato"
        match = re.search(r"\b(?:open|launch|on|in|using)\s+([a-zA-Z0-9\s]+)", user_prompt, re.IGNORECASE)
        if match:
            extracted_label = match.group(1).strip().split()[0].capitalize()
            return (None, None, extracted_label)

        return None

    def _enforce_app_launch_first_step(self, plan: ActionPlanResponse, app_launch) -> None:
        if not app_launch:
            return
        package_name, fallback_package_name, app_label = app_launch
        launch_step = PlanStep(
            step_id=1,
            action=ActionType.OPEN_APP,
            target_node=package_name or app_label,
            package_name=package_name,
            fallback_package_name=fallback_package_name,
            target_text=app_label,
            input_data=None,
            tier=WaterfallTier.TIER_1_INTENT,
            requires_biometric=False,
        )
        if plan.steps and plan.steps[0].action == ActionType.OPEN_APP:
            plan.steps[0] = launch_step
        else:
            plan.steps.insert(0, launch_step)

        for index, step in enumerate(plan.steps, start=1):
            step.step_id = index
        plan.total_steps = len(plan.steps)
        plan.is_completed = False

    @staticmethod
    def _attach_vision_coordinates(plan: ActionPlanResponse, screen_state: List[Dict[str, Any]]) -> None:
        """Map Service 03 tap coordinates onto the current click/type step."""
        vision_nodes = [
            node
            for node in screen_state
            if isinstance(node, dict) and node.get("tap_x") is not None and node.get("tap_y") is not None
        ]
        if not vision_nodes or not plan.steps:
            return

        index = plan.current_step_index if 0 <= plan.current_step_index < len(plan.steps) else 0
        step = plan.steps[index]
        if step.action not in {ActionType.CLICK, ActionType.TYPE_TEXT}:
            return

        target = (step.target_text or step.target_node or "").strip().lower()
        matched: Optional[Dict[str, Any]] = None
        for node in vision_nodes:
            if node.get("matched"):
                matched = node
                break
        if target:
            for node in vision_nodes:
                haystack = " ".join(
                    str(node.get(field) or "")
                    for field in ("text", "content_description", "view_id", "label", "ocr_text")
                ).lower()
                if target in haystack or haystack in target:
                    matched = node
                    break
        if matched is None and len(vision_nodes) == 1:
            matched = vision_nodes[0]
        if matched is None:
            return

        step.tap_x = float(matched["tap_x"])
        step.tap_y = float(matched["tap_y"])
        step.execution_mode = "COORDINATE"
        step.tier = WaterfallTier.TIER_4_VISION_OCR
        if not step.target_node:
            step.target_node = str(matched.get("view_id") or matched.get("text") or step.target_text)


planner_service = PlannerService()
