from typing import List, Optional

from pydantic import BaseModel, Field


class BoundingBox(BaseModel):
    x1: float = Field(..., description="Left edge in pixels")
    y1: float = Field(..., description="Top edge in pixels")
    x2: float = Field(..., description="Right edge in pixels")
    y2: float = Field(..., description="Bottom edge in pixels")
    nx1: float = Field(..., ge=0, le=1, description="Normalized left (0-1)")
    ny1: float = Field(..., ge=0, le=1, description="Normalized top (0-1)")
    nx2: float = Field(..., ge=0, le=1, description="Normalized right (0-1)")
    ny2: float = Field(..., ge=0, le=1, description="Normalized bottom (0-1)")


class VisionElement(BaseModel):
    element_id: str
    label: str
    ocr_text: str = ""
    role: str = "interactive"
    confidence: float = 0.0
    source: str = "opencv"
    tap_x: float = Field(..., ge=0, le=1, description="Normalized tap X")
    tap_y: float = Field(..., ge=0, le=1, description="Normalized tap Y")
    pixel_x: int
    pixel_y: int
    bounds: BoundingBox


class VisionAnalyzeRequest(BaseModel):
    task_id: Optional[str] = None
    query_text: Optional[str] = Field(
        default=None,
        description="User intent used to rank detections that match the requested target",
    )
    image_base64: Optional[str] = None
    screenshot_base64: Optional[str] = None


class VisionAnalyzeResponse(BaseModel):
    task_id: Optional[str] = None
    image_width: int
    image_height: int
    engine: str
    query_text: Optional[str] = None
    matched_element_id: Optional[str] = None
    elements: List[VisionElement]
    screen_state: List[dict] = Field(
        default_factory=list,
        description="Planner-compatible synthetic DOM nodes with tap coordinates",
    )
    error: Optional[str] = None
