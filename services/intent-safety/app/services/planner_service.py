import re
import uuid
import json
import os
import unicodedata
from typing import List, Dict, Any, Optional
from openai import AsyncOpenAI

from app.config import settings
from app.schemas.action_plan import ActionPlanResponse, ActionType, PlanStep, RiskLevel, WaterfallTier
from app.services.safety_service import SafetyService
from app.services.vision_client import vision_client
from app.utils.screen_compressor import compress_screen_state


def sanitize_text(text: Optional[str]) -> str:
    """
    Sanitize text strings by stripping invisible zero-width spaces (\u200B-\u200D), BOM (\ufeff),
    non-breaking spaces (\u00A0), ASCII control characters, and non-printable artifacts.
    """
    if text is None:
        return ""
    clean = unicodedata.normalize("NFKC", str(text)).replace("\u00a0", " ")
    clean = re.sub(r"[\u0000-\u001f\u007f-\u009f\u200b-\u200d\ufeff\ufffd]", "", clean)
    clean = clean.replace("Ã—", "×").replace("Â", "")
    return re.sub(r"\s+", " ", clean).strip()


def _node_text(node: Dict[str, Any]) -> str:
    """Return the sanitized, searchable metadata for one accessibility node."""
    return " ".join(
        sanitize_text(node.get(field))
        for field in ("class_name", "className", "view_id", "viewId", "resource_id", "resourceId",
                      "content_description", "contentDescription", "hint", "text")
        if sanitize_text(node.get(field))
    )


def _node_is_clickable(node: Dict[str, Any]) -> bool:
    return bool(node.get("is_clickable") or node.get("isClickable") or node.get("clickable"))


def _node_is_editable(node: Dict[str, Any]) -> bool:
    return bool(node.get("is_editable") or node.get("isEditable") or node.get("editable"))


def _node_top(node: Dict[str, Any]) -> Optional[float]:
    """Read a node's top coordinate from common Android dump formats."""
    bounds = node.get("bounds") or node.get("bounds_in_screen") or node.get("boundsInScreen")
    if isinstance(bounds, (list, tuple)) and len(bounds) >= 2:
        try:
            return float(bounds[1])
        except (TypeError, ValueError):
            return None
    if isinstance(bounds, str):
        values = re.findall(r"-?\d+(?:\.\d+)?", bounds)
        if len(values) >= 2:
            return float(values[1])
    for key in ("top", "y", "bounds_top"):
        if node.get(key) is not None:
            try:
                return float(node[key])
            except (TypeError, ValueError):
                pass
    return None


