from contextlib import asynccontextmanager
from dataclasses import dataclass
from threading import Lock
from typing import Dict, Optional
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.schemas.request import IntentRequest, PlannerRequest
from app.schemas.action_plan import ActionPlanResponse, ActionType, PlanStep, RiskLevel, WaterfallTier
from app.services.planner_service import planner_service
from app.services.safety_service import SafetyService
from app.services.conversation_state import (
    PlannerExecutionState,
    conversation_state_manager,
)


@dataclass
class TaskSession:
    LAUNCHER_PACKAGES = frozenset({
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.android.launcher",
        "com.android.launcher3",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
    })
    MAX_LAUNCHER_ATTEMPTS = 3

    plan: ActionPlanResponse
    current_step_index: int = 0
    last_step_status: str = "INITIAL"
    last_failure_reason: Optional[str] = None
    # The counter is intentionally only for the immediately preceding action. A
    # failure separated by another action is not a consecutive failure.
    consecutive_failures: Dict[str, int] = None
    last_failure_key: Optional[str] = None
    strategy_shifted: set[str] = None
    target_app_name: Optional[str] = None
    user_prompt: Optional[str] = None
    user_id: Optional[str] = None
    launcher_attempts: int = 0
    launcher_phase: str = "HOME"
    empty_screen_retries: int = 0

    def __post_init__(self):
        self.consecutive_failures = self.consecutive_failures or {}
        self.strategy_shifted = self.strategy_shifted or set()


