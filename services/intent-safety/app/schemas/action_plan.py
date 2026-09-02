from enum import Enum
from typing import List, Optional, Any
from pydantic import BaseModel, Field, ConfigDict


class RiskLevel(str, Enum):
    GREEN = "GREEN"    # Read-only operations, searching, navigation
    YELLOW = "YELLOW"  # Drafting text, setting timers, non-critical state changes
    RED = "RED"        # Financial transactions, settings changes, auth/delete operations


class ActionType(str, Enum):
    OPEN_APP = "OPEN_APP"
    CLICK = "CLICK"
    TYPE_TEXT = "TYPE_TEXT"
    SCROLL = "SCROLL"
    CONFIRM_BIOMETRIC = "CONFIRM_BIOMETRIC"
    SYSTEM_BACK = "SYSTEM_BACK"
    GO_HOME_AND_SEARCH = "GO_HOME_AND_SEARCH"
    SWIPE_UP = "SWIPE_UP"
    SWIPE_HORIZONTAL = "SWIPE_HORIZONTAL"
    FINISH = "FINISH"
    HARD_STOP = "HARD_STOP"


class WaterfallTier(int, Enum):
    TIER_1_INTENT = 1       # Android Native Intent
    TIER_2_DEEPLINK = 2     # URL / App Deep Link
    TIER_3_ACCESSIBILITY = 3 # On-Device Accessibility Node Traversal
    TIER_4_VISION_OCR = 4   # Visual Fallback via Service 03


class PlanStep(BaseModel):
    model_config = ConfigDict(extra="forbid")
    step_id: int = Field(..., description="1-indexed step number", example=1)
    action: ActionType = Field(..., description="Primitive action type to execute")
    target_node: Optional[str] = Field(None, description="UI Element ID, Text label, or OCR region", example="btn_send")
    input_data: Optional[str] = Field(None, description="Text value to type if action is TYPE_TEXT", example="50")
    tier: WaterfallTier = Field(default=WaterfallTier.TIER_3_ACCESSIBILITY, description="Assigned Waterfall Tier")
    requires_biometric: bool = Field(default=False, description="True if this specific step requires fingerprint auth")
    # Additive protocol fields. `action` and `target_node` remain authoritative for
    # existing Gateway/WebSocket clients.
    action_type: ActionType = Field(None, description="Compatibility action name for direct executors")
    package_name: Optional[str] = Field(None, description="Resolved package for OPEN_APP")
    fallback_package_name: Optional[str] = Field(None, description="Optional alternate package for OPEN_APP")
    target_text: Optional[str] = Field(None, description="Human-readable accessibility target")
    scroll_direction: Optional[str] = Field(None, description="Direction for SCROLL directives")
    reason: Optional[str] = Field(None, description="Planner rationale for a recovery action")
    execution_mode: Optional[str] = Field(None, description="SYSTEM_INTENT, ACCESSIBILITY, or COORDINATE")
    tap_x: Optional[float] = Field(None, description="Normalized 0-1 X coordinate from Service 03")
    tap_y: Optional[float] = Field(None, description="Normalized 0-1 Y coordinate from Service 03")

    def model_post_init(self, __context: Any) -> None:
        if self.action_type is None:
            self.action_type = self.action
        if self.target_text is None:
            self.target_text = self.target_node
        if self.action == ActionType.OPEN_APP and self.package_name is None:
            self.package_name = self.target_node
        if self.execution_mode is None:
            if self.action == ActionType.OPEN_APP:
                self.execution_mode = "SYSTEM_INTENT"
            elif self.tap_x is not None and self.tap_y is not None:
                self.execution_mode = "COORDINATE"
            else:
                self.execution_mode = "ACCESSIBILITY"


class ActionPlanResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    plan_id: str = Field(..., description="Unique ID for tracking execution telemetry", example="plan_8871a")
    user_goal: str = Field(..., description="Original or transcribed intent summary", example="Transfer money to Alice")
    risk_level: RiskLevel = Field(..., description="Safety classifier score for the entire task")
    total_steps: int = Field(..., description="Number of execution steps")
    steps: List[PlanStep] = Field(..., description="Ordered list of steps for Service 04 to execute")
    current_step_index: int = Field(default=0, description="Zero-indexed step currently ready for dispatch")
    speech_response: Optional[str] = Field(None, description="User-facing execution or recovery message")
    is_completed: Optional[bool] = Field(default=None, description="Planner-declared terminal state")
    error: Optional[str] = Field(default=None, description="Machine-readable non-terminal planner error")
    session_language: Optional[str] = Field(default=None, description="BCP-47 language for the client TTS response")
    execution_state: Optional[str] = Field(default=None, description="Planner state-machine state")
    approach: Optional[str] = Field(default=None, description="LOCAL_RESOLUTION or SERVER_REPLANNING")

    model_config = {
        "json_schema_extra": {
            "example": {
                "plan_id": "plan_9901a",
                "user_goal": "Send $50 to Alice on Venmo",
                "risk_level": "RED",
                "total_steps": 2,
                "steps": [
                    {
                        "step_id": 1,
                        "action": "TYPE_TEXT",
                        "target_node": "amount_input",
                        "input_data": "50",
                        "tier": 3,
                        "requires_biometric": False
                    },
                    {
                        "step_id": 2,
                        "action": "CONFIRM_BIOMETRIC",
                        "target_node": "pay_button",
                        "input_data": None,
                        "tier": 3,
                        "requires_biometric": True
                    }
                ]
            }
        }
    }
