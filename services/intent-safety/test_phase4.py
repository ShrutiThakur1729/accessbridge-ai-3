"""
Test suite for Phase 4 & App Launch Resolution & App Drawer Search Focus.
"""

import unittest
import asyncio
from fastapi.testclient import TestClient

from app.main import app
from app.schemas.action_plan import ActionType, RiskLevel, WaterfallTier, ActionPlanResponse, PlanStep
from app.services.safety_service import SafetyService
from app.services.planner import PlannerService, planner_service


class TestPhase4SafetyVisionAndAppLaunch(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_safety_service_evaluates_red_risk(self):
        """Verify that RED keywords trigger RED risk level."""
        self.assertEqual(SafetyService.evaluate_risk("delete account"), RiskLevel.RED)
        self.assertEqual(SafetyService.evaluate_risk("send money to Bob"), RiskLevel.RED)
        self.assertEqual(SafetyService.evaluate_risk("factory reset phone"), RiskLevel.RED)
        self.assertEqual(SafetyService.evaluate_risk("open whatsapp"), RiskLevel.GREEN)

    def test_safety_interceptor_blocks_risky_plan(self):
        """Verify pre-execution interceptor blocks destructive actions with HARD_STOP."""
        risky_plan = ActionPlanResponse(
            plan_id="plan_test_01",
            user_goal="Delete account",
            risk_level=RiskLevel.RED,
            total_steps=1,
            steps=[
                PlanStep(
                    step_id=1,
                    action=ActionType.CLICK,
                    target_node="btn_delete_account",
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                )
            ],
        )

        intercepted, blocked = SafetyService.intercept_plan(risky_plan, "Delete account")
        self.assertTrue(blocked)
        self.assertEqual(intercepted.error, "HARD_STOP")
        self.assertEqual(intercepted.risk_level, RiskLevel.RED)
        self.assertTrue(intercepted.is_completed)
        self.assertEqual(intercepted.steps[0].action, ActionType.HARD_STOP)

    def test_fastapi_plan_endpoint_hard_stop(self):
        """Verify HTTP POST /api/v1/plan returns HARD_STOP for dangerous intents."""
        response = self.client.post(
            "/api/v1/plan",
            json={
                "user_prompt": "Factory reset my device and wipe all data",
                "input_type": "TEXT",
                "message_type": "USER_INTENT",
            },
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["error"], "HARD_STOP")
        self.assertEqual(data["risk_level"], "RED")
        self.assertTrue(data["is_completed"])
        self.assertEqual(data["steps"][0]["action"], "HARD_STOP")

    def test_vision_coordinates_attachment(self):
        """Verify Service 03 tap_x/tap_y coordinates are mapped to action steps."""
        plan = ActionPlanResponse(
            plan_id="plan_vision_test",
            user_goal="Tap search button",
            risk_level=RiskLevel.GREEN,
            total_steps=1,
            steps=[
                PlanStep(
                    step_id=1,
                    action=ActionType.CLICK,
                    target_node="Search",
                    target_text="Search",
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                )
            ],
        )

        vision_screen_state = [
            {
                "text": "Search",
                "content_description": "Search button",
                "view_id": "vis_1",
                "source": "vision",
                "tap_x": 0.45,
                "tap_y": 0.85,
                "confidence": 0.92,
                "matched": True,
            }
        ]

        PlannerService._attach_vision_coordinates(plan, vision_screen_state)

        step = plan.steps[0]
        self.assertEqual(step.tap_x, 0.45)
        self.assertEqual(step.tap_y, 0.85)
        self.assertEqual(step.execution_mode, "COORDINATE")
        self.assertEqual(step.tier, WaterfallTier.TIER_4_VISION_OCR)

    def test_multi_word_app_launch_resolution(self):
        """Verify multi-word app names are preserved and token sub-matching works."""
        # 1. Token sub-matching for compound name containing "hotstar"
        result_jio_hotstar = planner_service._resolve_app_launch("open Jio Hotstar")
        self.assertIsNotNone(result_jio_hotstar)
        self.assertEqual(result_jio_hotstar[0], "com.ripl.hotstar")

        # 2. Token sub-matching for compound name with intent keywords
        result_hotstar_intent = planner_service._resolve_app_launch("open Jio Hotstar to watch IPL")
        self.assertIsNotNone(result_hotstar_intent)
        self.assertEqual(result_hotstar_intent[0], "com.ripl.hotstar")

        # 3. Multi-word app name preservation without truncation
        result_amazon_shopping = planner_service._resolve_app_launch("launch Amazon Shopping")
        self.assertIsNotNone(result_amazon_shopping)
        self.assertTrue(result_amazon_shopping[0] == "com.amazon.mShop.android.shopping" or result_amazon_shopping[2] == "Amazon Shopping")

        # 4. Multi-word unlisted app resolution with intent clause cleaning
        result_swiggy_instamart = planner_service._resolve_app_launch("launch Swiggy Instamart to buy groceries")
        self.assertIsNotNone(result_swiggy_instamart)
        self.assertEqual(result_swiggy_instamart[2], "Swiggy Instamart")

    def test_post_swipe_up_app_drawer_search_focus(self):
        """Verify immediate search bar focus & TYPE_TEXT of full app name post SWIPE_UP."""
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            plan = loop.run_until_complete(
                planner_service.generate_plan(
                    user_id="test_user",
                    user_prompt="open Savana",
                    current_app_package="com.sec.android.app.launcher",
                    screen_state=[{"text": "Search apps", "view_id": "search_box"}],
                    action_status="SUCCESS",
                    target_action="SWIPE_UP",
                )
            )
            self.assertEqual(plan.steps[0].action, ActionType.TYPE_TEXT)
            self.assertEqual(plan.steps[0].input_data, "Savana")
            self.assertIn("Search", plan.steps[0].target_text)
        finally:
            loop.close()

    def test_prohibit_swipe_horizontal_in_app_drawer(self):
        """Verify SWIPE_HORIZONTAL steps are prohibited and sanitized in App Drawer context."""
        plan = ActionPlanResponse(
            plan_id="plan_swipe_test",
            user_goal="open Savana",
            risk_level=RiskLevel.GREEN,
            total_steps=1,
            steps=[
                PlanStep(
                    step_id=1,
                    action=ActionType.SWIPE_HORIZONTAL,
                    target_node=None,
                    target_text=None,
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                )
            ],
        )

        planner_service._sanitize_app_drawer_swipes(
            plan=plan,
            user_prompt="open Savana",
            current_app_package="com.google.android.apps.nexuslauncher",
            screen_state=[{"text": "Search apps", "view_id": "search_box"}],
        )

        self.assertEqual(plan.steps[0].action, ActionType.TYPE_TEXT)
        self.assertEqual(plan.steps[0].input_data, "Savana")

    def test_focused_input_payload_cleaning(self):
        """Verify _prioritize_focused_input strips leading action verbs and trailing app indicators."""
        plan = ActionPlanResponse(
            plan_id="plan_payload_test",
            user_goal="play tmkoc episode 100 on YouTube",
            risk_level=RiskLevel.GREEN,
            total_steps=1,
            steps=[
                PlanStep(
                    step_id=1,
                    action=ActionType.CLICK,
                    target_node="Search",
                    target_text="Search",
                    input_data="play tmkoc episode 100 on YouTube",
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                )
            ],
        )

        screen_context = {
            "active_input_focused": True,
            "focused_hint": "Search YouTube",
        }

        planner_service._prioritize_focused_input(
            plan=plan,
            user_prompt="play tmkoc episode 100 on YouTube",
            screen_context=screen_context,
            action_status="INITIAL",
            last_attempted_action=None,
        )

        self.assertEqual(plan.steps[0].action, ActionType.TYPE_TEXT)
        self.assertEqual(plan.steps[0].input_data, "tmkoc episode 100")

    def test_prevent_consecutive_duplicate_type_text(self):
        """Verify TYPE_TEXT is not dispatched twice in a row to the same focused field if previous turn succeeded."""
        plan = ActionPlanResponse(
            plan_id="plan_duplicate_type_test",
            user_goal="play tmkoc episode 100 on YouTube",
            risk_level=RiskLevel.GREEN,
            total_steps=1,
            steps=[
                PlanStep(
                    step_id=1,
                    action=ActionType.TYPE_TEXT,
                    target_node="Search YouTube",
                    target_text="Search YouTube",
                    input_data="tmkoc episode 100",
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                )
            ],
        )

        screen_context = {
            "active_input_focused": True,
            "focused_hint": "Search YouTube",
        }

        planner_service._prioritize_focused_input(
            plan=plan,
            user_prompt="play tmkoc episode 100 on YouTube",
            screen_context=screen_context,
            action_status="SUCCESS",
            last_attempted_action="TYPE_TEXT",
        )

        self.assertNotEqual(plan.steps[0].action, ActionType.TYPE_TEXT)
        self.assertEqual(plan.steps[0].action, ActionType.SCROLL)


if __name__ == "__main__":
    unittest.main()

