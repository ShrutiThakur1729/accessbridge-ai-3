import re
import uuid
import json
from typing import List, Dict, Any, Optional
from openai import AsyncOpenAI
from app.config import settings
from app.schemas.action_plan import ActionPlanResponse, ActionType, PlanStep, RiskLevel, WaterfallTier
from app.services.safety_service import SafetyService
from app.utils.screen_compressor import compress_screen_state


class PlannerService:
    # Known primary and optional fallback package names as default hints.
    APP_PACKAGE_MAP = {
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

    # Map text string representations to integer WaterfallTier values
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

    SYSTEM_PROMPT = """You are an autonomous Android UI agent operating turn-by-turn.
At each turn, evaluate:
1. "Is the user's ultimate goal fully satisfied on the current screen?"
   - YES -> Return action "FINISH" with is_completed = true.
   - NO  -> Return the single best next UI action (e.g., CLICK, TYPE_TEXT, OPEN_APP, SWIPE_UP) based on the CURRENT screen elements with is_completed = false.

DECISION RULES & MULTI-STEP GUARDRAILS:
- Launching or opening an application (OPEN_APP) is ONLY an intermediate setup step (Step 1 of N). Opening an app NEVER satisfies a complex task (e.g., messaging, searching, booking). Do NOT return "FINISH" or set `is_completed = true` after an OPEN_APP action unless the entire user intent was strictly to open the app and nothing more.
- For any request with post-open work, OPEN_APP is step 1 only. Continue with visible in-app controls until the requested content or target state is visible.
- If the target app is not in the foreground, issue OPEN_APP with `is_completed: false`.
- If the target app IS already open in foreground (checked via `current_app_package`), DO NOT issue OPEN_APP or SWIPE_UP. Look at the visible screen elements and interact with search bars, buttons, or input fields directly.

CRITICAL OUTPUT REQUIREMENT:
You MUST return a JSON object strictly matching the following schema. Never output a raw action or raw array directly.

JSON SCHEMA:
{
  "plan_id": "string",
  "user_goal": "string",
  "risk_level": "LOW | MEDIUM | HIGH | RED",
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

Note for 'tier': Use integer values:
- 1 for TIER_1_INTENT
- 2 for TIER_2_HEURISTIC
- 3 for TIER_3_ACCESSIBILITY
- 4 for TIER_4_VISION

OPERATIONAL INVARIANTS (STRICT):
1. APP STATE MANAGEMENT & NO REPEAT LAUNCHES:
   - RULE 1: If `action_status` is SUCCESS for OPEN_APP, or action_history ends in OPEN_APP with status SUCCESS, it is STRICTLY FORBIDDEN to output OPEN_APP again.
   - RULE 2: If the requested package is already the active foreground app, inspect `screen_state` and select the next in-app interaction: CLICK, TYPE_TEXT, or SCROLL. Never launch the same app again.
   - RULE 3: For messaging and search tasks, OPEN_APP is only step 1. Then CLICK the visible search bar/contact, TYPE_TEXT the search/contact/message data, and CLICK the visible send/result control. Never finish early or repeat step 1.
   - If `current_app_package` indicates the requested app is already active in the foreground, DO NOT issue 'OPEN_APP' or click on the app's top header label. Move directly to interacting with actionable controls.
   - Only issue 'OPEN_APP' if you are on the Android Home Screen/Launcher or in an unrelated app.
   - DYNAMIC FALLBACK: If an 'OPEN_APP' action previously returned 'FAILED', NEVER re-issue 'OPEN_APP'. Instead, open the App Drawer using 'SWIPE_UP', locate the search bar or icon, type the app name, and launch it via UI clicks.

2. TEXT INPUT & SEARCH WORKFLOWS:
   - UNIVERSAL RULE: If `active_input_focused` is `true`, your primary action for text-delivery or search intents MUST be `TYPE_TEXT` targeting the focused field. Do NOT issue navigation commands (`CLICK`, `BACK`, `SWIPE`) when an input field is ready.
   - When searching for item targets, select ONLY from `interactive_targets`. Do not select from `secondary_metadata`.
   - If an input field (EditText, Search Bar, Input Area) is focused, editable, or was clicked in the immediately preceding turn, your action MUST be 'TYPE_TEXT'.
   - Place the search phrase, query, or text payload into `input_data`. Do not click the search bar again if an input field is already active.

3. ACTION DEDUPLICATION & LOOP PREVENTION:
   - Evaluate `last_attempted_action` and `action_status`. Never re-issue the exact same action if `action_status` was 'SUCCESS' but the UI state remained identical.
   - If trapped in a state loop, change target interaction: target an active input field directly, click a specific inner child view ID, or perform a 'SCROLL' / 'SWIPE_UP'.

4. GENERAL UI NAVIGATION STRATEGY:
   - Prioritize global search icons or input fields before scrolling un-filtered lists.
   - Issue 'FINISH' and set `is_completed: true` ONLY when the end objective (e.g., message sent, item booked, target screen displayed) is verified by the supplied `screen_state`. A non-empty screen alone is not permission to finish; cite the visible success/target evidence in the step reason.
"""

    def __init__(self):
        self.client = AsyncOpenAI(
            api_key=settings.GROQ_API_KEY,
            base_url="https://api.groq.com/openai/v1",
        ) if getattr(settings, "GROQ_API_KEY", None) else None

    def _clean_screen_summary(self, screen_state: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        cleaned = []
        for elem in screen_state:
            view_id = str(elem.get("view_id", "") or "").lower()
            class_name = str(elem.get("class_name", "") or elem.get("className", "") or "").lower()
            text = str(elem.get("text") or elem.get("content_description") or elem.get("view_id") or "").strip()

            if "keyboard" in class_name or "softkeyboard" in class_name or "keyboard" in view_id:
                continue

            # Preserves single-character UI elements (e.g., "+", "<", ">", "X")
            if text:
                cleaned.append({
                    "text": text,
                    "view_id": elem.get("view_id", ""),
                    "is_clickable": elem.get("is_clickable", True),
                    "is_editable": elem.get("is_editable", False)
                })

            if len(cleaned) >= 120:
                break

        return cleaned

    def _parse_tier_value(self, raw_tier: Any) -> int:
        """Converts raw LLM tier value to integer expected by WaterfallTier IntEnum."""
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
        """Normalizes and wraps raw LLM dict output into a valid ActionPlanResponse payload."""
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

            action_raw = str(step.get("action", "CLICK")).upper()
            tier_val = self._parse_tier_value(step.get("tier"))

            normalized_step = {
                "step_id": step.get("step_id", idx),
                "action": action_raw,
                "target_node": step.get("target_node") or step.get("view_id") or None,
                "target_text": step.get("target_text") or step.get("text") or None,
                "input_data": step.get("input_data") or step.get("value") or None,
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
    def _enforce_completion_invariants(plan: ActionPlanResponse, screen_state: List[Dict[str, Any]]) -> None:
        """Completion is valid only for an explicit, screen-verified FINISH directive."""
        current_step = (
            plan.steps[plan.current_step_index]
            if 0 <= plan.current_step_index < len(plan.steps)
            else None
        )
        if current_step and current_step.action == ActionType.FINISH and screen_state:
            plan.is_completed = True
            return

        # OPEN_APP, CLICK, TYPE_TEXT, recovery, and an unverified FINISH are all
        # intermediate states. Do not let an LLM completion flag bypass this.
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
    ) -> ActionPlanResponse:
        screen_state = screen_state or []
        risk_level = SafetyService.evaluate_risk(user_prompt)
        requires_bio = SafetyService.requires_biometric_check(risk_level)
        app_launch = self._resolve_app_launch(user_prompt)
        action_history = action_history or []
        app_opened = self._app_was_opened(action_status, target_action, action_history)

        # GUARD 1: Handling OPEN_APP Failure (Trigger App Drawer Fallback)
        if action_status == "FAILED" and target_action == ActionType.OPEN_APP.value:
            return ActionPlanResponse(
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
            )

        # GUARD 2: Handling SUCCESS with 0 elements (App launched/transitioning)
        if action_status == "SUCCESS" and len(screen_state) == 0:
            is_multistep = self._is_multistep_goal(user_prompt, target_action)
            if is_multistep:
                return ActionPlanResponse(
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
                )

        if not self.client or not getattr(settings, "GROQ_API_KEY", None):
            plan = self._get_fallback_mock_plan(user_prompt, risk_level, requires_bio, app_launch)
            if app_opened or self._is_target_app_active(current_app_package, app_launch):
                self._replace_repeated_open_with_in_app_action(plan, user_prompt, screen_state)
            self._prioritize_focused_input(plan, user_prompt, compress_screen_state(screen_state))
            self._enforce_completion_invariants(plan, screen_state)
            return plan

        screen_context = compress_screen_state(screen_state)

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
            model_name = getattr(settings, "DEFAULT_LLM_MODEL", "llama-3.3-70b-versatile")
            completion = await self.client.chat.completions.create(
                model=model_name,
                messages=[
                    {"role": "system", "content": self.SYSTEM_PROMPT},
                    {"role": "user", "content": user_content}
                ],
                response_format={"type": "json_object"},
                max_tokens=150,
                temperature=0.0,
            )

            raw_content = completion.choices[0].message.content
            plan_dict = json.loads(raw_content)

            wrapped_payload = self._normalize_and_wrap_plan_dict(plan_dict, user_prompt, risk_level)
            plan = ActionPlanResponse(**wrapped_payload)

            # Prevent forcing OPEN_APP if app is already active in foreground
            if action_status == "INITIAL" and app_launch:
                target_package = app_launch[0]
                is_already_open = False
                if current_app_package and target_package:
                    is_already_open = target_package.lower() in current_app_package.lower()

                if not is_already_open:
                    self._enforce_app_launch_first_step(plan, app_launch)

            if app_opened or self._is_target_app_active(current_app_package, app_launch):
                self._replace_repeated_open_with_in_app_action(plan, user_prompt, screen_state)

            self._prioritize_focused_input(plan, user_prompt, screen_context)

            # Safeguard: Ensure OPEN_APP does not incorrectly mark multi-step tasks as complete
            if plan.steps and plan.steps[0].action == ActionType.OPEN_APP and self._is_multistep_goal(user_prompt, target_action):
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

            return plan

        except Exception as e:
            print(f"LLM Planner warning: {e}. Falling back to default mock plan.")
            return self._get_fallback_mock_plan(user_prompt, risk_level, requires_bio, app_launch)

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
        last_action = action_history[-1]
        return (
            str(last_action.get("action_type", last_action.get("action", ""))).upper() == ActionType.OPEN_APP.value
            and str(last_action.get("status", "")).upper() == "SUCCESS"
        )

    @staticmethod
    def _is_target_app_active(current_app_package: Optional[str], app_launch) -> bool:
        if not current_app_package or not app_launch or not app_launch[0]:
            return False
        return current_app_package.lower() == app_launch[0].lower()

    def _replace_repeated_open_with_in_app_action(
        self,
        plan: ActionPlanResponse,
        user_prompt: str,
        screen_state: List[Dict[str, Any]],
    ) -> None:
        """Never let a successful app launch be followed by another OPEN_APP."""
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

        searchable_nodes = [node for node in screen_state if "search" in node_text(node).lower()]
        editable_nodes = [node for node in screen_state if node.get("is_editable") or node.get("isEditable") or node.get("editable")]
        clickable_nodes = [node for node in screen_state if node.get("is_clickable") or node.get("isClickable") or node.get("clickable")]
        old_step = plan.steps[plan.current_step_index]
        if searchable_nodes:
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.CLICK,
                target_node=node_text(searchable_nodes[0]),
                target_text=node_text(searchable_nodes[0]),
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is already open; using the visible in-app search control.",
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
                reason="App is already open; entering data into the visible input field.",
            )
        elif clickable_nodes:
            target = node_text(clickable_nodes[0])
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.CLICK,
                target_node=target,
                target_text=target,
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is already open; continuing with a visible in-app control.",
            )
        else:
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.SCROLL,
                target_text="rescan_wait",
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is already open; waiting for actionable in-app elements.",
            )
        plan.is_completed = False

    def _prioritize_focused_input(
        self,
        plan: ActionPlanResponse,
        user_prompt: str,
        screen_context: Dict[str, Any],
    ) -> None:
        """Apply the universal focused-input rule independently of app identity."""
        text_intent = any(word in user_prompt.lower() for word in ("search", "find", "send", "message", "type", "play", "watch"))
        if (
            not text_intent
            or not screen_context["active_input_focused"]
            or not plan.steps
            or plan.steps[plan.current_step_index].action == ActionType.TYPE_TEXT
        ):
            return
        current_step = plan.steps[plan.current_step_index]
        target = screen_context["focused_hint"] or "focused input field"
        plan.steps[plan.current_step_index] = PlanStep(
            step_id=current_step.step_id,
            action=ActionType.TYPE_TEXT,
            target_node=target,
            target_text=target,
            input_data=self._extract_search_text(user_prompt) or user_prompt,
            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
            reason="Focused input is ready; entering the requested text.",
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
- Action History: {json.dumps(action_history[-10:])}

VISIBLE SCREEN STATE ({total_screen_elements} raw nodes detected):
{json.dumps(screen_context, indent=2)}

Analyze the screen state in relation to the original user intent. Return the complete ActionPlanResponse JSON object.
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
        if requires_bio:
            steps.append(
                PlanStep(
                    step_id=len(steps) + 1,
                    action=ActionType.CONFIRM_BIOMETRIC,
                    target_node="pay_confirm_btn",
                    input_data=None,
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    requires_biometric=True,
                )
            )
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
        return match.group(1).strip(" .?!") if match else None

    def _resolve_app_launch(self, user_prompt: str):
        normalized_prompt = user_prompt.lower()
        
        for app_name in sorted(self.APP_PACKAGE_MAP, key=len, reverse=True):
            if re.search(rf"\b{re.escape(app_name)}\b", normalized_prompt):
                return self.APP_PACKAGE_MAP[app_name]
        
        match = re.search(r"\b(?:open|launch|on|using)\s+([a-zA-Z0-9\s]+)", user_prompt, re.IGNORECASE)
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


planner_service = PlannerService()
