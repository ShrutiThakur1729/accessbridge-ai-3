from enum import Enum
from typing import Any, Dict, List, Optional
from pydantic import AliasChoices, BaseModel, ConfigDict, Field


class InputType(str, Enum):
    TEXT = "TEXT"
    AUDIO = "AUDIO"


class PlannerRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    task_id: Optional[str] = Field(
        default=None,
        description="Unique identifier for the task session",
        validation_alias=AliasChoices("task_id", "taskId"),
    )
    user_prompt: Optional[str] = Field(
        default=None,
        description="The raw or transcribed user command",
        validation_alias=AliasChoices("user_prompt", "text_prompt", "user_goal", "transcript", "original_intent"),
    )
    current_package_name: Optional[str] = Field(
        default=None,
        description="Active foreground application package identifier",
        validation_alias=AliasChoices("current_package_name", "current_app_package", "current_package", "target_package"),
    )
    active_input_focused: bool = Field(
        default=False,
        description="True if an editable search or text input currently holds UI focus",
        validation_alias=AliasChoices("active_input_focused", "activeInputFocused"),
    )
    focused_hint: Optional[str] = Field(
        default=None,
        description="Placeholder text or content description of the currently focused input element",
        validation_alias=AliasChoices("focused_hint", "focusedHint"),
    )
    compressed_elements: List[Dict[str, Any]] = Field(
        default_factory=list,
        description="Cleaned, pruned UI nodes for planner evaluation",
        validation_alias=AliasChoices("compressed_elements", "screen_state", "screenElements"),
    )
    action_status: str = Field(
        default="INITIAL",
        description="Status of previous action: INITIAL, SUCCESS, or FAILED",
        validation_alias=AliasChoices("action_status", "actionStatus"),
    )
    action_history: List[Dict[str, Any]] = Field(
        default_factory=list,
        description="Historical trace of executed actions for loop detection",
        validation_alias=AliasChoices("action_history", "actionHistory"),
    )
    message_type: str = Field(
        default="USER_INTENT",
        description="Protocol message type: USER_INTENT or USER_INTENT_FEEDBACK",
        validation_alias=AliasChoices("message_type", "type"),
    )
    status: Optional[str] = Field(
        default=None,
        description="Detailed Android execution status reason",
    )
    target_action: Optional[str] = Field(
        default=None,
        description="Last attempted action primitive",
        validation_alias=AliasChoices("target_action", "action", "action_type"),
    )
    target_node: Optional[str] = Field(
        default=None,
        description="Target node text/ID from the previous step",
    )
    failure_reason: Optional[str] = Field(
        default=None,
        description="Diagnostic explanation for on-device failure",
        validation_alias=AliasChoices("failure_reason", "error_message", "error"),
    )
    execution_attempt: int = Field(
        default=0,
        ge=0,
        description="Consecutive execution attempt counter",
        validation_alias=AliasChoices("execution_attempt", "executionAttempt"),
    )
    user_id: str = Field(
        default="default_user",
        description="ID of the user making the request",
        validation_alias=AliasChoices("user_id", "userId"),
    )
    input_type: str = Field(
        default="TEXT",
        description="Input modality: TEXT or AUDIO",
        validation_alias=AliasChoices("input_type", "inputType"),
    )
    audio_base64: Optional[str] = Field(
        default=None,
        description="Base64 encoded voice data if applicable",
    )

    @property
    def text_prompt(self) -> Optional[str]:
        return self.user_prompt

    @property
    def current_app_package(self) -> Optional[str]:
        return self.current_package_name

    @property
    def screen_state(self) -> List[Dict[str, Any]]:
        return self.compressed_elements


# Alias IntentRequest to PlannerRequest for full compatibility
IntentRequest = PlannerRequest

__all__ = ["InputType", "IntentRequest", "PlannerRequest"]
