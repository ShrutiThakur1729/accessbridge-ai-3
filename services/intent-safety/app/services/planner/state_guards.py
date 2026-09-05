#Contains UI interceptors, overlay cleanup, and contact transition handlers.
import re
import uuid
from typing import List, Dict, Any, Optional
from app.schemas.action_plan import ActionPlanResponse, ActionType, PlanStep, RiskLevel, WaterfallTier
from app.services.planner.utils import sanitize_text
from app.services.planner.ui_topology import classify_ui_topology, node_target_label, node_text, node_is_editable
from app.services.planner.entity_extractor import extract_intent_payloads

def is_dismissive_label(label: str) -> bool:
    return sanitize_text(label).lower().strip() in {
        "close", "dismiss", "skip", "not now", "no thanks", "cancel", "maybe later", "clear", "clear query", "x", "×"
    }

def is_post_action_modal_state(
    action_history: Optional[List[Dict[str, Any]]],
    last_attempted_action: Optional[str],
    action_status: str,
) -> bool:
    last = (action_history or [])[-1] if action_history else {}
    action = str(last.get("action_type") or last.get("action") or last_attempted_action or "").upper()
    status = str(last.get("status") or action_status or "").upper()
    target = sanitize_text(last.get("target_node") or last.get("target_text")).lower()
    modal_keywords = ("add", "create", "set", "edit", "new", "picker", "address")
    return status == "SUCCESS" and action == ActionType.CLICK.value and any(word in target for word in modal_keywords)


_COMMIT_LABELS = {"save", "submit", "done", "ok", "okay", "confirm", "apply"}
_PARAMETER_PATTERN = re.compile(
    r"\b(?:a\.?m\.?|p\.?m\.?|am/pm|hour|minute|time|date|number\s*picker|date\s*picker|"
    r"time\s*picker|toggle|switch|checkbox|slider|\d{1,2}(?::\d{2})?)\b",
    re.IGNORECASE,
)


def _node_is_interactive(node: Dict[str, Any]) -> bool:
    return bool(
        node.get("is_clickable") or node.get("clickable") or node.get("isClickable")
        or node.get("is_checkable") or node.get("checkable") or node.get("isCheckable")
        or node.get("is_scrollable") or node.get("scrollable") or node.get("isScrollable")
        or node_is_editable(node)
    )


def _is_commit_control(node: Dict[str, Any]) -> bool:
    if not isinstance(node, dict) or not _node_is_interactive(node):
        return False
    text_fields = (
        sanitize_text(node.get("text")),
        sanitize_text(node.get("content_description") or node.get("contentDescription")),
        sanitize_text(node.get("view_id") or node.get("viewId")),
        sanitize_text(node.get("resource_id") or node.get("resourceId")),
    )
    for txt in text_fields:
        if not txt:
            continue
        txt_lower = txt.lower().strip()
        if txt_lower in _COMMIT_LABELS:
            return True
        tokens = set(re.findall(r"\w+", txt_lower))
        if tokens & _COMMIT_LABELS:
            return True
    return False


def _has_parameter_signal(value: str) -> bool:
    return bool(_PARAMETER_PATTERN.search(value))


