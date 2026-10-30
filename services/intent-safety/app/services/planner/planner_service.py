# The orchestrator
import re
import logging
import uuid
from typing import List, Dict, Any, Optional
from dataclasses import dataclass, field

from app.schemas.action_plan import ActionPlanResponse, ActionType, PlanStep, RiskLevel, WaterfallTier
from app.services.safety_service import SafetyService
from app.services.vision_client import vision_client
from app.utils.screen_compressor import compress_screen_state

from app.services.planner.utils import sanitize_text
from app.services.planner.ui_topology import (
    classify_ui_topology, detect_context_mode, node_target_label, node_text, node_is_editable, node_is_clickable,
    find_best_app_match, group_spatial_nodes
)
from app.services.planner.entity_extractor import extract_clean_entity, extract_intent_payloads, normalize_app_query
from app.services.planner.state_guards import (
    dismiss_overlay_if_present, handle_post_contact_selection, prioritize_unadjusted_parameter_control
)
from app.services.planner.app_drawer_handler import (
    resolve_app_launch, handle_app_drawer_recovery, extract_app_name_for_search, looks_like_launcher_or_app_drawer,
    is_app_foreground, is_compound_or_multi_step_intent
)
from app.services.planner.llm_client import LLMPlannerClient
from app.services.planner.localization import get_localized_message
from app.services.conversation_state import conversation_state_manager, PlannerExecutionState

logger = logging.getLogger("intent-safety.planner")


@dataclass
class SessionContext:
    user_id: str
    session_id: str
    active_package: Optional[str] = None
    primary_goal: Optional[str] = None
    pending_state: Optional[str] = None
    pending_target: Optional[str] = None
    conversation_history: List[Dict[str, str]] = field(default_factory=list)

    def add_turn(self, role: str, text: str, max_history: int = 10) -> None:
        if text and text.strip():
            self.conversation_history.append({"role": role, "text": text.strip()})
            if len(self.conversation_history) > max_history:
                self.conversation_history = self.conversation_history[-max_history:]


def classify_intent_metadata(user_prompt: str) -> Dict[str, Any]:
    prompt_raw = user_prompt.strip().lower()

    if prompt_raw in {"open", "launch", "start", "go to", "turn on", "show"}:
        return {
            "primary_intent": "AMBIGUOUS_LAUNCH",
            "target_app": None,
            "has_subsequent_actions": False,
        }

    app_info = resolve_app_launch(user_prompt)
    if not app_info:
        return {
            "primary_intent": "GENERAL_ACTION",
            "target_app": None,
            "has_subsequent_actions": True,
        }

    pkg, fallback_pkg, app_name = app_info

    pure_launch_match = re.match(
        rf"^(?:open|launch|go\s+to|start|turn\s+on|show)\s+{re.escape(app_name.lower())}(?:\s+(?:app|application))?$",
        prompt_raw
    )
    if pure_launch_match or prompt_raw in {app_name.lower(), f"{app_name.lower()} app", f"{app_name.lower()} application"}:
        return {
            "primary_intent": "LAUNCH_APP",
            "target_app": app_name,
            "has_subsequent_actions": False,
        }

    return {
        "primary_intent": "LAUNCH_APP",
        "target_app": app_name,
        "has_subsequent_actions": True,
    }


