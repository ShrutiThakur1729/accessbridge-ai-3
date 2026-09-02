"""HTTP client for Service 03 Vision-Perception Engine."""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

import httpx

from app.config import settings

logger = logging.getLogger("intent-safety.vision")


class VisionClient:
    def __init__(self, base_url: Optional[str] = None, timeout: float = 25.0):
        self.base_url = (base_url or settings.VISION_PERCEPTION_URL).rstrip("/")
        self.timeout = timeout

    async def analyze(
        self,
        screenshot_base64: str,
        task_id: Optional[str] = None,
        query_text: Optional[str] = None,
    ) -> Optional[Dict[str, Any]]:
        if not screenshot_base64 or not self.base_url:
            return None

        url = f"{self.base_url}/analyze"
        payload = {
            "task_id": task_id,
            "query_text": query_text,
            "screenshot_base64": screenshot_base64,
            "image_base64": screenshot_base64,
        }
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                logger.info("Calling Service 03 vision analyze url=%s task_id=%s", url, task_id)
                response = await client.post(url, json=payload)
            if response.status_code >= 400:
                logger.warning(
                    "Service 03 returned %s: %s",
                    response.status_code,
                    response.text[:300],
                )
                return None
            data = response.json()
            logger.info(
                "Service 03 returned %s elements engine=%s task_id=%s",
                len(data.get("elements") or []),
                data.get("engine"),
                task_id,
            )
            return data
        except Exception as exc:
            logger.warning("Service 03 unreachable: %s", exc)
            return None

    @staticmethod
    def to_screen_state(vision_payload: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
        if not vision_payload:
            return []
        nodes = vision_payload.get("screen_state")
        if isinstance(nodes, list) and nodes:
            return nodes
        elements = vision_payload.get("elements") or []
        mapped: List[Dict[str, Any]] = []
        for index, element in enumerate(elements, start=1):
            mapped.append(
                {
                    "text": element.get("ocr_text") or element.get("label") or f"control_{index}",
                    "content_description": element.get("label") or "",
                    "view_id": element.get("element_id") or f"vis_{index}",
                    "class_name": "vision.DetectedControl",
                    "is_clickable": True,
                    "source": "vision",
                    "tap_x": element.get("tap_x"),
                    "tap_y": element.get("tap_y"),
                    "bounds": None,
                    "confidence": element.get("confidence"),
                    "matched": element.get("element_id") == vision_payload.get("matched_element_id"),
                }
            )
        return mapped


vision_client = VisionClient()