class TaskSessionTracker:
    def __init__(self):
        self._sessions: Dict[str, TaskSession] = {}
        self._lock = Lock()

    def create(self, task_id: str, plan: ActionPlanResponse, user_prompt: str, user_id: str) -> ActionPlanResponse:
        with self._lock:
            plan.current_step_index = 0
            self._sessions[task_id] = TaskSession(
                plan=plan,
                target_app_name=self._target_app_name(plan),
                user_prompt=user_prompt,
                user_id=user_id,
            )
            return plan

    def apply_feedback(
        self,
        task_id: str,
        action_status: str,
        screen_state: list[dict],
        target_action: Optional[str] = None,
        target_node: Optional[str] = None,
        failure_reason: Optional[str] = None,
        execution_attempt: int = 0,
        execution_status: Optional[str] = None,
        current_package: Optional[str] = None,
    ) -> ActionPlanResponse:
        with self._lock:
            session = self._sessions.get(task_id)
            if not session:
                raise KeyError(task_id)

            session.last_step_status = action_status.upper()
            action = target_action or self._current_action(session)
            target = target_node or self._current_target(session)
            failure_key = f"{action}:{target}"

            if session.last_step_status == "FAILED" and not screen_state:
                session.empty_screen_retries += 1
                if session.empty_screen_retries >= 3:
                    self._soft_finish_app_not_found(session)
                else:
                    self._issue_drawer_swipe(session)
                session.plan.current_step_index = session.current_step_index
                return session.plan
            if screen_state:
                session.empty_screen_retries = 0

            # A successful drawer swipe must be followed by a fresh launcher scan,
            # not an automatic step advance/FINISH.
            if (
                session.last_step_status == "SUCCESS"
                and self._is_launcher(current_package)
                and self._current_action(session) in {ActionType.SWIPE_UP.value, ActionType.SWIPE_HORIZONTAL.value}
            ):
                self._advance_launcher_recovery(session, screen_state)
                session.plan.current_step_index = session.current_step_index
                return session.plan

            # Android reports execution success together with the settled screen state.
            if session.last_step_status == "SUCCESS" and screen_state is not None:
                session.consecutive_failures.pop(failure_key, None)
                session.last_failure_key = None
                session.current_step_index = min(session.current_step_index + 1, len(session.plan.steps))
            elif session.last_step_status == "FAILED":
                session.last_failure_reason = failure_reason or "Unknown execution failure"
                if action == ActionType.OPEN_APP.value and self._is_launcher(current_package):
                    # A failed system intent on Home must first open the app drawer;
                    # never repeat OPEN_APP or try to click the Home-screen label.
                    self._issue_drawer_swipe(session)
                    session.plan.current_step_index = session.current_step_index
                    return session.plan
                if self._is_launcher(current_package) and session.launcher_phase != "HOME":
                    self._advance_launcher_recovery(session, screen_state)
                    session.plan.current_step_index = session.current_step_index
                    return session.plan
                if (execution_status or "").upper() in {"NOT_CLICKABLE", "PACKAGE_NOT_FOUND"}:
                    self._replace_with_home_search(session)
                    session.plan.current_step_index = session.current_step_index
                    return session.plan
                if session.last_failure_key != failure_key:
                    session.consecutive_failures.clear()
                session.last_failure_key = failure_key
                # The planner owns the sequential counter. The device attempt is
                # accepted only as a lower bound so reconnects cannot reset it.
                failures = max(session.consecutive_failures.get(failure_key, 0) + 1, execution_attempt, 1)
                session.consecutive_failures[failure_key] = failures
                if failures >= 2:
                    self._break_failure_loop(session, failure_key, failure_reason)
            session.plan.current_step_index = session.current_step_index
            return session.plan

    @staticmethod
    def _is_launcher(current_package: Optional[str]) -> bool:
        return (current_package or "").lower() in TaskSession.LAUNCHER_PACKAGES

    @staticmethod
    def _target_app_name(plan: ActionPlanResponse) -> Optional[str]:
        for step in plan.steps:
            if step.action == ActionType.OPEN_APP:
                return step.target_text or step.target_node
        return None

    @staticmethod
    def _visible_element_label(screen_state: list[dict], target: str) -> Optional[str]:
        target_lower = target.lower()
        for element in screen_state:
            for field in ("text", "content_description", "view_id"):
                value = str(element.get(field, "")).strip()
                if value and target_lower in value.lower():
                    return value
        return None

    @staticmethod
    def _launcher_search_label(screen_state: list[dict]) -> Optional[str]:
        for element in screen_state:
            value = " ".join(str(element.get(field, "")) for field in ("text", "content_description", "view_id")).strip()
            if "search" in value.lower():
                return value
        return None

    def _advance_launcher_recovery(self, session: TaskSession, screen_state: list[dict]) -> None:
        """State machine for a failed package intent while the launcher is foreground."""
        app_name = session.target_app_name or self._launcher_label(self._current_target(session))
        if session.launcher_attempts >= TaskSession.MAX_LAUNCHER_ATTEMPTS:
            self._complete_unopenable_app(session, app_name)
            return

        app_icon = self._visible_element_label(screen_state, app_name)
        if app_icon:
            session.launcher_attempts += 1
            session.launcher_phase = "ICON_FOUND"
            self._replace_current_step(session, ActionType.CLICK, app_icon, "App icon found on launcher.")
            return

        if session.launcher_phase == "HOME":
            session.launcher_attempts += 1
            session.launcher_phase = "DRAWER_OPEN"
            self._replace_current_step(
                session,
                ActionType.SWIPE_UP,
                None,
                "App icon missing on home page, opening app drawer.",
            )
            return

        search_bar = self._launcher_search_label(screen_state)
        session.launcher_attempts += 1
        if search_bar:
            self._replace_current_step(session, ActionType.CLICK, search_bar, "Searching launcher app drawer.")
        else:
            self._replace_current_step(
                session,
                ActionType.SWIPE_HORIZONTAL,
                None,
                "App is missing from the current app-drawer page.",
            )

    def _issue_drawer_swipe(self, session: TaskSession) -> None:
        app_name = session.target_app_name or self._launcher_label(self._current_target(session))
        session.launcher_phase = "DRAWER_OPEN"
        self._replace_current_step(
            session,
            ActionType.SWIPE_UP,
            None,
            "App icon missing on home page, opening app drawer.",
        )
        session.plan.is_completed = False
        session.plan.error = None
        session.plan.speech_response = f"Opening app drawer to locate {app_name}."

    def _soft_finish_app_not_found(self, session: TaskSession) -> None:
        app_name = session.target_app_name or self._launcher_label(self._current_target(session))
        # This is a recoverable terminal directive, not a successfully completed
        # task. Keep it as the active step so direct executors receive FINISH.
        reason = "The requested app could not be opened after recovery attempts."
        if session.current_step_index >= len(session.plan.steps):
            session.plan.steps.append(PlanStep(
                step_id=len(session.plan.steps) + 1,
                action=ActionType.FINISH,
                target_node=None,
                input_data=None,
                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                requires_biometric=False,
                reason=reason,
            ))
            session.current_step_index = len(session.plan.steps) - 1
            self._renumber(session.plan)
        else:
            self._replace_current_step(session, ActionType.FINISH, None, reason)
        session.plan.current_step_index = session.current_step_index
        session.plan.is_completed = False
        session.plan.error = "APP_NOT_FOUND"
        session.plan.speech_response = f"Could not open {app_name}. Please check if the app is installed."

    def _replace_current_step(
        self,
        session: TaskSession,
        action: ActionType,
        target: Optional[str],
        reason: str,
    ) -> None:
        if session.current_step_index >= len(session.plan.steps):
            return
        old_step = session.plan.steps[session.current_step_index]
        session.plan.steps[session.current_step_index] = PlanStep(
            step_id=old_step.step_id,
            action=action,
            target_node=target,
            target_text=target,
            input_data=None,
            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
            requires_biometric=False,
            reason=reason,
        )
        self._renumber(session.plan)

    def _complete_unopenable_app(self, session: TaskSession, app_name: str) -> None:
        # Keep this launcher-recovery terminal state protocol-compatible with the
        # empty-screen failure path: FINISH is recoverable, never a success.
        self._soft_finish_app_not_found(session)

    @staticmethod
    def _current_action(session: TaskSession) -> str:
        if session.current_step_index < len(session.plan.steps):
            return session.plan.steps[session.current_step_index].action.value
        return "FINISH"

    @staticmethod
    def _current_target(session: TaskSession) -> str:
        if session.current_step_index < len(session.plan.steps):
            step = session.plan.steps[session.current_step_index]
            return step.target_node or step.input_data or ""
        return ""

    @staticmethod
    def _renumber(plan: ActionPlanResponse) -> None:
        for index, step in enumerate(plan.steps, start=1):
            step.step_id = index
        plan.total_steps = len(plan.steps)

    def _break_failure_loop(self, session: TaskSession, failure_key: str, failure_reason: Optional[str]) -> None:
        """Replace a twice-failed directive with a real, observable recovery path."""
        if failure_key in session.strategy_shifted:
            # Vision has already been requested for this target. Do not manufacture
            # another target or generic scroll directive; finish for a fresh plan.
            session.current_step_index = len(session.plan.steps)
            return

        if session.current_step_index >= len(session.plan.steps):
            return

        failed_step = session.plan.steps[session.current_step_index]
        if failed_step.tier == WaterfallTier.TIER_4_VISION_OCR:
            session.current_step_index = len(session.plan.steps)
            return

        session.strategy_shifted.add(failure_key)
        # Preserve the failure count across a Tier-4 pivot. A terminal FINISH is
        # therefore impossible before the third consecutive failed execution.
        if failed_step.action == ActionType.OPEN_APP:
            # A package launch failure must not be retried with a made-up package.
            # Ask the accessibility layer for the human-visible launcher label.
            failed_step.action = ActionType.CLICK
            failed_step.target_node = self._launcher_label(failed_step.target_node)
            failed_step.input_data = None
            failed_step.tier = WaterfallTier.TIER_3_ACCESSIBILITY
        else:
            # Tier 4 is the existing, schema-compatible Service 04 request flag.
            # Keep the original target for OCR/vision rather than inventing one.
            failed_step.tier = WaterfallTier.TIER_4_VISION_OCR
        self._renumber(session.plan)

    def _replace_with_home_search(self, session: TaskSession) -> None:
        """Use a launcher search recovery, never a synthetic click/scroll target."""
        if session.current_step_index >= len(session.plan.steps):
            return
        failed_step = session.plan.steps[session.current_step_index]
        label = self._launcher_label(failed_step.target_text or failed_step.target_node)
        session.plan.steps[session.current_step_index] = PlanStep(
            step_id=failed_step.step_id,
            action=ActionType.GO_HOME_AND_SEARCH,
            target_node=label,
            target_text=label,
            input_data=None,
            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
            requires_biometric=False,
        )
        self._renumber(session.plan)

    @staticmethod
    def _launcher_label(target: Optional[str]) -> str:
        """Derive a safe user-visible label without relying on vendor package maps."""
        if not target:
            return "Apps"
        if "." not in target:
            return target.strip() or "Apps"
        label = target.rsplit(".", 1)[-1].replace("_", " ").replace("-", " ").strip()
        if label.lower().endswith("clock"):
            return "Clock"
        return label.title() or "Apps"


