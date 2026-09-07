from enum import Enum
from typing import List, Optional, Any, Dict
from pydantic import BaseModel, Field, ConfigDict, field_validator


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
    GO_HOME = "GO_HOME"
    GO_HOME_AND_SEARCH = "GO_HOME_AND_SEARCH"
    SWIPE_UP = "SWIPE_UP"
    SWIPE_HORIZONTAL = "SWIPE_HORIZONTAL"
    PAUSE_FOR_USER_INPUT = "PAUSE_FOR_USER_INPUT"
    USER_TAKE_OVER = "USER_TAKE_OVER"
    FINISH = "FINISH"
    HARD_STOP = "HARD_STOP"


class WaterfallTier(int, Enum):
    TIER_1_INTENT = 1       # Android Native Intent
    TIER_2_DEEPLINK = 2     # URL / App Deep Link
    TIER_3_ACCESSIBILITY = 3 # On-Device Accessibility Node Traversal
    TIER_4_VISION_OCR = 4   # Visual Fallback via Service 03


class PlanStep(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    step_id: int = Field(default=1, description="1-indexed step number", example=1)
    action: ActionType = Field(default=ActionType.FINISH, description="Primitive action type to execute")
    target_node: Optional[str] = Field(None, description="UI Element ID, Text label, or OCR region", example="btn_send")
    input_data: Optional[str] = Field(None, description="Text value to type if action is TYPE_TEXT", example="50")
    tier: WaterfallTier = Field(default=WaterfallTier.TIER_3_ACCESSIBILITY, description="Assigned Waterfall Tier")
    requires_biometric: bool = Field(default=False, description="True if this specific step requires fingerprint auth")
    
    action_type: Optional[ActionType] = Field(None, description="Compatibility action name for direct executors")
    package_name: Optional[str] = Field(None, description="Resolved package for OPEN_APP")
    fallback_package_name: Optional[str] = Field(None, description="Optional alternate package for OPEN_APP")
    target_text: Optional[str] = Field(None, description="Human-readable accessibility target")
    scroll_direction: Optional[str] = Field(None, description="Direction for SCROLL directives")
    reason: Optional[str] = Field(None, description="Planner rationale for a recovery action")
    execution_mode: Optional[str] = Field(None, description="SYSTEM_INTENT, ACCESSIBILITY, or COORDINATE")
    tap_x: Optional[float] = Field(None, description="Normalized 0-1 X coordinate from Service 03")
    tap_y: Optional[float] = Field(None, description="Normalized 0-1 Y coordinate from Service 03")

    @field_validator("action", mode="before")
    @classmethod
    def validate_action(cls, v: Any) -> ActionType:
        if isinstance(v, str):
            v_upper = v.upper()
            if v_upper in ActionType.__members__:
                return ActionType[v_upper]
        return v if isinstance(v, ActionType) else ActionType.FINISH

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
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    plan_id: str = Field(default="plan_default", description="Unique ID for tracking execution telemetry", example="plan_8871a")
    session_id: Optional[str] = Field(None, description="Persistent conversation session ID across multi-turn tasks")
    user_goal: str = Field(default="Process user request", description="Original or transcribed intent summary", example="Transfer money to Alice")
    risk_level: RiskLevel = Field(default=RiskLevel.GREEN, description="Safety classifier score for the entire task")
    total_steps: int = Field(default=0, description="Number of execution steps")
    steps: List[PlanStep] = Field(default_factory=list, description="Ordered list of steps to execute")
    current_step_index: int = Field(default=0, description="Zero-indexed step currently ready for dispatch")
    speech_response: Optional[str] = Field(None, description="User-facing execution or recovery message")
    is_completed: Optional[bool] = Field(default=None, description="Planner-declared terminal state")
    error: Optional[str] = Field(default=None, description="Machine-readable non-terminal planner error")
    session_language: Optional[str] = Field(default=None, description="BCP-47 language for the client TTS response")
    execution_state: Optional[str] = Field(default=None, description="Planner state-machine state")
    requires_confirmation: Optional[bool] = Field(default=None, description="True if turn requires verbal user confirmation")
    approach: Optional[str] = Field(default=None, description="LOCAL_RESOLUTION or SERVER_REPLANNING")
    context_mode: str = Field(
        default="DEFAULT",
        description="Active UI context, such as DEFAULT, IN_DIALOG_PICKER, FORM_FILLING, or SEARCH_ACTIVE",
    )
    thought_process: Optional[str] = Field(None, description="Cognitive reasoning trace from LLM execution")
    updated_session_state: Optional[Dict[str, Any]] = Field(None, description="Updated session variables and parameters")
    conversation_history: List[Dict[str, str]] = Field(
        default_factory=list,
        description="Rolling window of recent conversation turns in session memory"
    )

    @field_validator("risk_level", mode="before")
    @classmethod
    def validate_risk_level(cls, v: Any) -> RiskLevel:
        if isinstance(v, str):
            v_upper = v.upper()
            if v_upper in RiskLevel.__members__:
                return RiskLevel[v_upper]
        return v if isinstance(v, RiskLevel) else RiskLevel.GREEN