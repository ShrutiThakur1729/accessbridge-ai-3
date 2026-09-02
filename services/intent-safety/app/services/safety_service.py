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
    YELLOW: Drafting text, non-critical updates, filling fields
    RED: Financial transfers, unauthorized access, destructive changes
    """

    RED_KEYWORDS = [
        "pay",
        "send money",
        "transfer",
        "withdraw",
        "delete",
        "remove account",
        "password",
        "pin",
        "transaction",
        "buy",
        "factory reset",
        "wipe data",
        "uninstall",
        "format phone",
        "disable accessibility",
        "grant all permissions",
        "bypass lock",
        "root device",
        "hack",
        "unauthorized",
    ]

    YELLOW_KEYWORDS = [
        "type",
        "draft",
        "message",
        "write",
        "search for",
        "update profile",
    ]

    DESTRUCTIVE_STEP_MARKERS = [
        "delete",
        "uninstall",
        "wipe",
        "factory",
        "reset",
        "remove account",
        "grant",
        "permission",
        "password",
        "pin",
        "pay",
        "transfer",
        "confirm payment",
        "buy",
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
        if plan:
            for step in plan.steps:
                haystack += " " + " ".join(
                    filter(
                        None,
                        [
                            step.action.value if step.action else "",
                            step.target_node,
                            step.target_text,
                            step.reason,
                            step.input_data,
                        ],
                    )
                ).lower()

        if re.search(r"\b(pay|send money|transfer|withdraw|transaction|buy|purchase)\b", haystack):
            return "financial"
        if re.search(r"\b(delete|uninstall|wipe|factory reset|remove account|format)\b", haystack):
            return "destructive"
        if re.search(r"\b(hack|bypass|unauthorized|root|grant all permissions|disable accessibility|password|pin)\b", haystack):
            return "unauthorized"
        if SafetyService.evaluate_risk(user_prompt) == RiskLevel.RED:
            return "generic"
        return None

    @staticmethod
    def _step_is_red(step: PlanStep) -> bool:
        blob = " ".join(
            filter(None, [step.action.value, step.target_node, step.target_text, step.input_data, step.reason])
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
        step_hit = any(SafetyService._step_is_red(step) for step in plan.steps)
        if plan.risk_level == RiskLevel.RED or reason or step_hit:
            reason_code = reason or "generic"
            logger.info(
                "Pre-execution safety intercept blocked plan_id=%s reason=%s step_hit=%s",
                plan.plan_id,
                reason_code,
                step_hit,
            )
            return SafetyService.build_hard_stop_plan(user_prompt, reason_code, plan), True

        logger.info("Pre-execution safety intercept passed plan_id=%s risk=%s", plan.plan_id, plan.risk_level)
        return plan, False
