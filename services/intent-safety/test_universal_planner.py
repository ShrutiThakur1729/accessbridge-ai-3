import asyncio
from app.schemas.action_plan import RiskLevel, ActionType
from app.services.planner.llm_client import SYSTEM_PROMPT
from app.services.planner.state_guards import prioritize_unadjusted_parameter_control
from app.services.planner.planner_service import planner_service

def test_system_prompt_contains_parameter_fulfillment_check():
    expected_substring = (
        "PARAMETER FULFILLMENT CHECK: Before outputting a commit action (e.g., Save, Submit, Done), "
        "verify whether requested parameter values (e.g., time digits, AM/PM, toggles) visible on screen have been applied. "
        "If interactive parameters are visible and unadjusted, execute parameter adjustment steps before tapping commit."
    )
    assert expected_substring in SYSTEM_PROMPT, f"Expected prompt substring not found in SYSTEM_PROMPT"

def test_block_premature_save_on_turn_1_when_unadjusted_parameters_exist():
    screen_state = [
        {"text": "AM", "is_clickable": True, "class_name": "android.widget.Button"},
        {"text": "10:00", "is_clickable": True, "class_name": "android.widget.TextView"},
        {"text": "Save", "is_clickable": True, "class_name": "android.widget.Button"}
    ]
    plan = prioritize_unadjusted_parameter_control(
        screen_state=screen_state,
        user_prompt="Set alarm for 10:00 AM",
        risk_level=RiskLevel.GREEN,
        action_history=[]
    )
    assert plan is not None
    assert len(plan.steps) == 1
    assert plan.steps[0].action == ActionType.CLICK
    assert plan.steps[0].target_node in ["AM", "10:00"]
    assert "Save" not in plan.steps[0].target_node

def test_allow_save_after_parameter_control_is_targeted():
    screen_state = [
        {"text": "AM", "is_clickable": True, "class_name": "android.widget.Button"},
        {"text": "Save", "is_clickable": True, "class_name": "android.widget.Button"}
    ]
    action_history = [{"action_type": "CLICK", "target_node": "AM", "status": "SUCCESS"}]
    plan = prioritize_unadjusted_parameter_control(
        screen_state=screen_state,
        user_prompt="Set alarm for 10:00 AM",
        risk_level=RiskLevel.GREEN,
        action_history=action_history
    )
    assert plan is None

def test_loop_detected_override_selects_non_blacklisted_node():
    screen_state = [
        {"text": "Repeated Node", "is_clickable": True},
        {"text": "Alternative Node", "is_clickable": True}
    ]
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        user_prompt="Navigate settings",
        screen_state=screen_state,
        action_status="LOOP_DETECTED",
        blacklist_memory=["Repeated Node"]
    ))
    assert plan is not None
    assert plan.steps[0].action == ActionType.CLICK
    assert plan.steps[0].target_node == "Alternative Node"

def test_dynamic_prefix_matching_in_app_drawer():
    screen_state = [
        {"text": "Search apps", "is_editable": True, "class_name": "android.widget.EditText"},
        {"text": "JioSaavn", "is_clickable": True, "class_name": "android.widget.TextView", "top": 300}
    ]
    planner_service._app_drawer_searches["test_user:test_task"] = "Savan"
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="test_task",
        user_prompt="open Savan",
        screen_state=screen_state,
        action_history=[{"action_type": "TYPE_TEXT", "target_node": "Search apps", "status": "SUCCESS"}]
    ))
    assert plan is not None
    assert plan.steps[0].action == ActionType.CLICK
    assert plan.steps[0].target_node == "JioSaavn"

def test_app_drawer_top_result_fallback():
    screen_state = [
        {"text": "Search apps", "is_editable": True, "class_name": "android.widget.EditText"},
        {"text": "Music Player", "is_clickable": True, "class_name": "android.widget.TextView", "top": 300}
    ]
    planner_service._app_drawer_searches["test_user:test_task"] = "UnmatchedApp"
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="test_task",
        user_prompt="open UnmatchedApp",
        screen_state=screen_state,
        action_history=[{"action_type": "TYPE_TEXT", "target_node": "Search apps", "status": "SUCCESS"}]
    ))
    assert plan is not None
    assert plan.steps[0].action == ActionType.CLICK
    assert plan.steps[0].target_node == "Music Player"