task_sessions = TaskSessionTracker()


def apply_session_metadata(
    plan: ActionPlanResponse,
    session_id: str,
    session_language: str,
    message_type: str,
) -> ActionPlanResponse:
    """Commit a planner result and expose its state to the Gateway/TTS client."""
    if plan.error == "HARD_STOP" or (plan.steps and plan.steps[0].action == ActionType.HARD_STOP):
        plan.session_language = session_language
        plan.approach = "SAFETY_INTERCEPT"
        plan.execution_state = "ERROR"
        conversation_state_manager.transition(
            session_id,
            PlannerExecutionState.ERROR,
            session_language,
            {"event": "safety_hard_stop", "plan_id": plan.plan_id},
        )
        return plan

    approach = plan.approach or "SERVER_REPLANNING"
    state = (
        PlannerExecutionState.EXECUTING
        if approach == "LOCAL_RESOLUTION" or message_type == "USER_INTENT_FEEDBACK"
        else PlannerExecutionState.AWAITING_USER_CONFIRMATION
    )
    plan.session_language = session_language
    plan.approach = approach
    plan.execution_state = state.value
    conversation_state_manager.transition(
        session_id,
        state,
        session_language,
        {"event": "planner_result", "approach": approach, "plan_id": plan.plan_id},
    )
    return plan