def _classify_ui_topology(screen_state: List[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
    """Classify raw Android accessibility nodes into app-agnostic UI zones."""
    zones = {name: [] for name in (
        "INPUT_ZONE", "ACTION_SUBMIT_ZONE", "LIST_ITEM_ZONE", "HEADER_NAV_ZONE", "SYSTEM_CONTAINER_ZONE"
    )}
    input_tops = []
    prepared = []
    for node in screen_state or []:
        if not isinstance(node, dict):
            continue
        metadata = _node_text(node).lower()
        top = _node_top(node)
        prepared.append((node, metadata, top))
        if _node_is_editable(node) or any(token in metadata for token in (
            "edittext", "search", "type a message", "message", "text box", "textbox", "ask"
        )):
            zones["INPUT_ZONE"].append(node)
            if top is not None:
                input_tops.append(top)

    lowest_input = max(input_tops) if input_tops else None
    for node, metadata, top in prepared:
        clickable = _node_is_clickable(node)
        class_name = sanitize_text(node.get("class_name") or node.get("className")).lower()
        is_input = node in zones["INPUT_ZONE"]
        is_system = any(token in metadata for token in (
            "folder name", "samsung, folder", "launcher", "workspace", "folder_title", "folder title"
        )) or ("launcher" in class_name and ("folder" in metadata or "container" in metadata))
        if is_system:
            zones["SYSTEM_CONTAINER_ZONE"].append(node)
        if clickable and any(token in metadata for token in (
            "send", "submit", "save", "done", "check", "search", "magnifier", "btn_search",
            "fab", "floatingactionbutton", "add", "plus", "+"
        )):
            zones["ACTION_SUBMIT_ZONE"].append(node)
        if clickable and any(token in metadata for token in (
            "back", "navigate up", "call", "video", "profile", "toolbar", "header"
        )):
            zones["HEADER_NAV_ZONE"].append(node)
        if not clickable and top is not None and top <= 220 and not is_input:
            zones["HEADER_NAV_ZONE"].append(node)
        if clickable and not is_input and not is_system:
            is_below_input = lowest_input is None or top is None or top > lowest_input
            is_action = node in zones["ACTION_SUBMIT_ZONE"] or node in zones["HEADER_NAV_ZONE"]
            if is_below_input and not is_action:
                zones["LIST_ITEM_ZONE"].append(node)
    return zones


def _extract_intent_payloads(user_prompt: str, field_type: str) -> str:
    """Extract only text appropriate for a target selector or message-composition field."""
    prompt = sanitize_text(user_prompt)
    if not prompt:
        return ""
    kind = sanitize_text(field_type).upper()

    def clean(value: str) -> str:
        value = sanitize_text(value).strip(" \t\"'")
        value = re.sub(r"\s+\b(?:on|via|using|in)\s+.+$", "", value, flags=re.IGNORECASE)
        return value.strip(" \t\"'")

    if kind == "MESSAGE_PAYLOAD":
        match = re.search(r"\b(?:saying|texting)\s+(?:that\s+)?[\"']?(.+?)[\"']?(?:\s*$)", prompt, re.I)
        if not match:
            match = re.search(r"\b(?:message|text|write)\s+(?!to\b)(?:that\s+)?[\"']?(.+?)[\"']?(?:\s*$)", prompt, re.I)
        if match:
            return clean(match.group(1))
        match = re.search(r"\b(?:send|say)\s+[\"']?(.+?)[\"']?\s+\b(?:to|via)\b", prompt, re.I)
        return clean(match.group(1)) if match else ""

    patterns = (
        r"\b(?:search(?:\s+for)?|find|look\s*up|play|watch|navigate\s+to|go\s+to|open|select)\s+[\"']?(.+?)[\"']?(?=\s+\b(?:on|via|using|in|saying|message|text)\b|$)",
        r"\b(?:send|message|text|call)\s+(?:a\s+)?(?:message\s+)?\bto\s+[\"']?(.+?)[\"']?(?=\s+\b(?:on|via|using|in|saying|message|text)\b|$)",
        r"\b(?:set|create|add|remind)\b.*?\b(?:for|at|to)\s+[\"']?(.+?)[\"']?(?=\s+\b(?:on|via|using|in|saying|message|text)\b|$)",
    )
    for pattern in patterns:
        match = re.search(pattern, prompt, re.I)
        if match and clean(match.group(1)):
            return clean(match.group(1))
    if not re.search(r"\b(?:to|on|via|in|saying|using)\b", prompt, re.I):
        return clean(prompt)
    return ""


def _extract_clean_entity(user_prompt: str, field_context: str = "") -> str:
    """Classifies field context dynamically into Target Entity or Message Payload."""
    field_hint = sanitize_text(field_context).lower()
    if any(keyword in field_hint for keyword in (
        "type a message", "message", "write a reply", "type message", "chat_input",
        "entry_message", "input_message", "send message",
    )):
        return _extract_intent_payloads(user_prompt, "MESSAGE_PAYLOAD")
    return _extract_intent_payloads(user_prompt, "TARGET_ENTITY")


class PlannerService:
    APP_PACKAGE_HINTS = {
        "whatsapp": ("com.whatsapp", None, "WhatsApp"),
        "uber": ("com.ubercab", None, "Uber"),
        "hotstar": ("com.ripl.hotstar", "in.startv.hotstar", "Hotstar"),
        "jio hotstar": ("com.ripl.hotstar", "in.startv.hotstar", "Jio Hotstar"),
        "disney hotstar": ("com.ripl.hotstar", "in.startv.hotstar", "Disney Hotstar"),
        "amazon": ("com.amazon.mShop.android.shopping", None, "Amazon"),
        "amazon shopping": ("com.amazon.mShop.android.shopping", None, "Amazon Shopping"),
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

    SYSTEM_PROMPT = """You are an autonomous, universal Android UI Automation Agent operating turn-by-turn.

STRICT FORMAT REQUIREMENTS:
- Respond ONLY with a valid JSON object matching the requested schema.
- Do NOT include markdown code blocks (```json), conversational text, or commentary.
- Return raw JSON starting with '{' and ending with '}'.

UNIVERSAL EXECUTION RULES (STRICT):

1. FOREGROUND PACKAGE CHECK:
   - Examine `current_package_name`.
   - If `current_package_name` belongs to the target application (or the app is active in foreground), NEVER output `OPEN_APP`.
   - If the target application is NOT in the foreground, output `OPEN_APP` with `package_name` or `target_node` set to the application name.

2. DYNAMIC INPUT PARAMETER ISOLATION (UNIVERSAL):
   - When issuing `TYPE_TEXT`, `input_data` MUST contain ONLY the target item/entity/message to be typed, NEVER the full user voice prompt.
   - Analyze the target field context (`focused_hint` or `target_node`) and extract the relevant parameter:
     * If focused on a Search / Recipient / Contact field -> set `input_data` to ONLY the name or entity (e.g., "Siddhi thanekar", "Lofi music", "Thane station").
     * If focused on a Chat / Body / Comment field -> set `input_data` to ONLY the payload message (e.g., "hello").
     * Never include command phrases like "send a message to", "on WhatsApp", or "saying".

3. VALID TARGET CHECK:
   - When issuing `CLICK`, `target_node` or `target_text` MUST contain a valid non-empty label matching an interactive element on screen.
   - Never output `CLICK` with empty target fields.

4. COMPLETION RULE:
   - Set `is_completed: true` with `action: "FINISH"` ONLY when the user's ultimate goal is visibly satisfied on screen.

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

    def __init__(self):
        self.use_llm_mock = os.getenv(
            "USE_LLM_MOCK", str(getattr(settings, "USE_LLM_MOCK", False))
        ).strip().lower() in {"1", "true", "yes", "on"}
        self.client = AsyncOpenAI(
            api_key=getattr(settings, "OPENAI_API_KEY", None) or "ollama",
            base_url=getattr(settings, "OPENAI_API_BASE", "http://localhost:11434/v1"),
        ) if not self.use_llm_mock else None
        self._task_failure_counts: Dict[str, Dict[str, int]] = {}
        self._app_drawer_searches: Dict[str, str] = {}
        self._pending_install_intents: Dict[str, str] = {}
        self._bottom_sheet_blacklists: Dict[str, set[str]] = {}

    @staticmethod
    def _is_dismissive_label(label: str) -> bool:
        return sanitize_text(label).lower().strip() in {
            "close", "dismiss", "skip", "not now", "no thanks", "cancel", "maybe later", "clear", "clear query", "x", "×"
        }

    @staticmethod
    def _is_post_action_modal_state(
        action_history: Optional[List[Dict[str, Any]]],
        last_attempted_action: Optional[str],
        action_status: str,
    ) -> bool:
        """Detect the turn immediately after a control likely opened a modal."""
        last = (action_history or [])[-1] if action_history else {}
        action = str(last.get("action_type") or last.get("action") or last_attempted_action or "").upper()
        status = str(last.get("status") or action_status or "").upper()
        target = sanitize_text(last.get("target_node") or last.get("target_text")).lower()
        modal_keywords = ("add", "create", "set", "edit", "new", "picker", "address")
        return status == "SUCCESS" and action == ActionType.CLICK.value and any(word in target for word in modal_keywords)

    def _enforce_post_action_modal_protection(
        self,
        plan: ActionPlanResponse,
        action_history: Optional[List[Dict[str, Any]]],
        last_attempted_action: Optional[str],
        action_status: str,
    ) -> None:
        """Do not let a modal-opening click be immediately undone by dismissal."""
        if not self._is_post_action_modal_state(action_history, last_attempted_action, action_status) or not plan.steps:
            return
        index = plan.current_step_index if 0 <= plan.current_step_index < len(plan.steps) else 0
        step = plan.steps[index]
        if step.action == ActionType.CLICK and self._is_dismissive_label(step.target_node or step.target_text or ""):
            plan.steps[index] = PlanStep(
                step_id=step.step_id, action=ActionType.SCROLL, target_node=None, target_text="rescan_wait",
                tier=WaterfallTier.TIER_3_ACCESSIBILITY, requires_biometric=False,
                reason="Post-action modal protection: refusing to dismiss the dialog opened on the preceding turn.",
            )
            plan.is_completed = False

    @staticmethod
    def _session_state_key(user_id: str, task_id: Optional[str]) -> str:
        return f"{sanitize_text(user_id) or 'anonymous'}:{sanitize_text(task_id) or 'default'}"

    @staticmethod
    def _is_install_confirmation(user_prompt: str) -> bool:
        return bool(re.match(r"^\s*(?:yes|yeah|yep|sure|ok(?:ay)?|install(?:\s+it)?)\s*[!.]?\s*$", user_prompt, re.I))

    @staticmethod
    def _build_play_store_install_plan(user_prompt: str, app_name: str, risk_level: RiskLevel) -> ActionPlanResponse:
        return ActionPlanResponse(
            plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
            total_steps=3, current_step_index=0, is_completed=False,
            speech_response=f"Opening Play Store to install {app_name}.",
            steps=[
                PlanStep(step_id=1, action=ActionType.OPEN_APP, target_node="com.android.vending",
                         target_text="Google Play Store", package_name="com.android.vending",
                         tier=WaterfallTier.TIER_1_INTENT, requires_biometric=False,
                         reason="User confirmed installation of an unavailable application."),
                PlanStep(step_id=2, action=ActionType.TYPE_TEXT, target_node="Search apps & games",
                         target_text="Search apps & games", input_data=app_name,
                         tier=WaterfallTier.TIER_3_ACCESSIBILITY, requires_biometric=False,
                         reason="Searching Play Store for the requested application."),
                PlanStep(step_id=3, action=ActionType.CLICK, target_node="Install", target_text="Install",
                         tier=WaterfallTier.TIER_3_ACCESSIBILITY, requires_biometric=False,
                         reason="Selecting the Play Store install action after user confirmation."),
            ],
        )

    @staticmethod
    def _looks_like_launcher_or_app_drawer(
        current_app_package: Optional[str],
        screen_state: List[Dict[str, Any]],
    ) -> bool:
        package = sanitize_text(current_app_package).lower()
        if any(token in package for token in ("launcher", "quickstep", "home", "trebuchet", "miui")):
            return True
        metadata = " ".join(_node_text(node).lower() for node in screen_state if isinstance(node, dict))
        return any(token in metadata for token in ("app drawer", "search apps", "find apps", "all apps", "folder name", "samsung, folder"))

    def _handle_app_drawer_recovery(
        self,
        user_prompt: str,
        screen_state: List[Dict[str, Any]],
        risk_level: RiskLevel,
        action_status: str,
        target_action: Optional[str],
        current_app_package: Optional[str],
        session_key: str,
        action_history: Optional[List[Dict[str, Any]]] = None,
    ) -> Optional[ActionPlanResponse]:
        """Keep app-launch recovery deterministic and outside generic LLM planning."""
        action = str(target_action or "").upper()
        status = action_status.upper()
        if status == "FAILED" and action == ActionType.OPEN_APP.value:
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                speech_response="Direct application launch failed. Opening App Drawer to search manually.",
                steps=[PlanStep(step_id=1, action=ActionType.SWIPE_UP, target_node="home_screen",
                                target_text="Open App Drawer", tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                                requires_biometric=False,
                                reason="App launch recovery: opening the App Drawer without selecting home-screen folders.")],
            )

        last = (action_history or [])[-1] if action_history else {}
        last_action = str(last.get("action_type") or last.get("action") or action).upper()
        drawer_after_swipe = (
            status in {"SUCCESS", "INITIAL"}
            and last_action == ActionType.SWIPE_UP.value
            and self._looks_like_launcher_or_app_drawer(current_app_package, screen_state)
        )
        if not drawer_after_swipe:
            return None

        # FIX 3: Broadened search bar matching across screen state in App Drawer
        search_bar = next((
            node for node in screen_state
            if isinstance(node, dict) and (
                _node_is_editable(node)
                or any(token in _node_text(node).lower() for token in ("search", "find", "edittext", "apps"))
            )
        ), None)

        if not search_bar:
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                speech_response="Waiting for the App Drawer search field.",
                steps=[PlanStep(step_id=1, action=ActionType.SCROLL, target_text="rescan_wait",
                                tier=WaterfallTier.TIER_3_ACCESSIBILITY, requires_biometric=False,
                                reason="App Drawer recovery will not guess or click launcher folders.")],
            )

        app_name = self._extract_app_name_for_search(user_prompt)
        self._app_drawer_searches[session_key] = app_name
        target = self._node_target_label(search_bar)
        return ActionPlanResponse(
            plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
            total_steps=1, current_step_index=0, is_completed=False,
            speech_response=f"App Drawer active. Searching for {app_name}.",
            steps=[PlanStep(step_id=1, action=ActionType.TYPE_TEXT, target_node=target, target_text=target,
                            input_data=app_name, tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            requires_biometric=False,
                            reason="App Drawer recovery: typing the extracted app name into its search field.")],
        )

    @staticmethod
    def _get_llm_mock_plan(user_prompt: str, risk_level: RiskLevel) -> ActionPlanResponse:
        return ActionPlanResponse(
            plan_id="plan_llm_mock",
            user_goal=user_prompt,
            risk_level=risk_level,
            total_steps=1,
            current_step_index=0,
            is_completed=False,
            speech_response="LLM mock enabled. Returning local test directive.",
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

            target_node = sanitize_text(step.get("target_node") or step.get("view_id")) or None
            target_text = sanitize_text(step.get("target_text") or step.get("text")) or None

            # GUARD: Convert empty clicks into safe rescan waits
            if action_raw == "CLICK" and not target_node and not target_text:
                action_raw = "SCROLL"
                target_text = "rescan_wait"

            normalized_step = {
                "step_id": step.get("step_id", idx),
                "action": action_raw,
                "target_node": target_node,
                "target_text": target_text,
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
        current_step = (
            plan.steps[plan.current_step_index]
            if 0 <= plan.current_step_index < len(plan.steps)
            else None
        )

        if current_step and current_step.action == ActionType.HARD_STOP:
            plan.is_completed = True
            plan.error = plan.error or "HARD_STOP"
            return

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
        blacklisted_targets: Optional[set[str]] = None,
    ) -> Optional[ActionPlanResponse]:
        prompt = user_prompt.strip().lower()
        if not prompt or any(word in prompt for word in (" then ", " after ", " and then ", "next ", "navigate")):
            return None

        type_match = re.match(r"^(?:type|enter|write)\s+(.+)$", prompt, re.IGNORECASE)
        if active_input_focused and type_match:
            target = sanitize_text(focused_hint) or "focused input"
            extracted_val = _extract_clean_entity(type_match.group(1), target)
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                session_language=session_language, execution_state="EXECUTING", approach="LOCAL_RESOLUTION",
                speech_response=f"Entering text into {target}.",
                steps=[PlanStep(step_id=1, action=ActionType.TYPE_TEXT, target_node=target,
                                target_text=target, input_data=extracted_val,
                                tier=WaterfallTier.TIER_3_ACCESSIBILITY, requires_biometric=False,
                                reason="Exact focused-input command resolved locally.")],
            )

        if not re.match(r"^(?:tap|click|select|open)\b", prompt, re.IGNORECASE):
            return None
        matches: List[str] = []
        for node in screen_state:
            if not isinstance(node, dict) or not bool(node.get("is_clickable", node.get("isClickable", node.get("clickable", False)))):
                continue
            label = next((sanitize_text(node.get(field)) for field in ("text", "content_description", "contentDescription", "view_id", "viewId") if sanitize_text(node.get(field))), "")
            if len(label) >= 2 and label.lower() in prompt and label not in matches:
                if blacklisted_targets and any(blk in label.lower() or label.lower() in blk for blk in blacklisted_targets if blk):
                    continue
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

    def _dismiss_overlay_if_present(
        self,
        screen_state: List[Dict[str, Any]],
        user_prompt: str,
        risk_level: RiskLevel,
        action_history: Optional[List[Dict[str, Any]]] = None,
        last_attempted_action: Optional[str] = None,
        action_status: str = "INITIAL",
    ) -> Optional[ActionPlanResponse]:
        """Universal Pop-Up & Modal Interceptor with strict state enforcement."""
        last_action = action_history[-1] if action_history else {}
        last_act_type = str(last_action.get("action_type") or last_action.get("action") or last_attempted_action or "").upper()
        last_act_status = str(last_action.get("status") or action_status or "").upper()

        if last_act_type == ActionType.TYPE_TEXT.value and last_act_status == "SUCCESS":
            return None

        if self._is_post_action_modal_state(action_history, last_attempted_action, action_status):
            return None

        dismiss_keywords = {"close", "dismiss", "skip", "not now", "no thanks", "cancel", "maybe later"}
        for node in screen_state:
            if not isinstance(node, dict):
                continue
            if not (node.get("is_clickable") or node.get("clickable") or node.get("isClickable")):
                continue
            label = sanitize_text(node.get("text") or node.get("content_description") or node.get("view_id"))
            label_lower = label.lower()
            if label_lower in dismiss_keywords or label_lower in ("x", "×"):
                return ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    current_step_index=0,
                    is_completed=False,
                    speech_response="Dismissing overlay control.",
                    steps=[
                        PlanStep(
                            step_id=1,
                            action=ActionType.CLICK,
                            target_node=label,
                            target_text=label,
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            reason="Universal pop-up/dialog interceptor: clearing screen overlay."
                        )
                    ]
                )
        return None

    def _handle_post_contact_selection(
        self,
        user_prompt: str,
        screen_state: List[Dict[str, Any]],
        risk_level: RiskLevel,
        action_history: Optional[List[Dict[str, Any]]] = None,
        action_status: str = "INITIAL"
    ) -> Optional[ActionPlanResponse]:
        """Detects if previous action was CLICK on contact/search and chat input is active."""
        if not action_history or action_status.upper() != "SUCCESS":
            return None

        last_action = action_history[-1]
        last_act_type = str(last_action.get("action_type") or last_action.get("action") or "").upper()
        if last_act_type != ActionType.CLICK.value:
            return None

        topology = _classify_ui_topology(screen_state)
        chat_input_node = next((
            node for node in topology["INPUT_ZONE"]
            if _node_is_editable(node)
            and "search" not in _node_text(node).lower()
            and any(keyword in _node_text(node).lower() for keyword in (
                "type a message", "message", "write a reply", "chat_input", "type message"
            ))
        ), None)

        if chat_input_node:
            target_label = self._node_target_label(chat_input_node) or "Type a message"
            message_payload = _extract_intent_payloads(user_prompt, "MESSAGE_PAYLOAD")
            
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                user_goal=user_prompt,
                risk_level=risk_level,
                total_steps=1,
                current_step_index=0,
                is_completed=False,
                speech_response="Contact selected. Typing message payload.",
                steps=[
                    PlanStep(
                        step_id=1,
                        action=ActionType.TYPE_TEXT,
                        target_node=target_label,
                        target_text=target_label,
                        input_data=message_payload,
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="Post-contact selection state shift: Direct messaging active chat field."
                    )
                ]
            )
        return None

    @staticmethod
    def _node_target_label(node: Dict[str, Any]) -> str:
        """FIX 1: Fallback to resource_id/resourceId or class name to prevent empty targets."""
        if not isinstance(node, dict):
            return ""
        label = next((
            sanitize_text(node.get(field))
            for field in (
                "text", "content_description", "contentDescription", 
                "view_id", "viewId", "resource_id", "resourceId", "hint", "class_name"
            )
            if sanitize_text(node.get(field))
        ), "")
        if not label:
            cls = sanitize_text(node.get("class_name") or node.get("className"))
            if cls:
                label = cls.split(".")[-1]
        return label

    def _needs_vision_fallback(
        self,
        screen_state: List[Dict[str, Any]],
        user_prompt: str,
        action_status: str,
        target_action: Optional[str],
        action_history: Optional[List[Dict[str, Any]]],
    ) -> bool:
        """Return true only when an interactive action cannot safely use the DOM."""
        if action_status.upper() in {"FAILED", "NOT_CLICKABLE"}:
            return True
        history = action_history or []
        previous_action = str((history[-1] if history else {}).get("action_type") or (history[-1] if history else {}).get("action") or target_action or "").upper()
        post_input = previous_action == ActionType.TYPE_TEXT.value and action_status.upper() == "SUCCESS"
        action_intent = post_input or bool(re.search(
            r"\b(?:send|submit|search|find|save|done|add|create|set)\b", user_prompt, re.I
        ))
        if not action_intent:
            return False
        topology = _classify_ui_topology(screen_state)
        return not any(self._node_target_label(node) for node in topology["ACTION_SUBMIT_ZONE"])

    def _build_vision_coordinate_plan(
        self,
        user_prompt: str,
        risk_level: RiskLevel,
        vision_nodes: List[Dict[str, Any]],
        target_action: Optional[str],
        action_history: Optional[List[Dict[str, Any]]],
    ) -> Optional[ActionPlanResponse]:
        """Translate a Service 04 detection into one safe coordinate click."""
        last = (action_history or [])[-1] if action_history else {}
        prior_target = sanitize_text(last.get("target_node") or last.get("target_text")).lower()
        cue_text = f"{user_prompt} {prior_target} {target_action or ''}".lower()
        if any(word in cue_text for word in ("send", "message", "reply")):
            expected = ("send_button", "send", "paper_plane")
        elif any(word in cue_text for word in ("search", "find", "lookup")):
            expected = ("search_magnifier", "search", "magnifier")
        elif any(word in cue_text for word in ("add", "alarm", "task", "create")):
            expected = ("plus_icon", "plus", "fab", "add")
        elif any(word in cue_text for word in ("save", "done", "confirm", "check")):
            expected = ("check_mark", "check", "done", "save")
        else:
            expected = tuple(token for token in prior_target.split() if len(token) > 2)

        def confident(node: Dict[str, Any]) -> bool:
            confidence = node.get("confidence")
            if confidence is None:
                return True
            try:
                return float(confidence) >= 0.5
            except (TypeError, ValueError):
                return False

        candidate = next((
            node for node in vision_nodes
            if node.get("tap_x") is not None and node.get("tap_y") is not None
            and confident(node)
            and (node.get("matched") or any(token in _node_text(node).lower() for token in expected))
        ), None)
        if not candidate:
            return None
        try:
            tap_x, tap_y = float(candidate["tap_x"]), float(candidate["tap_y"])
        except (TypeError, ValueError):
            return None
        target = self._node_target_label(candidate) or "vision_control"
        return ActionPlanResponse(
            plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
            total_steps=1, current_step_index=0, is_completed=False,
            speech_response="Using visual detection to select the control.", approach="VISION_FALLBACK",
            steps=[PlanStep(step_id=1, action=ActionType.CLICK, target_node=target, target_text=target,
                            tap_x=tap_x, tap_y=tap_y, execution_mode="COORDINATE",
                            tier=WaterfallTier.TIER_4_VISION_OCR, requires_biometric=False,
                            reason="Service 04 visual fallback found a labeled control unavailable to Accessibility.")],
        )

    def _progress_ui_state(
        self,
        user_prompt: str,
        screen_state: List[Dict[str, Any]],
        risk_level: RiskLevel,
        action_history: List[Dict[str, Any]],
        action_status: str,
        target_action: Optional[str],
    ) -> Optional[ActionPlanResponse]:
        """Deterministically advance search-result and post-input UI states."""
        last = action_history[-1] if action_history else {}
        last_action = str(last.get("action_type") or last.get("action") or target_action or "").upper()
        last_status = str(last.get("status") or action_status or "").upper()
        if last_status != "SUCCESS" or last_action != ActionType.TYPE_TEXT.value:
            return None

        topology = _classify_ui_topology(screen_state)
        typed_value = sanitize_text(last.get("input_data") or last.get("value") or last.get("text_to_type")).lower()
        list_candidates = topology["LIST_ITEM_ZONE"]
        matching_result = next((
            node for node in list_candidates
            if typed_value and typed_value in self._node_target_label(node).lower() and self._node_target_label(node)
        ), None)
        if matching_result:
            target = self._node_target_label(matching_result)
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                speech_response="Selecting the matching result.",
                steps=[PlanStep(step_id=1, action=ActionType.CLICK, target_node=target, target_text=target,
                                tier=WaterfallTier.TIER_3_ACCESSIBILITY, requires_biometric=False,
                                reason="State A: typed search value matched a list-item result.")],
            )

        blocked = ("close", "cancel", "clear query", "clear", "dismiss", "back", "navigate up", "x", "×")
        submit_node = next((
            node for node in topology["ACTION_SUBMIT_ZONE"]
            if node not in topology["HEADER_NAV_ZONE"]
            and not (node in topology["INPUT_ZONE"] and _node_is_editable(node))
            and self._node_target_label(node).lower().strip() not in blocked
            and self._node_target_label(node).lower().strip() not in {"song", "songs", "filter", "filters", "all"}
            and self._node_target_label(node)
        ), None)
        if submit_node:
            target = self._node_target_label(submit_node)
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                speech_response="Submitting the entered value.",
                steps=[PlanStep(step_id=1, action=ActionType.CLICK, target_node=target, target_text=target,
                                tier=WaterfallTier.TIER_3_ACCESSIBILITY, requires_biometric=False,
                                reason="State C: safe post-input submit control.")],
            )
        return None

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
        blacklisted_targets = self._update_and_get_blacklisted_targets(
            task_id=task_id,
            action_status=action_status,
            target_action=target_action,
            action_history=action_history,
        )

        risk_level = SafetyService.evaluate_risk(user_prompt)
        red_reason = SafetyService.classify_red_reason(user_prompt)

        if action_status == "INITIAL" and red_reason in ["destructive", "unauthorized"]:
            return SafetyService.build_hard_stop_plan(user_prompt, red_reason)

        screen_state = list(screen_state or [])
        if vision_elements:
            screen_state.extend(node for node in vision_elements if node not in screen_state)
        clickable_present = any(
            isinstance(node, dict)
            and (node.get("is_clickable") or node.get("isClickable") or node.get("clickable") or node.get("tap_x") is not None)
            for node in screen_state
        )
        vision_fallback_plan: Optional[ActionPlanResponse] = None
        needs_vision = self._needs_vision_fallback(
            screen_state, user_prompt, action_status, target_action, action_history
        )
        if screenshot_base64 and (not screen_state or not clickable_present or needs_vision):
            vision_payload = await vision_client.analyze(
                screenshot_base64,
                task_id=task_id,
                query_text=user_prompt,
            )
            vision_nodes = vision_client.to_screen_state(vision_payload)
            if vision_nodes:
                print(f"LLM Planner: enriched DOM with {len(vision_nodes)} Service 03 coordinates.")
                screen_state.extend(node for node in vision_nodes if node not in screen_state)
                if needs_vision:
                    vision_fallback_plan = self._build_vision_coordinate_plan(
                        user_prompt, risk_level, vision_nodes, target_action, action_history
                    )

        def finalize(plan: ActionPlanResponse) -> ActionPlanResponse:
            self._enforce_post_action_modal_protection(
                plan, action_history, target_action, action_status
            )
            self._enforce_blacklisted_target_fallback(plan, screen_state, blacklisted_targets)
            intercepted_plan, _ = SafetyService.intercept_plan(plan, user_prompt)
            self._attach_vision_coordinates(intercepted_plan, screen_state)
            if screen_state and any(node.get("source") == "vision" for node in screen_state):
                intercepted_plan.approach = intercepted_plan.approach or "VISION_FALLBACK"
            return intercepted_plan

        if vision_fallback_plan:
            return finalize(vision_fallback_plan)

        requires_bio = SafetyService.requires_biometric_check(risk_level)
        app_launch = self._resolve_app_launch(user_prompt)
        action_history = action_history or []
        app_opened = self._app_was_opened(action_status, target_action, action_history)
        session_key = self._session_state_key(user_id, task_id)

        pending_install = self._pending_install_intents.get(session_key)
        if pending_install and self._is_install_confirmation(user_prompt):
            self._pending_install_intents.pop(session_key, None)
            return finalize(self._build_play_store_install_plan(user_prompt, pending_install, risk_level))

        searched_app = self._app_drawer_searches.get(session_key)
        if searched_app and action_status.upper() == "SUCCESS" and (target_action or "").upper() == ActionType.TYPE_TEXT.value:
            if not _classify_ui_topology(screen_state)["LIST_ITEM_ZONE"]:
                self._app_drawer_searches.pop(session_key, None)
                self._pending_install_intents[session_key] = searched_app
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                    total_steps=1, current_step_index=0, is_completed=True,
                    speech_response=(f"The app {searched_app} is not installed on your phone. "
                                     "Would you like me to install it from the Play Store?"),
                    steps=[PlanStep(step_id=1, action=ActionType.FINISH, target_node=None,
                                    target_text="App not installed", tier=WaterfallTier.TIER_1_INTENT,
                                    requires_biometric=False,
                                    reason="App Drawer search completed without a matching application result.")],
                ))
        if searched_app and action_status.upper() == "SUCCESS" and (target_action or "").upper() == ActionType.CLICK.value:
            self._app_drawer_searches.pop(session_key, None)

        app_drawer_recovery = self._handle_app_drawer_recovery(
            user_prompt=user_prompt,
            screen_state=screen_state,
            risk_level=risk_level,
            action_status=action_status,
            target_action=target_action,
            current_app_package=current_app_package,
            session_key=session_key,
            action_history=action_history,
        )
        if app_drawer_recovery:
            return finalize(app_drawer_recovery)

        if action_status == "INITIAL" and app_launch and not self._is_target_app_active(current_app_package, app_launch):
            fallback_plan = self._get_fallback_mock_plan(user_prompt, risk_level, requires_bio, app_launch)
            return finalize(fallback_plan)

        if self.use_llm_mock:
            return finalize(self._get_llm_mock_plan(user_prompt, risk_level))

        is_open_only_intent = bool(re.match(r"^(?:open|launch)\s+[a-zA-Z0-9\s]+$", user_prompt.strip(), re.IGNORECASE))
        if is_open_only_intent and self._is_target_app_active(current_app_package, app_launch) and action_status == "SUCCESS":
            return finalize(ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                user_goal=user_prompt,
                risk_level=risk_level,
                total_steps=1,
                current_step_index=0,
                is_completed=True,
                speech_response=f"Opened {current_app_package or 'application'}.",
                steps=[
                    PlanStep(
                        step_id=1,
                        action=ActionType.FINISH,
                        target_node=None,
                        target_text="App opened successfully",
                        tier=WaterfallTier.TIER_1_INTENT,
                        reason="Application is active in foreground; task completed."
                    )
                ]
            ))

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

        post_contact_plan = self._handle_post_contact_selection(
            user_prompt, screen_state, risk_level, action_history, action_status
        )
        if post_contact_plan:
            return finalize(post_contact_plan)

        state_progression_plan = self._progress_ui_state(
            user_prompt, screen_state, risk_level, action_history, action_status, target_action
        )
        if state_progression_plan:
            return finalize(state_progression_plan)

        overlay_plan = self._dismiss_overlay_if_present(
            screen_state, user_prompt, risk_level, action_history, target_action, action_status
        )
        if overlay_plan:
            return finalize(overlay_plan)

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

        filtered_screen_state = self._filter_screen_state_for_blacklisted_targets(screen_state, blacklisted_targets)
        screen_context = compress_screen_state(filtered_screen_state)
        screen_context["ui_topology"] = _classify_ui_topology(filtered_screen_state)
        screen_context["last_action"] = action_history[-1] if action_history else {}
        if active_input_focused:
            screen_context["active_input_focused"] = True
        if focused_hint and not screen_context.get("focused_hint"):
            screen_context["focused_hint"] = focused_hint

        local_plan = self.resolve_local_action(
            user_prompt, screen_state, risk_level, active_input_focused, focused_hint, session_language, blacklisted_targets
        )
        if local_plan:
            return finalize(local_plan)

        if not self.client or not getattr(settings, "OPENAI_API_KEY", None):
            plan = self._get_fallback_mock_plan(user_prompt, risk_level, requires_bio, app_launch)
            if app_opened or self._is_target_app_active(current_app_package, app_launch):
                self._replace_repeated_open_with_in_app_action(plan, user_prompt, screen_state, action_status, target_action, action_history)
            self._prioritize_focused_input(plan, user_prompt, screen_context, action_status, target_action)
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

            if action_status == "INITIAL" and app_launch:
                if not self._is_target_app_active(current_app_package, app_launch):
                    self._enforce_app_launch_first_step(plan, app_launch)

            if app_opened or self._is_target_app_active(current_app_package, app_launch):
                self._replace_repeated_open_with_in_app_action(plan, user_prompt, screen_state, action_status, target_action, action_history)

            self._prioritize_focused_input(plan, user_prompt, screen_context, action_status, target_action)

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
                self._replace_repeated_open_with_in_app_action(plan, user_prompt, screen_state, action_status, target_action, action_history)
            self._prioritize_focused_input(plan, user_prompt, screen_context, action_status, target_action)
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

        launchers = ("launcher", "home", "quickstep", "nexus", "trebuchet", "miui", "sec.android.app.launcher")
        if any(launcher in curr for launcher in launchers):
            return False

        if not app_launch:
            return False

        package_name, fallback_package_name, app_label = app_launch

        if package_name and (package_name.lower() in curr or curr in package_name.lower()):
            return True
        if fallback_package_name and (fallback_package_name.lower() in curr or curr in fallback_package_name.lower()):
            return True
        if app_label and app_label.lower() in curr:
            return True

        return False

    def _replace_repeated_open_with_in_app_action(
        self,
        plan: ActionPlanResponse,
        user_prompt: str,
        screen_state: List[Dict[str, Any]],
        action_status: str = "INITIAL",
        last_attempted_action: Optional[str] = None,
        action_history: Optional[List[Dict[str, Any]]] = None,
    ) -> None:
        if not plan.steps or plan.steps[plan.current_step_index].action != ActionType.OPEN_APP:
            return

        def is_clear_or_cancel_node(node: Dict[str, Any]) -> bool:
            text_val = self._node_target_label(node).lower().strip()
            if not text_val:
                return False
            clear_keywords = ("close", "clear query", "clear", "back", "navigate up", "cancel", "dismiss")
            if any(kw == text_val or kw in text_val for kw in clear_keywords):
                return True
            if text_val in ("x", "×"):
                return True
            return False

        def is_ad_banner_node(node: Dict[str, Any]) -> bool:
            text_val = self._node_target_label(node)
            return len(text_val) > 30

        last_action_entry = action_history[-1] if action_history else {}
        last_act = str(
            last_action_entry.get("action_type")
            or last_action_entry.get("action")
            or (last_attempted_action.value if hasattr(last_attempted_action, "value") else (last_attempted_action or ""))
        ).upper()
        last_act_status = str(last_action_entry.get("status") or action_status or "").upper()
        is_prev_type_text_success = (last_act == ActionType.TYPE_TEXT.value and last_act_status == "SUCCESS")

        topology = _classify_ui_topology(screen_state)
        raw_clickable = [
            node for node in (
                topology["ACTION_SUBMIT_ZONE"] + topology["LIST_ITEM_ZONE"] + topology["HEADER_NAV_ZONE"]
            )
            if _node_is_clickable(node)
            and not ("keyboard" in self._node_target_label(node).lower())
            and len(self._node_target_label(node)) > 0
            and not is_ad_banner_node(node)
        ]

        if is_prev_type_text_success:
            clickable_nodes = [
                node for node in raw_clickable
                if not is_clear_or_cancel_node(node) and node not in topology["HEADER_NAV_ZONE"]
            ]
        else:
            clickable_nodes = raw_clickable

        searchable_nodes = [node for node in topology["INPUT_ZONE"] if "search" in self._node_target_label(node).lower()]
        submission_nodes = [
            node for node in topology["ACTION_SUBMIT_ZONE"]
            if node in clickable_nodes
            and not (node in topology["INPUT_ZONE"] and _node_is_editable(node))
            and self._node_target_label(node).lower().strip() not in {"song", "songs", "filter", "filters", "all"}
            and not self._is_dismissive_label(self._node_target_label(node))
        ]
        focused_editable = [
            node for node in topology["INPUT_ZONE"]
            if _node_is_editable(node)
            and (node.get("is_focused") or node.get("isFocused") or node.get("focused"))
        ]
        editable_nodes = [node for node in topology["INPUT_ZONE"] if _node_is_editable(node)]
        old_step = plan.steps[plan.current_step_index]

        # FIX 2: Strict target label verification before setting CLICK action
        if is_prev_type_text_success and submission_nodes:
            valid_submit = next((node for node in submission_nodes if self._node_target_label(node)), None)
            if valid_submit:
                target = self._node_target_label(valid_submit)
                plan.steps[plan.current_step_index] = PlanStep(
                    step_id=old_step.step_id,
                    action=ActionType.CLICK,
                    target_node=target,
                    target_text=target,
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    reason="Post-type_text: Tapping safe search submission control.",
                )
            else:
                plan.steps[plan.current_step_index] = PlanStep(
                    step_id=old_step.step_id,
                    action=ActionType.SCROLL,
                    target_text="rescan_wait",
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    reason="Post-type_text: Submit node label empty; waiting for rescan.",
                )
        elif is_prev_type_text_success and topology["LIST_ITEM_ZONE"]:
            safe_results = [
                node for node in topology["LIST_ITEM_ZONE"] 
                if node in clickable_nodes and self._node_target_label(node)
            ]
            if safe_results:
                target = self._node_target_label(safe_results[0])
                plan.steps[plan.current_step_index] = PlanStep(
                    step_id=old_step.step_id,
                    action=ActionType.CLICK,
                    target_node=target,
                    target_text=target,
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    reason="Post-type_text: Selecting a valid list-item result.",
                )
            else:
                plan.steps[plan.current_step_index] = PlanStep(
                    step_id=old_step.step_id,
                    action=ActionType.SCROLL,
                    target_node=None,
                    target_text="rescan_wait",
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    reason="Post-type_text: no safe submit or result control is visible.",
                )
        elif is_prev_type_text_success:
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.SCROLL,
                target_node=None,
                target_text="rescan_wait",
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="Post-type_text: waiting for a safe submit or result control.",
            )
        elif clickable_nodes:
            valid_clickable = next((node for node in clickable_nodes if self._node_target_label(node)), None)
            if valid_clickable:
                target = self._node_target_label(valid_clickable)
                plan.steps[plan.current_step_index] = PlanStep(
                    step_id=old_step.step_id,
                    action=ActionType.CLICK,
                    target_node=target,
                    target_text=target,
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    reason="App is active; continuing with an interactive element.",
                )
        elif not is_prev_type_text_success and focused_editable:
            target = self._node_target_label(focused_editable[0]) or "focused_input"
            extracted_val = _extract_clean_entity(user_prompt, target)
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.TYPE_TEXT,
                target_node=target,
                target_text=target,
                input_data=extracted_val,
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is active; typing target query into focused field.",
            )
        elif searchable_nodes:
            target = self._node_target_label(searchable_nodes[0]) or "search_input"
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.CLICK,
                target_node=target,
                target_text=target,
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is active; tapping visible search control.",
            )
        elif not is_prev_type_text_success and editable_nodes:
            target = self._node_target_label(editable_nodes[0]) or "editable_input"
            extracted_val = _extract_clean_entity(user_prompt, target)
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=old_step.step_id,
                action=ActionType.TYPE_TEXT,
                target_node=target,
                target_text=target,
                input_data=extracted_val,
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="App is active; entering target query into input field.",
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
        action_status: str = "INITIAL",
        last_attempted_action: Optional[str] = None,
    ) -> None:
        last_action_str = str(
            last_attempted_action.value if hasattr(last_attempted_action, "value") else (last_attempted_action or "")
        ).upper()

        topology = screen_context.get("ui_topology") or {}
        input_zone = topology.get("INPUT_ZONE", [])
        submit_zone = topology.get("ACTION_SUBMIT_ZONE", [])
        list_zone = topology.get("LIST_ITEM_ZONE", [])
        header_zone = topology.get("HEADER_NAV_ZONE", [])
        system_zone = topology.get("SYSTEM_CONTAINER_ZONE", [])

        if last_action_str == ActionType.TYPE_TEXT.value and action_status.upper() == "SUCCESS":
            last_action = screen_context.get("last_action") or {}
            target_entity = sanitize_text(
                last_action.get("input_data") or last_action.get("value") or last_action.get("text_to_type")
            ) or _extract_intent_payloads(user_prompt, "TARGET_ENTITY")
            target_entity_lower = target_entity.lower()
            excluded_labels = {
                "close", "cancel", "clear", "clear query", "dismiss", "back", "x", "×",
                "song", "songs", "filter", "filters", "all", "videos", "shorts",
            }

            def is_post_type_excluded(node: Dict[str, Any]) -> bool:
                label = self._node_target_label(node).lower().strip()
                return (
                    (node in input_zone and _node_is_editable(node)) or label in excluded_labels or self._is_dismissive_label(label)
                    or "search here" in label or "search youtube" in label
                )

            matching_suggestion = next((
                node for node in list_zone
                if not is_post_type_excluded(node) and target_entity_lower
                and self._node_target_label(node)
                and (target_entity_lower in self._node_target_label(node).lower()
                     or self._node_target_label(node).lower() in target_entity_lower)
            ), None)
            safe_submit = next((
                node for node in submit_zone
                if node not in header_zone and not is_post_type_excluded(node)
                and self._node_target_label(node)
                and any(token in self._node_target_label(node).lower() for token in (
                    "search", "submit", "magnifier", "ime", "enter", "go", "send", "check", "done"
                ))
            ), None)
            current_step = plan.steps[plan.current_step_index] if plan.steps else None
            if current_step and matching_suggestion:
                target = self._node_target_label(matching_suggestion)
                plan.steps[plan.current_step_index] = PlanStep(
                    step_id=current_step.step_id, action=ActionType.CLICK, target_node=target, target_text=target,
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    reason="Post-type search binding: selecting the matching suggestion row.",
                )
            elif current_step and safe_submit:
                target = self._node_target_label(safe_submit)
                plan.steps[plan.current_step_index] = PlanStep(
                    step_id=current_step.step_id, action=ActionType.CLICK, target_node=target, target_text=target,
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    reason="Post-type search binding: submitting through the safe IME/search action.",
                )
            elif current_step:
                plan.steps[plan.current_step_index] = PlanStep(
                    step_id=current_step.step_id, action=ActionType.SCROLL, target_node=None, target_text="rescan_wait",
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    reason="Post-type search binding: waiting for a suggestion or submit control; input and filters are excluded.",
                )
            plan.is_completed = False
            return

        text_intent = any(
            word in user_prompt.lower()
            for word in ("search", "find", "send", "message", "type", "play", "watch", "to", "book")
        )
        if (
            not text_intent
            or not screen_context.get("active_input_focused")
            or not plan.steps
            or plan.steps[plan.current_step_index].action == ActionType.TYPE_TEXT
        ):
            return

        target_hint = sanitize_text(screen_context.get("focused_hint")) or "focused input field"
        target_lower = target_hint.lower()

        system_folder_keywords = (
            "folder", "rename", "title_input", "workspace", "launcher",
            "home screen", "folder name", "folder_name", "edit_folder"
        )
        focused_system_container = any(
            target_lower in _node_text(node).lower() or _node_text(node).lower() in target_lower
            for node in system_zone
            if _node_text(node)
        )
        if focused_system_container or any(kw in target_lower for kw in system_folder_keywords):
            current_step = plan.steps[plan.current_step_index]
            plan.steps[plan.current_step_index] = PlanStep(
                step_id=current_step.step_id,
                action=ActionType.SWIPE_UP,
                target_node="home_screen",
                target_text="Open App Drawer",
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                reason="Target is system folder/workspace label. Blocking input injection; forcing App Drawer navigation."
            )
            return

        current_step = plan.steps[plan.current_step_index]
        clean_input = _extract_clean_entity(user_prompt, field_context=target_hint)

        plan.steps[plan.current_step_index] = PlanStep(
            step_id=current_step.step_id,
            action=ActionType.TYPE_TEXT,
            target_node=target_hint,
            target_text=target_hint,
            input_data=clean_input,
            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
            reason="Focused input active; entering isolated target parameter.",
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
- UI Topology (functional zones): {json.dumps(screen_context.get('ui_topology', {}), indent=2)}
- Interactive Targets ({len(screen_context.get('interactive_targets', []))} controls):
{json.dumps(screen_context.get('interactive_targets', []), indent=2)}
- Secondary Metadata ({len(screen_context.get('secondary_metadata', []))} items):
{json.dumps(screen_context.get('secondary_metadata', [])[:15], indent=2)}

Analyze the screen state in relation to the user intent and action history. Return the next logical single action JSON object.
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
        return ActionPlanResponse(
            plan_id=f"plan_{uuid.uuid4().hex[:8]}",
            user_goal=user_prompt,
            risk_level=risk_level,
            total_steps=len(steps) or 1,
            is_completed=False,
            steps=steps or [PlanStep(step_id=1, action=ActionType.SCROLL, target_text="rescan_wait")],
            speech_response=f"Executing plan for {user_prompt}"
        )

    def _resolve_app_launch(self, user_prompt: str):
        normalized_prompt = user_prompt.lower()

        for app_name in sorted(self.APP_PACKAGE_HINTS, key=len, reverse=True):
            if re.search(rf"\b{re.escape(app_name)}\b", normalized_prompt):
                return self.APP_PACKAGE_HINTS[app_name]

        match = re.search(r"\b(?:open|launch|set|create|send|play|watch|find|on|in|using)\s+([a-zA-Z0-9\s]+)", user_prompt, re.IGNORECASE)
        if match:
            raw_target = match.group(1).strip()
            target_clause = re.split(r"\b(?:and|to|for|with|on|in|then|search|play|watch|find|buy|send)\b", raw_target, flags=re.IGNORECASE)[0].strip()

            if target_clause:
                normalized_clause = target_clause.lower()
                for app_name in sorted(self.APP_PACKAGE_HINTS, key=len, reverse=True):
                    if re.search(rf"\b{re.escape(app_name)}\b", normalized_clause):
                        return self.APP_PACKAGE_HINTS[app_name]

                extracted_label = " ".join(word.capitalize() for word in target_clause.split())
                return (None, None, extracted_label)

        return None

    def _extract_app_name_for_search(self, user_prompt: str) -> str:
        app_launch = self._resolve_app_launch(user_prompt)
        if app_launch and app_launch[2]:
            return app_launch[2]
        clean = re.sub(
            r"^(?:open|launch|search|find|play|watch|book|set|create|send)\s+(?:for\s+)?",
            "",
            user_prompt.strip(),
            flags=re.IGNORECASE,
        ).strip()
        clean = re.split(r"\b(?:and|to|for|with|on|in|then)\b", clean, flags=re.IGNORECASE)[0].strip()
        return " ".join(word.capitalize() for word in clean.split()) if clean else user_prompt

    @staticmethod
    def _find_search_bar_element(screen_state: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        return next((
            node for node in _classify_ui_topology(screen_state)["INPUT_ZONE"]
            if _node_is_editable(node) or any(token in _node_text(node).lower() for token in ("search", "find", "edittext"))
        ), None)

    def _find_search_bar_target(self, screen_state: List[Dict[str, Any]]) -> Optional[str]:
        search_bar = self._find_search_bar_element(screen_state)
        if search_bar:
            return next(
                (sanitize_text(search_bar.get(f)) for f in ("text", "content_description", "view_id") if sanitize_text(search_bar.get(f))),
                None,
            )
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
                    sanitize_text(node.get(field))
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
            step.target_node = sanitize_text(matched.get("view_id") or matched.get("text")) or step.target_text

    def _update_and_get_blacklisted_targets(
        self,
        task_id: Optional[str],
        action_status: str,
        target_action: Optional[str],
        action_history: Optional[List[Dict[str, Any]]],
    ) -> set[str]:
        blacklisted = set()
        tid = task_id or "global_default"
        if tid not in self._task_failure_counts:
            self._task_failure_counts[tid] = {}
        if tid not in self._bottom_sheet_blacklists:
            self._bottom_sheet_blacklists[tid] = set()

        bottom_sheet_keywords = ("bottom sheet", "address", "location sheet")
        bottom_sheet_clicks: Dict[str, int] = {}
        for entry in action_history or []:
            action = str(entry.get("action_type", entry.get("action", ""))).upper()
            label = sanitize_text(
                entry.get("target_node") or entry.get("target_text") or entry.get("view_id")
            ).lower()
            if action == ActionType.CLICK.value and label and any(keyword in label for keyword in bottom_sheet_keywords):
                bottom_sheet_clicks[label] = bottom_sheet_clicks.get(label, 0) + 1
        for label, click_count in bottom_sheet_clicks.items():
            if click_count >= 2:
                self._bottom_sheet_blacklists[tid].add(label)
        blacklisted.update(self._bottom_sheet_blacklists[tid])

        task_failures = self._task_failure_counts[tid]

        if action_history:
            temp_counts: Dict[str, int] = {}
            for entry in action_history:
                act = str(entry.get("action_type", entry.get("action", ""))).upper()
                node = sanitize_text(entry.get("target_node") or entry.get("target_text") or entry.get("package_name")).lower()
                status = str(entry.get("status", "")).upper()
                if not node:
                    continue
                key = f"{act}:{node}"
                if status == "FAILED":
                    temp_counts[key] = temp_counts.get(key, 0) + 1
                    temp_counts[node] = temp_counts.get(node, 0) + 1
                elif status == "SUCCESS":
                    temp_counts[key] = 0
                    temp_counts[node] = 0

            for k, v in temp_counts.items():
                task_failures[k] = max(task_failures.get(k, 0), v)

        if target_action and action_status:
            current_status = action_status.upper()
            last_target = ""
            if action_history:
                last_target = sanitize_text(action_history[-1].get("target_node") or action_history[-1].get("target_text")).lower()
            if not last_target and target_action:
                last_target = sanitize_text(target_action).lower()

            if last_target:
                key = f"{target_action.upper()}:{last_target}"
                if current_status == "FAILED":
                    task_failures[key] = task_failures.get(key, 0) + 1
                    task_failures[last_target] = task_failures.get(last_target, 0) + 1
                elif current_status == "SUCCESS":
                    task_failures[key] = 0
                    task_failures[last_target] = 0

        for key, count in task_failures.items():
            if count >= 2:
                target_name = key.split(":")[-1] if ":" in key else key
                if target_name:
                    blacklisted.add(target_name)
                    blacklisted.add(key)

        return blacklisted

    @staticmethod
    def _filter_screen_state_for_blacklisted_targets(
        screen_state: List[Dict[str, Any]],
        blacklisted_targets: set[str],
    ) -> List[Dict[str, Any]]:
        if not blacklisted_targets:
            return screen_state
        filtered = []
        for node in screen_state:
            if not isinstance(node, dict):
                continue
            text = sanitize_text(node.get("text") or node.get("content_description") or node.get("view_id")).lower()
            if any(blk in text or text in blk for blk in blacklisted_targets if blk):
                continue
            filtered.append(node)
        return filtered

    def _enforce_blacklisted_target_fallback(
        self,
        plan: ActionPlanResponse,
        screen_state: List[Dict[str, Any]],
        blacklisted_targets: set[str],
    ) -> None:
        if not blacklisted_targets or not plan.steps:
            return

        topology = _classify_ui_topology(screen_state)
        system_nodes = topology["SYSTEM_CONTAINER_ZONE"]
        for index, step in enumerate(plan.steps):
            target = sanitize_text(step.target_node or step.target_text).lower()
            if not target:
                continue

            is_blacklisted = any(blk in target or target in blk for blk in blacklisted_targets if blk)
            if is_blacklisted:
                is_bottom_sheet = any(keyword in target for keyword in ("bottom sheet", "address", "location sheet"))
                if is_bottom_sheet:
                    primary_input = next((
                        node for node in topology["INPUT_ZONE"]
                        if node not in system_nodes and self._node_target_label(node)
                    ), None)
                    if primary_input:
                        input_target = self._node_target_label(primary_input)
                        plan.steps[index] = PlanStep(
                            step_id=step.step_id,
                            action=ActionType.CLICK,
                            target_node=input_target,
                            target_text=input_target,
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            requires_biometric=False,
                            reason="Bottom-sheet toggle shield: returning to the primary layout input after repeated sheet clicks.",
                        )
                    else:
                        plan.steps[index] = PlanStep(
                            step_id=step.step_id,
                            action=ActionType.SCROLL,
                            target_node=None,
                            target_text="rescan_wait",
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            requires_biometric=False,
                            reason="Bottom-sheet toggle shield: waiting for the primary layout input to return.",
                        )
                    continue
                next_node = None
                for node in screen_state:
                    if not isinstance(node, dict):
                        continue
                    if not bool(node.get("is_clickable", node.get("isClickable", node.get("clickable", False)))):
                        continue
                    node_label = self._node_target_label(node)
                    if not node_label:
                        continue
                    label_lower = node_label.lower()
                    if not any(blk in label_lower or label_lower in blk for blk in blacklisted_targets if blk):
                        next_node = node_label
                        break

                if next_node:
                    plan.steps[index] = PlanStep(
                        step_id=step.step_id,
                        action=ActionType.CLICK,
                        target_node=next_node,
                        target_text=next_node,
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason=f"Target '{target}' blacklisted after 2 consecutive failures; falling back to next available element '{next_node}'."
                    )
                else:
                    plan.steps[index] = PlanStep(
                        step_id=step.step_id,
                        action=ActionType.SCROLL,
                        target_node=None,
                        target_text="rescan_wait",
                        input_data=None,
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason=f"Target '{target}' blacklisted after 2 consecutive failures and no alternative element found; issuing scroll rescan_wait to break loop."
                    )


planner_service = PlannerService()