from app.schemas.action_plan import RiskLevel


class SafetyService:
    """
    Evaluates risk levels based on keywords, intent actions, and target apps.
    - GREEN: Read-only, search, navigation, opening system tools
    - YELLOW: Drafting text, non-critical updates, filling fields
    - RED: Financial transfers, money requests, deletions, security settings
    """

    RED_KEYWORDS = [
        "pay", "send money", "transfer", "withdraw", "delete", 
        "remove account", "password", "pin", "transaction", "buy"
    ]
    
    YELLOW_KEYWORDS = [
        "type", "draft", "message", "write", "search for", "update profile"
    ]

    @staticmethod
    def evaluate_risk(user_prompt: str) -> RiskLevel:
        prompt_lower = user_prompt.lower()

        # Check for high-consequence risk markers
        for keyword in SafetyService.RED_KEYWORDS:
            if keyword in prompt_lower:
                return RiskLevel.RED

        # Check for moderate-consequence risk markers
        for keyword in SafetyService.YELLOW_KEYWORDS:
            if keyword in prompt_lower:
                return RiskLevel.YELLOW

        # Default to safe read-only operations
        return RiskLevel.GREEN

    @staticmethod
    def requires_biometric_check(risk_level: RiskLevel) -> bool:
        # Enforce physical biometric prompt if risk is RED
        return risk_level == RiskLevel.RED