def dispatch_plan(
    plan: ActionPlanResponse,
    user_prompt: str,
    session_id: str,
    session_language: str,
    message_type: str,
) -> ActionPlanResponse:
    """Mandatory safety checkpoint between planner output and Gateway dispatch."""
    intercepted, _blocked = SafetyService.intercept_plan(plan, user_prompt)
    return apply_session_metadata(intercepted, session_id, session_language, message_type)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup tasks
    yield
    # Shutdown tasks


app = FastAPI(
    title=settings.APP_NAME,
    debug=settings.DEBUG,
    lifespan=lifespan,
    openapi_url=f"{settings.API_PREFIX}/openapi.json",
    docs_url=f"{settings.API_PREFIX}/docs",
    redoc_url=f"{settings.API_PREFIX}/redoc",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health", tags=["Health Check"])
async def health_check():
    return {
        "status": "healthy",
        "service": settings.APP_NAME,
        "environment": settings.APP_ENV,
    }


@app.post(
    f"{settings.API_PREFIX}/plan",
    response_model=ActionPlanResponse,
    tags=["Intent Planning"],
    summary="Generate Execution Plan from User Prompt",
    description="Evaluates user intent safety risk level and creates structured UI navigation steps."
)
async def generate_plan(request: PlannerRequest) -> ActionPlanResponse:
    user_prompt = request.user_prompt or "Continue task"
    user_id = request.user_id or "default_user"
    current_pkg = request.current_package_name or ""
    screen_elements = request.compressed_elements or []
    session_id = request.session_id or request.task_id or "default_session"
    previous_session = conversation_state_manager.get(session_id)
    session_language = request.session_language or (previous_session.session_language if previous_session else "en-US")
    conversation_state_manager.transition(
        session_id,
        PlannerExecutionState.PROCESSING_DOM,
        session_language,
        {"event": "user_turn", "prompt": user_prompt, "dom_nodes": len(screen_elements)},
    )

    prompt_reason = SafetyService.classify_red_reason(user_prompt)
    if prompt_reason or SafetyService.evaluate_risk(user_prompt) == RiskLevel.RED:
        return dispatch_plan(
            SafetyService.build_hard_stop_plan(user_prompt, prompt_reason or "generic"),
            user_prompt,
            session_id,
            session_language,
            request.message_type,
        )

    if request.message_type == "USER_INTENT_FEEDBACK":
        if not request.task_id:
            raise HTTPException(status_code=400, detail="task_id is required for intent feedback.")
        
        with task_sessions._lock:
            session = task_sessions._sessions.get(request.task_id)
        
        if session:
            user_prompt = request.user_prompt or session.user_prompt or user_prompt
            user_id = request.user_id or session.user_id or user_id

        # 1. Check if recovery state machine needs to handle launcher/drawer failure fallback
        if request.action_status.upper() == "FAILED" and task_sessions._is_launcher(current_pkg):
            if session:
                return dispatch_plan(task_sessions.apply_feedback(
                    request.task_id,
                    request.action_status,
                    screen_elements,
                    request.target_action,
                    request.target_node,
                    request.failure_reason,
                    request.execution_attempt,
                    request.status,
                    current_pkg,
                ), user_prompt, session_id, session_language, request.message_type)

        # 2. Dynamic Turn Re-Evaluation with enriched screen context
        new_plan = await planner_service.generate_plan(
            user_id=user_id,
            user_prompt=user_prompt,
            current_app_package=current_pkg,
            screen_state=screen_elements,
            action_status=request.action_status, 
            target_action=request.target_action,
            task_id=request.task_id,
            action_history=request.action_history,
            active_input_focused=request.active_input_focused,
            focused_hint=request.focused_hint,
            session_language=session_language,
            screenshot_base64=request.screenshot_base64,
            vision_elements=request.vision_elements,
        )
        new_plan = dispatch_plan(new_plan, user_prompt, session_id, session_language, request.message_type)

        # 3. Update or register the active session with the newly generated action step
        if session:
            with task_sessions._lock:
                session.plan = new_plan
                session.current_step_index = 0
                session.last_step_status = request.action_status.upper()
                session.plan.current_step_index = 0
        elif request.task_id:
            task_sessions.create(request.task_id, new_plan, user_prompt, user_id)

        return new_plan

    # --- Initial Turn (Turn 1) ---
    if not request.user_prompt and request.message_type == "USER_INTENT":
    # If it's the very first intent turn and has no prompt or stored session state
        raise HTTPException(
            status_code=400, 
            detail="user_prompt is required for initial intent planning."
        )
    
    plan = await planner_service.generate_plan(
        user_id=user_id,
        user_prompt=user_prompt,
        current_app_package=current_pkg,
        screen_state=screen_elements,
        action_status=request.action_status, 
        target_action=request.target_action,
        task_id=request.task_id,
        action_history=request.action_history,
        active_input_focused=request.active_input_focused,
        focused_hint=request.focused_hint,
        session_language=session_language,
        screenshot_base64=request.screenshot_base64,
        vision_elements=request.vision_elements,
    )
    plan = dispatch_plan(plan, user_prompt, session_id, session_language, request.message_type)

    if request.task_id:
        task_sessions.create(request.task_id, plan, user_prompt, user_id)

    return plan
