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
    task_id = "prefix_task"
    session_key = f"test_user:{task_id}"
    planner_service._session_states.pop(session_key, None)
    screen_state = [
        {"text": "Search apps", "is_editable": True, "class_name": "android.widget.EditText"},
        {"text": "JioSaavn", "is_clickable": True, "class_name": "android.widget.TextView", "top": 300}
    ]
    planner_service._app_drawer_searches[session_key] = "Savan"
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="open Savan",
        screen_state=screen_state,
        action_history=[{"action_type": "TYPE_TEXT", "target_node": "Search apps", "status": "SUCCESS"}]
    ))
    assert plan is not None
    assert plan.execution_state == "AWAITING_USER_CONFIRMATION"
    assert "JioSaavn" in plan.speech_response

def test_app_drawer_top_result_fallback():
    task_id = "top_result_task"
    session_key = f"test_user:{task_id}"
    planner_service._session_states.pop(session_key, None)
    screen_state = [
        {"text": "Search apps", "is_editable": True, "class_name": "android.widget.EditText"},
        {"text": "Music Player", "is_clickable": True, "class_name": "android.widget.TextView", "top": 300}
    ]
    planner_service._app_drawer_searches[session_key] = "UnmatchedApp"
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="open UnmatchedApp",
        screen_state=screen_state,
        action_history=[{"action_type": "TYPE_TEXT", "target_node": "Search apps", "status": "SUCCESS"}]
    ))
    assert plan is not None
    assert plan.is_completed is False
    assert plan.execution_state == "AWAITING_USER_CONFIRMATION"
    assert "is not installed on your phone" in plan.speech_response

def test_play_store_install_confirmation_and_flow():
    screen_state = []
    task_id = "play_store_task"
    session_key = f"test_user:{task_id}"
    planner_service._pending_install_intents.pop(session_key, None)
    planner_service._app_drawer_searches.pop(session_key, None)
    
    # 1. Zero results -> Play Store prompt
    plan1 = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="open UnknownApp123",
        screen_state=screen_state,
        action_history=[{"action_type": "TYPE_TEXT", "target_node": "Search apps", "status": "SUCCESS"}]
    ))
    assert plan1 is not None
    assert plan1.is_completed is False
    assert plan1.execution_state == "AWAITING_USER_CONFIRMATION"
    assert "Would you like me to install it from the Play Store?" in plan1.speech_response
    assert planner_service._pending_install_intents.get(session_key).lower() == "unknownapp123"

    # 2. User confirms -> 3-step installation plan
    plan2 = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
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

    # 3. User denies -> Cancel installation and complete clean
    planner_service._pending_install_intents[session_key] = "UnknownApp123"
    plan3 = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="No, cancel",
        screen_state=screen_state,
        action_history=[{"action_type": "FINISH", "status": "SUCCESS"}]
    ))
    assert plan3 is not None
    assert plan3.is_completed is True
    assert plan3.steps[0].action == ActionType.FINISH
    assert planner_service._pending_install_intents.get(session_key) is None

def test_post_swipe_loop_protection():
    task_id = "post_swipe_task"
    session_key = f"test_user:{task_id}"
    planner_service._session_states.pop(session_key, None)
    screen_state = [
        {"text": "Search apps", "is_editable": True, "class_name": "android.widget.EditText"}
    ]
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="open WhatsApp",
        screen_state=screen_state,
        action_history=[{"action_type": "SWIPE_UP", "status": "SUCCESS"}]
    ))
    assert plan is not None
    assert plan.steps[0].action != ActionType.OPEN_APP
    assert plan.steps[0].action in {ActionType.TYPE_TEXT, ActionType.CLICK, ActionType.SCROLL}

def test_hard_package_lock_forbids_open_app_when_foregrounded():
    task_id = "hard_package_lock_task"
    session_key = f"test_user:{task_id}"
    planner_service._session_states.pop(session_key, None)
    screen_state = [
        {"text": "Type a message", "is_editable": True, "class_name": "android.widget.EditText"},
        {"text": "Send", "is_clickable": True, "class_name": "android.widget.Button"}
    ]
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="send a message on WhatsApp",
        current_app_package="com.whatsapp",
        screen_state=screen_state,
        action_status="INITIAL"
    ))
    assert plan is not None
    assert plan.steps[0].action != ActionType.OPEN_APP
    assert plan.steps[0].action in {ActionType.TYPE_TEXT, ActionType.CLICK}

def test_foreground_action_failure_recovery():
    task_id = "foreground_failure_task"
    session_key = f"test_user:{task_id}"
    planner_service._session_states.pop(session_key, None)
    screen_state = [
        {"text": "Failed Button", "is_clickable": True},
        {"text": "Next Control", "is_clickable": True}
    ]
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
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

