import asyncio
from app.services.planner.ui_topology import group_spatial_nodes
from app.utils.screen_compressor import compress_screen_state
from app.services.safety_service import SafetyService
from app.services.planner.planner_service import planner_service
from app.schemas.action_plan import ActionType, RiskLevel, ActionPlanResponse


def test_universal_spatial_grouping():
    screen_nodes = [
        {"text": "Item Title", "bounds": [100, 50, 150, 200], "view_id": "container_1"},
        {"text": "$19.99", "bounds": [110, 55, 140, 120], "view_id": "container_1"},
        {"text": "4.5 Stars", "bounds": [105, 130, 145, 190], "view_id": "container_1"},
        {"text": "Add to Cart", "is_clickable": True, "bounds": [160, 50, 200, 200], "view_id": "container_1"},
    ]
    grouped = group_spatial_nodes(screen_nodes)
    assert len(grouped) >= 1
    assert "Item Title" in grouped[0]["combined_label"]
    assert "$19.99" in grouped[0]["combined_label"]

    compressed = compress_screen_state(screen_nodes)
    assert "grouped_objects" in compressed
    assert len(compressed["grouped_objects"]) >= 1
    print("[PASS] test_universal_spatial_grouping")


def test_universal_screen_qa_handling():
    screen_nodes = [
        {"text": "Option A - $10", "is_clickable": True},
        {"text": "Option B - $20", "is_clickable": True},
    ]
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        session_id="qa_session",
        user_prompt="What are the options on screen?",
        screen_state=screen_nodes,
    ))
    assert plan is not None
    assert plan.steps[0].action == ActionType.PAUSE_FOR_USER_INPUT
    assert plan.execution_state == "PAUSE_FOR_USER_INPUT"
    assert plan.is_completed is False
    assert "Option A" in plan.speech_response or "options" in plan.speech_response
    print("[PASS] test_universal_screen_qa_handling")


def test_universal_3_tier_safety_classifier():
    # Tier A: Read / Navigate
    tier_a, _ = SafetyService.classify_universal_tier("Show me search results")
    assert tier_a == "TIER_A"

    # Tier B: Mutate / Direct -> Confirmation Required
    tier_b, _ = SafetyService.classify_universal_tier("Submit this order form")
    assert tier_b == "TIER_B"

    # Tier C: Sensitive / Financial -> USER_TAKE_OVER
    tier_c, _ = SafetyService.classify_universal_tier("Enter my password and pin")
    assert tier_c == "TIER_C"

    # Test Tier C plan generation
    plan_c = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        session_id="sensitive_session",
        user_prompt="Enter my password and pin",
        screen_state=[{"text": "Password", "view_id": "edit_password", "is_editable": True}]
    ))
    assert plan_c is not None
    assert plan_c.steps[0].action == ActionType.USER_TAKE_OVER
    assert plan_c.execution_state == "USER_TAKE_OVER"
    assert plan_c.is_completed is True
    assert "Please complete" in plan_c.speech_response
    print("[PASS] test_universal_3_tier_safety_classifier")


if __name__ == "__main__":
    test_universal_spatial_grouping()
    test_universal_screen_qa_handling()
    test_universal_3_tier_safety_classifier()

