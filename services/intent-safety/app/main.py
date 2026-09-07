from contextlib import asynccontextmanager
from typing import Optional
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.schemas.request import PlannerRequest
from app.schemas.action_plan import ActionPlanResponse, ActionType, RiskLevel
from app.services.planner import planner_service
from app.services.safety_service import SafetyService
from app.services.conversation_state import (
    PlannerExecutionState,
    conversation_state_manager,
)


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
    yield


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

    # Safety Evaluation
    prompt_reason = SafetyService.classify_red_reason(user_prompt)
    if prompt_reason or SafetyService.evaluate_risk(user_prompt) == RiskLevel.RED:
        return dispatch_plan(
            SafetyService.build_hard_stop_plan(user_prompt, prompt_reason or "generic"),
            user_prompt,
            session_id,
            session_language,
            request.message_type,
        )

    # Initial Turn Validation Check
    if not request.user_prompt and request.message_type == "USER_INTENT":
        raise HTTPException(
            status_code=400, 
            detail="user_prompt is required for initial intent planning."
        )

    # Direct dynamic plan generation via planner_service.py
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
        blacklist_memory=request.blacklist_memory,
        session_id=session_id,
    )

    return dispatch_plan(plan, user_prompt, session_id, session_language, request.message_type)