def test_app_drawer_launcher_utility_filtering():
    screen_state = [
        {"text": "Search apps", "is_editable": True, "class_name": "android.widget.EditText"},
        {"text": "Voice Search", "is_clickable": True, "class_name": "android.widget.TextView", "top": 250},
        {"text": "Speak", "is_clickable": True, "class_name": "android.widget.TextView", "top": 280},
        {"text": "Google", "is_clickable": True, "class_name": "android.widget.TextView", "top": 300},
        {"text": "Blinkit", "is_clickable": True, "class_name": "android.widget.TextView", "top": 350}
    ]
    task_id = "filter_test_task"
    session_key = f"test_user:{task_id}"
    planner_service._app_drawer_searches[session_key] = "blink it"
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="open blink it",
        screen_state=screen_state,
        action_history=[{"action_type": "TYPE_TEXT", "target_node": "Search apps", "status": "SUCCESS"}]
    ))
    assert plan is not None
    assert plan.steps[0].action == ActionType.CLICK
    assert plan.steps[0].target_node == "Blinkit"

def test_intent_metadata_classification():
    from app.services.planner.planner_service import classify_intent_metadata
    m1 = classify_intent_metadata("Open WhatsApp")
    assert m1["primary_intent"] == "LAUNCH_APP"
    assert m1["target_app"] == "WhatsApp"
    assert m1["has_subsequent_actions"] is False

    m2 = classify_intent_metadata("Open WhatsApp and text Mom")
    assert m2["primary_intent"] == "LAUNCH_APP"
    assert m2["target_app"] == "WhatsApp"
    assert m2["has_subsequent_actions"] is True

    m3 = classify_intent_metadata("Launch Camera")
    assert m3["primary_intent"] == "LAUNCH_APP"
    assert m3["target_app"] == "Camera"
    assert m3["has_subsequent_actions"] is False

def test_pure_launch_goal_termination_when_foregrounded():
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="pure_launch_task",
        user_prompt="Open WhatsApp",
        current_app_package="com.whatsapp",
        screen_state=[]
    ))
    assert plan is not None
    assert plan.is_completed is True
    assert plan.execution_state == "COMPLETED"
    assert "WhatsApp is open" in plan.speech_response
    assert plan.steps[0].action == ActionType.FINISH

def test_multi_step_continuation_when_foregrounded():
    screen_state = [
        {"text": "Search", "is_editable": True, "class_name": "android.widget.EditText"}
    ]
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="multi_step_task",
        user_prompt="Open WhatsApp and text Mom",
        current_app_package="com.whatsapp",
        screen_state=screen_state
    ))
    assert plan is not None
    assert plan.is_completed is False
    assert plan.steps[0].action != ActionType.OPEN_APP
    assert plan.steps[0].action in {ActionType.TYPE_TEXT, ActionType.CLICK, ActionType.SCROLL}

def test_home_first_app_drawer_recovery_sequence():
    task_id = "home_first_recovery_task"
    session_key = f"test_user:{task_id}"
    planner_service._session_states.pop(session_key, None)

    # Step 1: Open app fails -> GO_HOME
    plan1 = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="open UnknownApp999",
        current_app_package="com.random.app",
        screen_state=[],
        action_status="FAILED",
        target_action="OPEN_APP"
    ))
    assert plan1 is not None
    assert plan1.steps[0].action == ActionType.GO_HOME
    assert plan1.steps[0].target_text == "go_home"

    # Step 2: Next turn (after GO_HOME) -> SWIPE_UP
    plan2 = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="open UnknownApp999",
        current_app_package="com.android.launcher",
        screen_state=[],
        action_history=[{"action_type": "GO_HOME", "status": "SUCCESS"}]
    ))
    assert plan2 is not None
    assert plan2.steps[0].action == ActionType.SWIPE_UP

    # Step 3: Next turn (after SWIPE_UP) -> TYPE_TEXT in search bar & state locking APP_DRAWER_ACTIVE
    screen_state_drawer = [
        {"text": "Search apps", "is_editable": True, "class_name": "android.widget.EditText"}
    ]
    plan3 = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="open UnknownApp999",
        current_app_package="com.android.launcher",
        screen_state=screen_state_drawer,
        action_history=[{"action_type": "SWIPE_UP", "status": "SUCCESS"}]
    ))
    assert plan3 is not None
    assert plan3.steps[0].action == ActionType.TYPE_TEXT
    assert planner_service._session_states.get(session_key) == "APP_DRAWER_ACTIVE"

