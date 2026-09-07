import asyncio
import time
from app.services.conversation_state import conversation_state_manager, PlannerExecutionState, ConversationSession
from app.services.planner.planner_service import planner_service, SessionContext
from app.schemas.action_plan import ActionPlanResponse, ActionType, RiskLevel, WaterfallTier


def test_session_state_manager_ttl_and_context():
    session_id = "test_user:session_123"
    ctx = SessionContext(user_id="test_user", session_id="session_123", primary_goal="Send message")
    ctx.add_turn("user", "Hello")
    ctx.add_turn("assistant", "Hi there!")

    conversation_state_manager.transition(
        session_id=session_id,
        state=PlannerExecutionState.EXECUTING,
        session_context=ctx
    )

    retrieved = conversation_state_manager.get(session_id)
    assert retrieved is not None
    assert retrieved.session_context.primary_goal == "Send message"
    assert len(retrieved.session_context.conversation_history) == 2

    # Simulate 5-minute TTL eviction
    retrieved.last_active_at = time.time() - 301
    evicted_count = conversation_state_manager.cleanup_stale_sessions(ttl_seconds=300)
    assert evicted_count >= 1
    assert conversation_state_manager.get(session_id) is None
    print("[PASS] test_session_state_manager_ttl_and_context")


def test_multi_turn_history_retention():
    session_id = "multi_turn_session_456"
    user_id = "test_user"

    plan1 = asyncio.run(planner_service.generate_plan(
        user_id=user_id,
        session_id=session_id,
        user_prompt="Open WhatsApp",
        current_app_package="com.sec.android.app.launcher",
        screen_state=[{"text": "WhatsApp", "is_clickable": True}]
    ))
    assert plan1 is not None
    assert len(plan1.conversation_history) >= 1
    assert plan1.session_id == session_id

    plan2 = asyncio.run(planner_service.generate_plan(
        user_id=user_id,
        session_id=session_id,
        user_prompt="Text Mom",
        current_app_package="com.whatsapp",
        screen_state=[{"text": "Search", "is_editable": True}]
    ))
    assert plan2 is not None
    assert len(plan2.conversation_history) >= 3
    assert plan2.session_id == session_id
    print("[PASS] test_multi_turn_history_retention")


def test_action_plan_response_contract_fields():
    resp = ActionPlanResponse(
        plan_id="plan_test_contract",
        session_id="sess_789",
        user_goal="Search web",
        thought_process="User wants to search for shoes, active field is input",
        updated_session_state={"query": "shoes"},
    )
    assert resp.thought_process == "User wants to search for shoes, active field is input"
    assert resp.updated_session_state == {"query": "shoes"}
    print("[PASS] test_action_plan_response_contract_fields")


if __name__ == "__main__":
    test_session_state_manager_ttl_and_context()
    test_multi_turn_history_retention()
    test_action_plan_response_contract_fields()