class PlannerService:
    def __init__(self):
        self.llm_client = LLMPlannerClient()
        self._task_failure_counts: Dict[str, Dict[str, int]] = {}
        self._app_drawer_searches: Dict[str, str] = {}
        self._pending_install_intents: Dict[str, str] = {}
        self._pending_app_confirmations: Dict[str, str] = {}
        self._bottom_sheet_blacklists: Dict[str, set[str]] = {}
        self._session_states: Dict[str, str] = {}
        self._session_contexts: Dict[str, SessionContext] = {}

    def _get_or_create_session(self, user_id: str, session_id: str) -> SessionContext:
        clean_user = sanitize_text(user_id) or "anonymous"
        clean_session = sanitize_text(session_id) or "default_session"
        key = f"{clean_user}:{clean_session}"

        stored_session = conversation_state_manager.get(key)
        if stored_session and stored_session.session_context:
            self._session_contexts[key] = stored_session.session_context

        if key not in self._session_contexts:
            self._session_contexts[key] = SessionContext(user_id=clean_user, session_id=clean_session)

        ctx = self._session_contexts[key]
        conversation_state_manager.transition(
            session_id=key,
            state=PlannerExecutionState.PROCESSING_DOM,
            session_context=ctx
        )
        return ctx

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
        session_language: str = "en",
        screenshot_base64: Optional[str] = None,
        vision_elements: Optional[List[Dict[str, Any]]] = None,
        blacklist_memory: Optional[List[str]] = None,
        session_id: Optional[str] = None,
    ) -> ActionPlanResponse:
        risk_level = SafetyService.evaluate_risk(user_prompt)
        screen_state = list(screen_state or [])

        # Determine response language per turn
        def resolve_turn_language(prompt: str, saved_lang: str) -> str:
            if re.search(r'[\u0900-\u097F]', prompt):
                if re.search(r'\b(?:उघड|कर|पाठव|आहे|नाही|मराठी)\b', prompt, re.IGNORECASE):
                    return "mr"
                return "hi"
            saved_code = (saved_lang or "en").lower().split("-")[0].split("_")[0]
            if saved_code in {"hi", "mr", "en"}:
                return saved_code
            return "en"

        resolved_lang = resolve_turn_language(user_prompt, session_language)

        effective_session_id = session_id or task_id or "default_session"
        session_key = f"{sanitize_text(user_id) or 'anonymous'}:{sanitize_text(effective_session_id)}"

        session_ctx = self._get_or_create_session(user_id or "anonymous", effective_session_id)
        session_ctx.add_turn("user", user_prompt)
        if current_app_package:
            session_ctx.active_package = current_app_package

        affirmative_regex = r"\b(?:yes|sure|install|open|ok|okay|yeah|yep|do it|please|go ahead)\b"
        
        is_fresh_utterance = str(action_status).upper() == "INITIAL"
        print(f"[planner] status={action_status} fresh={is_fresh_utterance} "
            f"pending_install={self._pending_install_intents.get(session_key)!r} "
            f"drawer_state={self._session_states.get(session_key)!r}")

        pending_ambiguous_app = self._pending_app_confirmations.get(session_key) if is_fresh_utterance else None

        pending_install_app = self._pending_install_intents.get(session_key) if is_fresh_utterance else None

        def attach_session_metadata(plan: ActionPlanResponse, path_name: str) -> ActionPlanResponse:
            effective_response_lang = plan.response_language or resolved_lang
            logger.info("PLANNER_PATH=%s task_id=%s response_lang=%s", path_name, task_id or "N/A", effective_response_lang)
            if plan.speech_response:
                session_ctx.add_turn("assistant", plan.speech_response)
            return plan.model_copy(update={
                "session_id": session_ctx.session_id,
                "response_language": effective_response_lang,
                "conversation_history": list(session_ctx.conversation_history)
            })

        # 0A. Pending Ambiguous App Confirmation Flow
        
        if pending_ambiguous_app:
            if re.search(affirmative_regex, user_prompt, re.IGNORECASE):
                candidate_app = self._pending_app_confirmations.pop(session_key, None) or pending_ambiguous_app
                speech = get_localized_message("OPENING_APP", resolved_lang, app=candidate_app)
                return attach_session_metadata(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    session_id=session_ctx.session_id,
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    current_step_index=0,
                    is_completed=False,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.CLICK,
                        target_node=candidate_app,
                        target_text=candidate_app,
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason=f"Ambiguous app confirmation: opening confirmed app '{candidate_app}'.",
                    )],
                ), "guard_ambiguous_app_confirm")
            else:
                self._pending_app_confirmations.pop(session_key, None)
                speech = get_localized_message("CANCELLED_APP_LAUNCH", resolved_lang)
                return attach_session_metadata(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    session_id=session_ctx.session_id,
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    current_step_index=0,
                    is_completed=True,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.FINISH,
                        reason="User cancelled opening ambiguous candidate app.",
                    )],
                ), "guard_ambiguous_app_cancel")

        # 0B. Pending Play Store Install Confirmation Flow
        
        if pending_install_app:
            if re.search(affirmative_regex, user_prompt, re.IGNORECASE):
                app_name = self._pending_install_intents.pop(session_key, None) or pending_install_app
                speech = get_localized_message("INSTALLING_APP", resolved_lang, app=app_name)
                return attach_session_metadata(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    session_id=session_ctx.session_id,
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=3,
                    current_step_index=0,
                    is_completed=False,
                    speech_response=speech,
                    steps=[
                        PlanStep(
                            step_id=1,
                            action=ActionType.OPEN_APP,
                            target_node="com.android.vending",
                            target_text="Google Play Store",
                            package_name="com.android.vending",
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            reason="Play Store installation: opening Google Play Store.",
                        ),
                        PlanStep(
                            step_id=2,
                            action=ActionType.TYPE_TEXT,
                            target_node="Search Google Play",
                            target_text="Search Google Play",
                            input_data=f"Search apps for {app_name}",
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            reason=f"Play Store installation: typing search query for {app_name}.",
                        ),
                        PlanStep(
                            step_id=3,
                            action=ActionType.CLICK,
                            target_node="Install",
                            target_text="Install",
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            reason=f"Play Store installation: clicking Install for {app_name}.",
                        ),
                    ],
                ), "guard_install_app_confirm")
            else:
                self._pending_install_intents.pop(session_key, None)
                speech = get_localized_message("CANCELLED_INSTALL", resolved_lang)
                return attach_session_metadata(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    session_id=session_ctx.session_id,
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    current_step_index=0,
                    is_completed=True,
                    speech_response=speech,
                    steps=[
                        PlanStep(
                            step_id=1,
                            action=ActionType.FINISH,
                            reason="User cancelled Play Store installation.",
                        )
                    ],
                ), "guard_install_app_cancel")

        # APP LAUNCH FAILURE & PLAY STORE INSTALL INTERCEPTOR
        if str(action_status).upper() == "FAILED" and (
            target_action == "OPEN_APP"
            or (action_history and str(action_history[-1].get("action_type") or "").upper() == "OPEN_APP")
        ):
            app_info = resolve_app_launch(user_prompt)
            app_name = app_info[2] if (app_info and app_info[2]) else (target_action or "the requested app")

            self._pending_install_intents[session_key] = app_name
            speech = get_localized_message("APP_NOT_INSTALLED", resolved_lang, app=app_name)

            return attach_session_metadata(ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                session_id=session_ctx.session_id,
                user_goal=user_prompt,
                risk_level=risk_level,
                total_steps=1,
                current_step_index=0,
                is_completed=False,
                execution_state="AWAITING_USER_CONFIRMATION",
                requires_confirmation=True,
                speech_response=speech,
                steps=[PlanStep(
                    step_id=1,
                    action=ActionType.FINISH,
                    reason="OPEN_APP failed: captured app installation intent and awaiting user confirmation.",
                )],
            ), "guard_app_launch_failed")

        # 0C. Universal Screen QA Interceptor
        qa_pattern = r"\b(?:what|which|how\s+much|tell\s+me|read\s+screen|summarize|options|cheapest|show\s+options)\b"
        action_verb_pattern = r"\b(?:open|launch|click|type|send|install|call|message|play|search|go\s+to|turn\s+on|show|whatsapp)\b"
        prompt_lower = user_prompt.lower().strip()

        app_info_resolved = resolve_app_launch(user_prompt)
        has_app_match = bool(app_info_resolved and app_info_resolved[0])
        has_action_verb = bool(re.search(action_verb_pattern, prompt_lower))
        is_qa = bool(re.search(qa_pattern, prompt_lower)) and not has_action_verb and not has_app_match
        if is_qa:
            grouped = group_spatial_nodes(screen_state)
            if grouped:
                labels = [g.get("combined_label") for g in grouped if g.get("combined_label")]
                summary = "; ".join(labels[:5])
                speech = get_localized_message("QA_OPTIONS_FOUND", resolved_lang, summary=summary)
            else:
                speech = get_localized_message("QA_NO_OPTIONS", resolved_lang)
            return attach_session_metadata(ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                session_id=session_ctx.session_id,
                user_goal=user_prompt,
                risk_level=RiskLevel.GREEN,
                total_steps=1,
                current_step_index=0,
                is_completed=False,
                execution_state="PAUSE_FOR_USER_INPUT",
                speech_response=speech,
                steps=[PlanStep(
                    step_id=1,
                    action=ActionType.PAUSE_FOR_USER_INPUT,
                    reason="Universal Screen QA: answered visual inquiry and paused for user input.",
                )],
            ), "guard_screen_qa")

        intent_meta = classify_intent_metadata(user_prompt)

        # Single-Word Verb Prompt Safety Guard
        if intent_meta["primary_intent"] == "AMBIGUOUS_LAUNCH":
            speech = get_localized_message("AMBIGUOUS_LAUNCH_QUERY", resolved_lang)
            return attach_session_metadata(ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                session_id=session_ctx.session_id,
                user_goal=user_prompt,
                risk_level=risk_level,
                total_steps=1,
                current_step_index=0,
                is_completed=True,
                execution_state="AWAITING_USER_INPUT",
                speech_response=speech,
                steps=[PlanStep(
                    step_id=1,
                    action=ActionType.FINISH,
                    reason="Single-word launch verb detected without target app entity; requesting clarification.",
                )],
            ), "guard_ambiguous_verb")

        # Universal Pre-Plan Foreground Package Lock Guard
        if intent_meta["primary_intent"] == "LAUNCH_APP" and not intent_meta["has_subsequent_actions"]:
            if is_app_foreground(current_app_package, user_prompt):
                target_app = intent_meta["target_app"] or "Application"
                speech = get_localized_message("APP_IS_OPEN", resolved_lang, app=target_app)
                return attach_session_metadata(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    session_id=session_ctx.session_id,
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    current_step_index=0,
                    is_completed=True,
                    execution_state="COMPLETED",
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.FINISH,
                        reason="Pre-Plan Package Lock Guard: Target application is active in foreground; pure launch request satisfied.",
                    )],
                ), "guard_foreground_package_lock")

        context_mode = detect_context_mode(screen_state)
        action_history = action_history or []

        last_action = ""
        if action_history:
            last = action_history[-1]
            last_action = str(last.get("action_type") or last.get("action") or "").upper()

        def finalize(plan: ActionPlanResponse, path_name: str) -> ActionPlanResponse:
            """Emit executable action plan for this controller turn."""
            if is_app_foreground(current_app_package, user_prompt):
                sanitized_steps = []
                for step in plan.steps:
                    if step.action == ActionType.OPEN_APP:
                        editable_node = next((n for n in screen_state if isinstance(n, dict) and node_is_editable(n)), None)
                        clickable_node = next((n for n in screen_state if isinstance(n, dict) and node_is_clickable(n) and node_target_label(n)), None)
                        if editable_node:
                            lbl = node_target_label(editable_node) or "input"
                            clean_payload = extract_clean_entity(user_prompt) or user_prompt
                            sanitized_steps.append(PlanStep(
                                step_id=step.step_id,
                                action=ActionType.TYPE_TEXT,
                                target_node=lbl,
                                target_text=lbl,
                                input_data=clean_payload,
                                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                                reason="Hard Package Lock: converted OPEN_APP to in-app TYPE_TEXT for foreground app.",
                            ))
                        elif clickable_node:
                            lbl = node_target_label(clickable_node)
                            sanitized_steps.append(PlanStep(
                                step_id=step.step_id,
                                action=ActionType.CLICK,
                                target_node=lbl,
                                target_text=lbl,
                                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                                reason="Hard Package Lock: converted OPEN_APP to in-app CLICK for foreground app.",
                            ))
                        else:
                            sanitized_steps.append(PlanStep(
                                step_id=step.step_id,
                                action=ActionType.SCROLL,
                                target_text="rescan_wait",
                                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                                reason="App is already in foreground; requesting in-app screen rescan.",
                            ))
                    else:
                        sanitized_steps.append(step)
                if sanitized_steps:
                    plan = plan.model_copy(update={"steps": sanitized_steps})

            tier, explanation = SafetyService.classify_universal_tier(user_prompt, plan, screen_state)
            if tier == "TIER_C":
                takeover = SafetyService.build_user_takeover_plan(user_prompt, explanation or "Sensitive operation detected.", plan)
                return attach_session_metadata(takeover, path_name)
            elif tier == "TIER_B":
                plan = plan.model_copy(update={
                    "requires_confirmation": True,
                    "execution_state": plan.execution_state or "AWAITING_USER_CONFIRMATION"
                })

            if len(plan.steps) > 1:
                return attach_session_metadata(plan.model_copy(update={"context_mode": context_mode}), path_name)
            step_index = plan.current_step_index
            if not 0 <= step_index < len(plan.steps):
                step_index = 0
            step = (
                plan.steps[step_index].model_copy(update={"step_id": 1})
                if plan.steps
                else PlanStep(step_id=1, action=ActionType.FINISH)
            )
            return attach_session_metadata(plan.model_copy(update={
                "steps": [step],
                "total_steps": 1,
                "current_step_index": 0,
                "context_mode": context_mode,
            }), path_name)

        # 0.1 Loop Detection Recovery Guard
        if str(action_status).upper() == "LOOP_DETECTED":
            blacklisted = set()
            for item in (blacklist_memory or []):
                if item:
                    blacklisted.add(sanitize_text(item).lower())
            for act in (action_history or []):
                target = sanitize_text(act.get("target_node") or act.get("target_text")).lower()
                act_status = str(act.get("status") or "").upper()
                if target and act_status == "LOOP_DETECTED":
                    blacklisted.add(target)
                    act_type = str(act.get("action_type") or act.get("action") or "").lower()
                    if act_type:
                        blacklisted.add(f"{act_type}:{target}")

            candidate_node = None
            for node in screen_state:
                if not isinstance(node, dict) or not node_is_clickable(node):
                    continue
                label = node_target_label(node)
                sanitized_label = sanitize_text(label).lower()
                if not sanitized_label:
                    continue
                if (
                    sanitized_label not in blacklisted
                    and f"click:{sanitized_label}" not in blacklisted
                    and not any(b in sanitized_label for b in blacklisted if len(b) > 2)
                ):
                    candidate_node = label
                    break

            if candidate_node:
                speech = get_localized_message("LOOP_DETECTED_RECOVERY", resolved_lang)
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    is_completed=False,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.CLICK,
                        target_node=candidate_node,
                        target_text=candidate_node,
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="Loop recovery override: selecting next non-blacklisted clickable element.",
                    )]
                ), "guard_loop_detected_retry")
            else:
                speech = get_localized_message("LOOP_DETECTED_RECOVERY", resolved_lang)
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    is_completed=False,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.SCROLL,
                        target_text="rescan_wait",
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="Loop recovery override: issuing SCROLL rescan wait to refresh screen topology.",
                    )]
                ), "guard_loop_detected_rescan")

        # 0.2 Foreground Action Failure Guard
        if is_app_foreground(current_app_package, user_prompt) and str(action_status).upper() == "FAILED":
            failed_targets = set()
            if target_action:
                failed_targets.add(sanitize_text(target_action).lower())
            for act in (action_history or []):
                if str(act.get("status") or "").upper() == "FAILED":
                    t_node = sanitize_text(act.get("target_node") or act.get("target_text")).lower()
                    if t_node:
                        failed_targets.add(t_node)
            for b in (blacklist_memory or []):
                if b:
                    failed_targets.add(sanitize_text(b).lower())

            candidate_node = None
            candidate_is_editable = False
            for node in screen_state:
                if not isinstance(node, dict):
                    continue
                if node_is_clickable(node) or node_is_editable(node):
                    lbl = node_target_label(node)
                    sanitized_lbl = sanitize_text(lbl).lower()
                    if (
                        sanitized_lbl
                        and sanitized_lbl not in failed_targets
                        and not any(ft in sanitized_lbl for ft in failed_targets if len(ft) > 2)
                    ):
                        candidate_node = lbl
                        candidate_is_editable = node_is_editable(node)
                        break

            if candidate_node:
                act_type = ActionType.TYPE_TEXT if candidate_is_editable else ActionType.CLICK
                speech = get_localized_message("ACTION_FAILED_RETRY", resolved_lang, target=candidate_node)
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    is_completed=False,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=act_type,
                        target_node=candidate_node,
                        target_text=candidate_node,
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="Foreground action failure recovery: selecting next target from ui_topology.",
                    )]
                ), "guard_action_failed_retry")
            else:
                speech = get_localized_message("ACTION_FAILED_RESCAN", resolved_lang)
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    is_completed=False,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.SCROLL,
                        target_text="rescan_wait",
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="Foreground action failure recovery: no alternative target found, issuing SCROLL rescan wait.",
                    )]
                ), "guard_action_failed_rescan")

        # Empty screen state recovery guard
        if len(screen_state) == 0:
            if is_app_foreground(current_app_package, user_prompt):
                speech = get_localized_message("WAITING_FOR_SCREEN_RENDER", resolved_lang)
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    is_completed=False,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.SCROLL,
                        target_text="rescan_wait",
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="Foreground app guard: empty DOM while foregrounded, issuing SCROLL rescan wait.",
                    )]
                ), "guard_empty_dom_foreground")
            elif last_action != "SWIPE_UP" and not is_compound_or_multi_step_intent(user_prompt):
                app_info = resolve_app_launch(user_prompt)
                if app_info and app_info[0]:
                    pkg, fallback_pkg, app_name = app_info
                    target_pkg = pkg or fallback_pkg or app_name
                    speech = get_localized_message("EMPTY_DOM_RECOVER_LAUNCH", resolved_lang, app=app_name)
                    return finalize(ActionPlanResponse(
                        plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                        user_goal=user_prompt,
                        risk_level=risk_level,
                        total_steps=1,
                        is_completed=False,
                        speech_response=speech,
                        steps=[PlanStep(
                            step_id=1,
                            action=ActionType.OPEN_APP,
                            target_node=target_pkg,
                            target_text=app_name,
                            package_name=pkg,
                            fallback_package_name=fallback_pkg,
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            reason="Empty DOM screen state recovery: forcing OPEN_APP step to re-establish accessibility node tree.",
                        )]
                    ), "guard_empty_dom_launch")

        # 1. Recovery Guards
        app_drawer_recovery = handle_app_drawer_recovery(
            user_prompt, screen_state, risk_level, action_status, target_action,
            current_app_package, session_key, self._app_drawer_searches, action_history,
            self._pending_install_intents, self._session_states, self._pending_app_confirmations
        )
        if app_drawer_recovery:
            return finalize(app_drawer_recovery, "guard_app_drawer")

        # 2. Contact State Shift Guards
        post_contact = handle_post_contact_selection(user_prompt, screen_state, risk_level, action_history, action_status)
        if post_contact:
            return finalize(post_contact, "guard_post_contact")

        # 3. Parameter fulfillment guards
        parameter_plan = prioritize_unadjusted_parameter_control(
            screen_state, user_prompt, risk_level, context_mode, action_history
        )
        if parameter_plan:
            return finalize(parameter_plan, "guard_parameter_fulfillment")

        # 4. Dismiss Overlay Guards
        overlay_plan = dismiss_overlay_if_present(
            screen_state, user_prompt, risk_level, action_history, target_action, action_status, context_mode
        )
        if overlay_plan:
            return finalize(overlay_plan, "guard_dismiss_overlay")

        # 5. LLM Dynamic Plan Generation via Groq
        if self.llm_client and self.llm_client.client:
            screen_ctx = {
                "active_input_focused": active_input_focused,
                "focused_hint": focused_hint,
                "grouped_objects": group_spatial_nodes(screen_state),
                "ui_topology": classify_ui_topology(screen_state),
            }
            context_prompt = self.llm_client.build_user_context_prompt(
                task_id=task_id,
                user_prompt=user_prompt,
                current_app_package=current_app_package,
                action_status=action_status,
                last_attempted_action=last_action,
                screen_context=screen_ctx,
                total_screen_elements=len(screen_state),
                action_history=action_history or [],
                session_context=session_ctx,
                target_response_language=resolved_lang,
                raw_nodes=screen_state
            )
            llm_json = await self.llm_client.generate_plan_step(user_prompt, context_prompt)
            if llm_json and "steps" in llm_json and isinstance(llm_json["steps"], list) and len(llm_json["steps"]) > 0:
                try:
                    parsed_steps = []
                    for idx, s in enumerate(llm_json["steps"]):
                        act_str = str(s.get("action") or "CLICK").upper().strip()
                        if act_str in ActionType.__members__:
                            act_enum = ActionType[act_str]
                            parsed_steps.append(PlanStep(
                                step_id=idx + 1,
                                action=act_enum,
                                target_node=s.get("target_node"),
                                target_text=s.get("target_text"),
                                input_data=s.get("input_data"),
                                package_name=s.get("package_name"),
                                fallback_package_name=s.get("fallback_package_name"),
                                reason=s.get("reason") or "Generated by LLM Planner"
                            ))
                        else:
                            logger.error("LLM_REJECTED_UNKNOWN_ACTION_NAME action=%s", act_str)

                    if parsed_steps:
                        llm_resp_lang = str(llm_json.get("response_language") or resolved_lang).lower().strip()
                        llm_plan = ActionPlanResponse(
                            plan_id=llm_json.get("plan_id") or f"plan_{uuid.uuid4().hex[:8]}",
                            user_goal=user_prompt,
                            risk_level=getattr(RiskLevel, str(llm_json.get("risk_level") or "GREEN").upper(), RiskLevel.GREEN),
                            total_steps=len(parsed_steps),
                            is_completed=bool(llm_json.get("is_completed", False)),
                            speech_response=llm_json.get("speech_response") or get_localized_message("EXECUTIVE_PLAN", llm_resp_lang, goal=user_prompt),
                            response_language=llm_resp_lang,
                            steps=parsed_steps
                        )
                        return finalize(llm_plan, "llm")
                except Exception as ex:
                    logger.error("Failed to parse LLM response JSON into ActionPlanResponse: %s", ex)

        # Fallback Plan Generation (if LLM is disabled or unavailable)
        if is_app_foreground(current_app_package, user_prompt):
            editable_node = next((n for n in screen_state if isinstance(n, dict) and node_is_editable(n)), None)
            clickable_node = next((n for n in screen_state if isinstance(n, dict) and node_is_clickable(n) and node_target_label(n)), None)
            if editable_node:
                lbl = node_target_label(editable_node) or "input"
                clean_payload = extract_clean_entity(user_prompt) or user_prompt
                speech = get_localized_message("ENTERING_TEXT_FOREGROUND", resolved_lang, target=lbl)
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    is_completed=False,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.TYPE_TEXT,
                        target_node=lbl,
                        target_text=lbl,
                        input_data=clean_payload,
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="Foreground app guard: issuing TYPE_TEXT on on-screen editable field.",
                    )]
                ), "fallback_foreground_type")
            elif clickable_node:
                lbl = node_target_label(clickable_node)
                speech = get_localized_message("INTERACTING_WITH_TARGET", resolved_lang, target=lbl)
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    is_completed=False,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.CLICK,
                        target_node=lbl,
                        target_text=lbl,
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="Foreground app guard: issuing CLICK on on-screen clickable element.",
                    )]
                ), "fallback_foreground_click")
            else:
                speech = get_localized_message("WAITING_FOR_SCREEN_RENDER", resolved_lang)
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    is_completed=False,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.SCROLL,
                        target_text="rescan_wait",
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="Foreground app guard: issuing SCROLL rescan wait to refresh topology.",
                    )]
                ), "fallback_foreground_scroll")
        elif last_action != "SWIPE_UP":
            app_launch = resolve_app_launch(user_prompt)
            if app_launch and app_launch[0]:
                pkg, fallback_pkg, app_name = app_launch
                target_pkg = pkg or fallback_pkg or app_name
                speech = get_localized_message("OPENING_APP", resolved_lang, app=app_name)
                return finalize(ActionPlanResponse(
                    plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                    user_goal=user_prompt,
                    risk_level=risk_level,
                    total_steps=1,
                    is_completed=False,
                    speech_response=speech,
                    steps=[PlanStep(
                        step_id=1,
                        action=ActionType.OPEN_APP,
                        target_node=target_pkg,
                        target_text=app_name,
                        package_name=pkg,
                        fallback_package_name=fallback_pkg,
                        tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="App launch fallback: opening target application.",
                    )]
                ), "fallback_launch_app")

        speech = get_localized_message("EXECUTIVE_PLAN", resolved_lang, goal=user_prompt)
        return finalize(ActionPlanResponse(
            plan_id=f"plan_{uuid.uuid4().hex[:8]}",
            user_goal=user_prompt,
            risk_level=risk_level,
            total_steps=1,
            is_completed=False,
            steps=[PlanStep(step_id=1, action=ActionType.SCROLL, target_text="rescan_wait")],
            speech_response=speech
        ), "fallback_default")


planner_service = PlannerService()