def test_play_store_install_confirmation_and_flow():
    screen_state = []
    session_key = "test_user:test_task"
    
    # 1. Zero results -> Play Store prompt
    plan1 = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="test_task",
        user_prompt="open UnknownApp123",
        screen_state=screen_state,
        action_history=[{"action_type": "TYPE_TEXT", "target_node": "Search apps", "status": "SUCCESS"}]
    ))
    assert plan1 is not None
    assert plan1.is_completed is True
    assert "Would you like me to install it from the Play Store?" in plan1.speech_response
    assert planner_service._pending_install_intents.get(session_key).lower() == "unknownapp123"

    # 2. User confirms -> 3-step installation plan
    plan2 = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="test_task",
        user_prompt="Yes, install it",
        screen_state=screen_state,
        action_history=[{"action_type": "FINISH", "status": "SUCCESS"}]
    ))
    assert plan2 is not None
    assert len(plan2.steps) == 3
    assert plan2.steps[0].action == ActionType.OPEN_APP
    assert plan2.steps[0].package_name == "com.android.vending"
    assert plan2.steps[1].action == ActionType.TYPE_TEXT
    assert plan2.steps[2].action == ActionType.CLICK
    assert plan2.steps[2].target_node == "Install"

def test_post_swipe_loop_protection():
    screen_state = [
        {"text": "Search apps", "is_editable": True, "class_name": "android.widget.EditText"}
    ]
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="test_task",
        user_prompt="open WhatsApp",
        screen_state=screen_state,
        action_history=[{"action_type": "SWIPE_UP", "status": "SUCCESS"}]
    ))
    assert plan is not None
    assert plan.steps[0].action != ActionType.OPEN_APP
    assert plan.steps[0].action in {ActionType.TYPE_TEXT, ActionType.CLICK, ActionType.SCROLL}

def test_hard_package_lock_forbids_open_app_when_foregrounded():
    screen_state = [
        {"text": "Type a message", "is_editable": True, "class_name": "android.widget.EditText"},
        {"text": "Send", "is_clickable": True, "class_name": "android.widget.Button"}
    ]
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="test_task",
        user_prompt="send a message on WhatsApp",
        current_app_package="com.whatsapp",
        screen_state=screen_state,
        action_status="INITIAL"
    ))
    assert plan is not None
    assert plan.steps[0].action != ActionType.OPEN_APP
    assert plan.steps[0].action in {ActionType.TYPE_TEXT, ActionType.CLICK}

def test_foreground_action_failure_recovery():
    screen_state = [
        {"text": "Failed Button", "is_clickable": True},
        {"text": "Next Control", "is_clickable": True}
    ]
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="test_task",
        user_prompt="send a message on WhatsApp",
        current_app_package="com.whatsapp",
        screen_state=screen_state,
        action_status="FAILED",
        target_action="Failed Button"
    ))
    assert plan is not None
    assert plan.steps[0].action != ActionType.OPEN_APP
    assert plan.steps[0].action == ActionType.CLICK
    assert plan.steps[0].target_node == "Next Control"

def test_phonetic_normalization_and_fuzzy_matching():
    from app.services.planner.entity_extractor import normalize_app_query
    from app.services.planner.ui_topology import find_best_app_match

    candidates_blinkit = normalize_app_query("blink it")
    assert candidates_blinkit["no_spaces"] == "blinkit"
    assert candidates_blinkit["consonants_only"] == "blnkt"

    candidates_zipto = normalize_app_query("zip to")
    assert candidates_zipto["no_spaces"] == "zipto"

    candidates_saavn = normalize_app_query("saavn")
    best_match_saavn = find_best_app_match(candidates_saavn, ["YouTube", "JioSaavn", "Settings"])
    assert best_match_saavn == "JioSaavn"

    candidates_zipto_fuzzy = normalize_app_query("zip to")
    best_match_zipto = find_best_app_match(candidates_zipto_fuzzy, ["Zipto", "Uber"])
    assert best_match_zipto == "Zipto"

if __name__ == "__main__":
    test_system_prompt_contains_parameter_fulfillment_check()
    test_block_premature_save_on_turn_1_when_unadjusted_parameters_exist()
    test_allow_save_after_parameter_control_is_targeted()
    test_loop_detected_override_selects_non_blacklisted_node()
    test_dynamic_prefix_matching_in_app_drawer()
    test_app_drawer_top_result_fallback()
    test_play_store_install_confirmation_and_flow()
    test_post_swipe_loop_protection()
    test_hard_package_lock_forbids_open_app_when_foregrounded()
    test_foreground_action_failure_recovery()
    print("All unit tests (parameter fulfillment, loop detection, dynamic matching, Play Store flow, post-swipe protection & hard package lock) passed successfully!")
    test_phonetic_normalization_and_fuzzy_matching()
    print("All unit tests (parameter fulfillment, loop detection, dynamic matching, Play Store flow, post-swipe protection, hard package lock & phonetic fuzzy matching) passed successfully!")