def test_ambiguous_app_match_confirmation_flow():
    task_id = "ambiguous_match_task"
    session_key = f"test_user:{task_id}"
    planner_service._pending_app_confirmations.pop(session_key, None)

    screen_state = [
        {"text": "Search apps", "is_editable": True, "class_name": "android.widget.EditText"},
        {"text": "Nykaa Fashion", "is_clickable": True, "class_name": "android.widget.TextView", "top": 300}
    ]
    planner_service._app_drawer_searches[session_key] = "mica fashion"

    # Turn 1: Ambiguous match (0.50-0.79 score) -> AWAITING_USER_CONFIRMATION prompt
    plan1 = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="open mica fashion",
        screen_state=screen_state,
        action_history=[{"action_type": "TYPE_TEXT", "target_node": "Search apps", "status": "SUCCESS"}]
    ))
    assert plan1 is not None
    assert plan1.is_completed is False
    assert plan1.execution_state == "AWAITING_USER_CONFIRMATION"
    assert "I couldn't find 'mica fashion', but I found 'Nykaa Fashion'. Would you like me to open that?" in plan1.speech_response
    assert planner_service._pending_app_confirmations.get(session_key) == "Nykaa Fashion"

    # Turn 2: User confirms -> CLICK Nykaa Fashion
    plan2 = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="Yes, open it",
        screen_state=screen_state,
        action_history=[{"action_type": "FINISH", "status": "SUCCESS"}]
    ))
    assert plan2 is not None
    assert plan2.steps[0].action == ActionType.CLICK
    assert plan2.steps[0].target_node == "Nykaa Fashion"
    assert planner_service._pending_app_confirmations.get(session_key) is None

def test_dynamic_foreground_package_lock_guard():
    # Prompt: "Open Savana", active package: "com.savana.android"
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="savana_task",
        user_prompt="Open Savana",
        current_app_package="com.savana.android",
        screen_state=[
            {"text": "Search in Savana", "is_editable": True, "class_name": "android.widget.EditText"}
        ]
    ))
    assert plan is not None
    assert plan.is_completed is True
    assert plan.execution_state == "COMPLETED"
    assert "Savana is open" in plan.speech_response
    assert plan.steps[0].action == ActionType.FINISH

def test_strict_score_floor_discards_low_similarity_candidates():
    task_id = "strict_floor_task"
    session_key = f"test_user:{task_id}"
    planner_service._session_states.pop(session_key, None)
    planner_service._pending_app_confirmations.pop(session_key, None)
    planner_service._pending_install_intents.pop(session_key, None)

    screen_state = [
        {"text": "Search apps", "is_editable": True, "class_name": "android.widget.EditText"},
        {"text": "Smart Money", "is_clickable": True, "class_name": "android.widget.TextView", "top": 300}
    ]
    planner_service._app_drawer_searches[session_key] = "Zomato"

    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id=task_id,
        user_prompt="open Zomato",
        screen_state=screen_state,
        action_history=[{"action_type": "TYPE_TEXT", "target_node": "Search apps", "status": "SUCCESS"}]
    ))
    assert plan is not None
    assert plan.is_completed is False
    assert plan.execution_state == "AWAITING_USER_CONFIRMATION"
    assert "Smart Money" not in plan.speech_response
    assert "The app 'Zomato' is not installed on your phone. Would you like me to install it from the Play Store?" in plan.speech_response
    assert planner_service._pending_install_intents.get(session_key).lower() == "zomato"

def test_compound_hindi_intent_reaches_llm():
    plan = asyncio.run(planner_service.generate_plan(
        user_id="test_user",
        task_id="compound_hindi_task",
        user_prompt="youtube kholo aur tarak mehta ka ulta chashma lagao",
        screen_state=[]
    ))
    assert plan is not None
    assert len(plan.steps) > 0
    assert plan.steps[0].action == ActionType.OPEN_APP
    assert plan.steps[0].package_name == "com.google.android.youtube"
    input_text = (plan.steps[0].input_data or plan.steps[0].target_text or "").lower()
    assert "tarak mehta" in input_text or "taarak mehta" in input_text

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
    test_phonetic_normalization_and_fuzzy_matching()
    test_app_drawer_launcher_utility_filtering()
    test_intent_metadata_classification()
    test_pure_launch_goal_termination_when_foregrounded()
    test_multi_step_continuation_when_foregrounded()
    test_home_first_app_drawer_recovery_sequence()
    test_ambiguous_app_match_confirmation_flow()
    test_dynamic_foreground_package_lock_guard()
    test_strict_score_floor_discards_low_similarity_candidates()
    test_compound_hindi_intent_reaches_llm()
    print("All unit tests (including compound Hindi intent Groq LLM reasoning) passed successfully!")
