import logging
import re
from typing import Optional, Tuple

from app.schemas.action_plan import ActionPlanResponse, ActionType, PlanStep, RiskLevel, WaterfallTier

logger = logging.getLogger("intent-safety.safety")

HARD_STOP_ERROR = "HARD_STOP"


class SafetyService:
    """
    Evaluates risk and enforces a mandatory pre-dispatch intercept.

    GREEN: Read-only, search, navigation, opening system tools
    YELLOW: Drafting text, non-critical updates, filling fields, sending chat messages
    RED: Financial transfers, unauthorized access, destructive changes (Requires Confirmation/Biometric)
    """

    # Strictly financial or destructive keywords that elevate overall risk
    RED_KEYWORDS = [
        "send money",
        "transfer money",
        "pay",
        "withdraw",
        "delete account",
        "factory reset",
        "wipe data",
        "uninstall",
        "format phone",
        "disable accessibility",
        "grant all permissions",
        "bypass lock",
        "root device",
        "hack",
    ]

    YELLOW_KEYWORDS = [
        "type",
        "draft",
        "message",
        "send a message",
        "write",
        "search for",
        "update profile",
        "send",
    ]

    DESTRUCTIVE_STEP_MARKERS = [
        "delete_account",
        "uninstall_app",
        "factory_reset",
        "format_device",
        "grant_permission",
        "bypass_security",
    ]

    SAFE_HARBOR_MESSAGES = {
        "financial": (
            "I cannot complete financial or payment actions for you. "
            "Please confirm any transfer or purchase yourself in the app."
        ),
        "destructive": (
            "I cannot make destructive changes such as deleting data, uninstalling apps, "
            "or resetting this device. Please do that yourself if you still want to proceed."
        ),
        "unauthorized": (
            "I cannot perform unauthorized access, permission grants, or security bypasses. "
            "This request has been blocked for your safety."
        ),
        "generic": (
            "This action was blocked by the safety engine because it is too risky to automate. "
            "Please complete it yourself."
        ),
    }

    @staticmethod
    def evaluate_risk(user_prompt: str) -> RiskLevel:
        prompt_lower = user_prompt.lower()

        for keyword in SafetyService.RED_KEYWORDS:
            if keyword in prompt_lower:
                return RiskLevel.RED

        for keyword in SafetyService.YELLOW_KEYWORDS:
            if keyword in prompt_lower:
                return RiskLevel.YELLOW

        return RiskLevel.GREEN

    @staticmethod
    def classify_red_reason(user_prompt: str, plan: Optional[ActionPlanResponse] = None) -> Optional[str]:
        haystack = user_prompt.lower()

        # Strict checks for actual financial transaction phrasing (not generic "send")
        if re.search(r"\b(send money|transfer money|pay bill|withdraw cash|confirm payment|buy item)\b", haystack):
            return "financial"
        if re.search(r"\b(factory reset|wipe data|uninstall app|format device|delete account)\b", haystack):
            return "destructive"
        if re.search(r"\b(hack|bypass lock|root device|disable accessibility)\b", haystack):
            return "unauthorized"

        return None

    @staticmethod
    def _step_is_destructive(step: PlanStep) -> bool:
        blob = " ".join(
            filter(None, [step.action.value if step.action else "", step.target_node, step.target_text, step.reason])
        ).lower()
        return any(marker in blob for marker in SafetyService.DESTRUCTIVE_STEP_MARKERS)

    @staticmethod
    def requires_biometric_check(risk_level: RiskLevel) -> bool:
        return risk_level == RiskLevel.RED

    @staticmethod
    def build_hard_stop_plan(
        user_prompt: str,
        reason_code: str,
        existing: Optional[ActionPlanResponse] = None,
    ) -> ActionPlanResponse:
        explanation = SafetyService.SAFE_HARBOR_MESSAGES.get(
            reason_code, SafetyService.SAFE_HARBOR_MESSAGES["generic"]
        )
        logger.warning(
            "Safety HARD_STOP issued reason=%s prompt_preview=%s",
            reason_code,
            user_prompt[:80],
        )
        return ActionPlanResponse(
            plan_id=existing.plan_id if existing else "plan_hard_stop",
            user_goal=user_prompt,
            risk_level=RiskLevel.RED,
            total_steps=1,
            current_step_index=0,
            is_completed=True,
            speech_response=explanation,
            error=HARD_STOP_ERROR,
            execution_state="ERROR",
            approach="SAFETY_INTERCEPT",
            steps=[
                PlanStep(
                    step_id=1,
                    action=ActionType.HARD_STOP,
                    target_node=None,
                    target_text=reason_code,
                    input_data=None,
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    requires_biometric=False,
                    reason=explanation,
                    execution_mode="ACCESSIBILITY",
                )
            ],
        )

    @staticmethod
    def intercept_plan(plan: ActionPlanResponse, user_prompt: str) -> Tuple[ActionPlanResponse, bool]:
        """Mandatory checkpoint between planner output and Gateway dispatch."""
        reason = SafetyService.classify_red_reason(user_prompt, plan)
        has_destructive_step = any(SafetyService._step_is_destructive(step) for step in plan.steps)

        # Only trigger HARD_STOP for truly destructive or unauthorized actions
        if reason in ["destructive", "unauthorized"] or has_destructive_step:
            reason_code = reason or "destructive"
            logger.info(
                "Pre-execution safety intercept issued HARD_STOP plan_id=%s reason=%s",
                plan.plan_id,
                reason_code,
            )
            return SafetyService.build_hard_stop_plan(user_prompt, reason_code, plan), True

        # For financial or RED risk items, enforce biometric check flag on step instead of hard stopping
        if plan.risk_level == RiskLevel.RED or reason == "financial":
            for step in plan.steps:
                if step.action in [ActionType.CLICK, ActionType.CONFIRM_BIOMETRIC]:
                    step.requires_biometric = True

        logger.info("Pre-execution safety intercept passed plan_id=%s risk=%s", plan.plan_id, plan.risk_level)
        return plan, False