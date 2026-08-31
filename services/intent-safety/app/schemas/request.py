from enum import Enum
from typing import Any, List, Optional
from pydantic import AliasChoices, BaseModel, Field


class InputType(str, Enum):
    TEXT = "TEXT"
    AUDIO = "AUDIO"


class IntentRequest(BaseModel):
    task_id: Optional[str] = Field(None, description="Client task ID used to track multi-step execution")
    message_type: str = Field(default="USER_INTENT", description="USER_INTENT or USER_INTENT_FEEDBACK")
    action_status: str = Field(
        default="INITIAL",
        validation_alias=AliasChoices("action_status", "actionStatus"),
        description="Result reported by Android for the previous step",
    )
    status: Optional[str] = Field(None, description="Detailed Android execution status, such as PACKAGE_NOT_FOUND")
    target_action: Optional[str] = Field(None, description="Action that produced the feedback")
    target_node: Optional[str] = Field(None, description="Exact target used by the action")
    failure_reason: Optional[str] = Field(None, description="Detailed on-device execution failure reason")
    execution_attempt: int = Field(default=0, ge=0, description="Consecutive attempt number for this exact action target")
    user_id: str = Field(..., description="Unique ID of the user requesting execution", example="usr_10293")
    input_type: InputType = Field(default=InputType.TEXT, description="Format of incoming payload")
    text_prompt: Optional[str] = Field(None, description="Raw text command from user", example="Send $50 to Alice on Venmo")
    audio_base64: Optional[str] = Field(None, description="Base64 encoded audio string if input_type is AUDIO")
    current_app_package: Optional[str] = Field(
        None,
        validation_alias=AliasChoices("current_app_package", "current_package"),
        description="Active foreground app package ID",
        example="com.venmo",
    )
    screen_state: List[dict[str, Any]] = Field(
        default_factory=list,
        validation_alias=AliasChoices("screen_state", "screenElements"),
        description="Visible Android accessibility nodes",
    )
    action_history: List[dict[str, Any]] = Field(
        default_factory=list,
        validation_alias=AliasChoices("action_history", "actionHistory"),
        description="Executed actions for this task, ordered oldest to newest",
    )

    model_config = {
        "json_schema_extra": {
            "example": {
                "user_id": "usr_9981",
                "input_type": "TEXT",
                "text_prompt": "Send $50 to Alice on Venmo",
                "current_app_package": "com.venmo"
            }
        }
    }