def _visible_parameter_controls(screen_state: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Return interactive picker/form parameter controls, never static labels or commit controls."""
    return [
        node for node in screen_state
        if isinstance(node, dict)
        and _node_is_interactive(node)
        and not _is_commit_control(node)
        and _has_parameter_signal(node_text(node))
    ]


def _parameter_was_targeted(
    controls: List[Dict[str, Any]], action_history: Optional[List[Dict[str, Any]]]
) -> bool:
    labels = {node_target_label(node).lower() for node in controls if node_target_label(node)}
    for action in action_history or []:
        if str(action.get("status") or "").upper() != "SUCCESS":
            continue
        target = sanitize_text(action.get("target_node") or action.get("target_text")).lower()
        if target and (target in labels or _has_parameter_signal(target)):
            return True
    return False


def prioritize_unadjusted_parameter_control(
    screen_state: List[Dict[str, Any]],
    user_prompt: str,
    risk_level: RiskLevel,
    context_mode: str = "DEFAULT",
    action_history: Optional[List[Dict[str, Any]]] = None,
) -> Optional[ActionPlanResponse]:
    """On a modal's first turn, adjust a visible parameter before its commit action."""
    commit_visible = any(_is_commit_control(node) for node in screen_state if isinstance(node, dict))
    controls = _visible_parameter_controls(screen_state)
    if not commit_visible or not controls or _parameter_was_targeted(controls, action_history):
        return None

    target = node_target_label(controls[0])
    if not target:
        return None
    return ActionPlanResponse(
        plan_id=f"plan_{uuid.uuid4().hex[:8]}",
        user_goal=user_prompt,
        risk_level=risk_level,
        total_steps=1,
        current_step_index=0,
        is_completed=False,
        speech_response="Adjusting the visible parameter before confirming.",
        steps=[PlanStep(
            step_id=1,
            action=ActionType.CLICK,
            target_node=target,
            target_text=target,
            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
            reason="Parameter fulfillment guard: adjust visible picker/form values before commit.",
        )],
    )

def dismiss_overlay_if_present(
    screen_state: List[Dict[str, Any]],
    user_prompt: str,
    risk_level: RiskLevel,
    action_history: Optional[List[Dict[str, Any]]] = None,
    last_attempted_action: Optional[str] = None,
    action_status: str = "INITIAL",
    context_mode: str = "DEFAULT",
) -> Optional[ActionPlanResponse]:
    if str(context_mode).upper() in {"IN_DIALOG_PICKER", "FORM_FILLING"}:
        return None

    last_action = action_history[-1] if action_history else {}
    last_act_type = str(last_action.get("action_type") or last_action.get("action") or last_attempted_action or "").upper()
    last_act_status = str(last_action.get("status") or action_status or "").upper()

    if last_act_type == ActionType.TYPE_TEXT.value and last_act_status == "SUCCESS":
        return None

    if is_post_action_modal_state(action_history, last_attempted_action, action_status):
        return None

    dismiss_keywords = {"close", "dismiss", "skip", "not now", "no thanks", "cancel", "maybe later"}
    for node in screen_state:
        if not isinstance(node, dict) or not (node.get("is_clickable") or node.get("clickable") or node.get("isClickable")):
            continue
        label = sanitize_text(node.get("text") or node.get("content_description") or node.get("view_id"))
        if label.lower() in dismiss_keywords or label.lower() in ("x", "×"):
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                user_goal=user_prompt,
                risk_level=risk_level,
                total_steps=1,
                current_step_index=0,
                is_completed=False,
                speech_response="Dismissing overlay control.",
                steps=[PlanStep(step_id=1, action=ActionType.CLICK, target_node=label, target_text=label,
                                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                                reason="Universal pop-up/dialog interceptor: clearing screen overlay.")]
            )
    return None

def handle_post_contact_selection(
    user_prompt: str,
    screen_state: List[Dict[str, Any]],
    risk_level: RiskLevel,
    action_history: Optional[List[Dict[str, Any]]] = None,
    action_status: str = "INITIAL"
) -> Optional[ActionPlanResponse]:
    if not action_history or action_status.upper() != "SUCCESS":
        return None

    last_action = action_history[-1]
    if str(last_action.get("action_type") or last_action.get("action") or "").upper() != ActionType.CLICK.value:
        return None

    topology = classify_ui_topology(screen_state)
    chat_input_node = next((
        node for node in topology["INPUT_ZONE"]
        if node_is_editable(node) and "search" not in node_text(node).lower()
        and any(keyword in node_text(node).lower() for keyword in (
            "type a message", "message", "write a reply", "chat_input", "type message"
        ))
    ), None)

    if chat_input_node:
        target_label = node_target_label(chat_input_node) or "Type a message"
        message_payload = extract_intent_payloads(user_prompt, "MESSAGE_PAYLOAD")
        return ActionPlanResponse(
            plan_id=f"plan_{uuid.uuid4().hex[:8]}",
            user_goal=user_prompt,
            risk_level=risk_level,
            total_steps=1,
            current_step_index=0,
            is_completed=False,
            speech_response="Contact selected. Typing message payload.",
            steps=[PlanStep(step_id=1, action=ActionType.TYPE_TEXT, target_node=target_label,
                            target_text=target_label, input_data=message_payload,
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            reason="Post-contact selection state shift: Direct messaging active chat field.")]
        )
    return None
