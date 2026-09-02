from __future__ import annotations

import base64
import logging
import re
import threading
from typing import Any, Dict, List, Optional, Tuple

import cv2
import numpy as np
from PIL import Image
from io import BytesIO

from app.config import settings
from app.schemas import BoundingBox, VisionAnalyzeResponse, VisionElement

logger = logging.getLogger("vision-perception")

_yolo_lock = threading.Lock()
_ocr_lock = threading.Lock()
_yolo_model = None
_ocr_reader = None
_yolo_failed = False
_ocr_failed = False


def _decode_image(image_bytes: bytes) -> np.ndarray:
    array = np.frombuffer(image_bytes, dtype=np.uint8)
    image = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if image is None:
        pil = Image.open(BytesIO(image_bytes)).convert("RGB")
        image = cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)
    return image


def decode_base64_image(raw: str) -> np.ndarray:
    payload = raw.strip()
    if payload.startswith("data:"):
        payload = payload.split(",", 1)[-1]
    try:
        image_bytes = base64.b64decode(payload, validate=False)
    except Exception as exc:
        raise ValueError("Screenshot payload is not valid base64") from exc
    if not image_bytes:
        raise ValueError("Screenshot payload is empty")
    return _decode_image(image_bytes)


def _clip01(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def _box_from_xyxy(x1: float, y1: float, x2: float, y2: float, width: int, height: int) -> BoundingBox:
    x1 = max(0.0, min(float(x1), width - 1))
    y1 = max(0.0, min(float(y1), height - 1))
    x2 = max(x1 + 1.0, min(float(x2), float(width)))
    y2 = max(y1 + 1.0, min(float(y2), float(height)))
    return BoundingBox(
        x1=x1,
        y1=y1,
        x2=x2,
        y2=y2,
        nx1=_clip01(x1 / width),
        ny1=_clip01(y1 / height),
        nx2=_clip01(x2 / width),
        ny2=_clip01(y2 / height),
    )


def _iou(a: Tuple[float, float, float, float], b: Tuple[float, float, float, float]) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    if inter <= 0:
        return 0.0
    area_a = max(1.0, (ax2 - ax1) * (ay2 - ay1))
    area_b = max(1.0, (bx2 - bx1) * (by2 - by1))
    return inter / (area_a + area_b - inter)


def _get_yolo():
    global _yolo_model, _yolo_failed
    if not settings.ENABLE_YOLO or _yolo_failed:
        return None
    with _yolo_lock:
        if _yolo_model is not None or _yolo_failed:
            return _yolo_model
        try:
            from ultralytics import YOLO

            logger.info("Loading YOLO model %s", settings.YOLO_MODEL)
            _yolo_model = YOLO(settings.YOLO_MODEL)
        except Exception as exc:
            _yolo_failed = True
            logger.warning("YOLO unavailable, using OpenCV UI contours only: %s", exc)
            _yolo_model = None
        return _yolo_model


def _get_ocr():
    global _ocr_reader, _ocr_failed
    if not settings.ENABLE_OCR or _ocr_failed:
        return None
    with _ocr_lock:
        if _ocr_reader is not None or _ocr_failed:
            return _ocr_reader
        try:
            import easyocr

            logger.info("Loading EasyOCR reader")
            _ocr_reader = easyocr.Reader(["en"], gpu=False, verbose=False)
        except Exception as exc:
            _ocr_failed = True
            logger.warning("OCR engine unavailable, returning geometry-only detections: %s", exc)
            _ocr_reader = None
        return _ocr_reader


def _detect_ui_contours(image: np.ndarray) -> List[Dict[str, Any]]:
    height, width = image.shape[:2]
    min_area = width * height * settings.MIN_BOX_AREA_RATIO
    max_area = width * height * settings.MAX_BOX_AREA_RATIO

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, 50, 150)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 3))
    closed = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel, iterations=2)

    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    detections: List[Dict[str, Any]] = []
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        area = w * h
        if area < min_area or area > max_area:
            continue
        aspect = w / max(h, 1)
        if aspect < 0.2 or aspect > 12:
            continue
        detections.append(
            {
                "xyxy": (float(x), float(y), float(x + w), float(y + h)),
                "confidence": 0.55,
                "label": "ui_control",
                "source": "opencv",
                "role": "interactive",
            }
        )
    return detections


def _detect_yolo(image: np.ndarray) -> List[Dict[str, Any]]:
    model = _get_yolo()
    if model is None:
        return []
    try:
        results = model.predict(image, verbose=False, conf=0.25, imgsz=640)
    except Exception as exc:
        logger.warning("YOLO inference failed: %s", exc)
        return []

    detections: List[Dict[str, Any]] = []
    for result in results:
        names = result.names or {}
        boxes = getattr(result, "boxes", None)
        if boxes is None:
            continue
        for box in boxes:
            xyxy = box.xyxy[0].tolist()
            cls_id = int(box.cls[0]) if box.cls is not None else 0
            detections.append(
                {
                    "xyxy": (float(xyxy[0]), float(xyxy[1]), float(xyxy[2]), float(xyxy[3])),
                    "confidence": float(box.conf[0]) if box.conf is not None else 0.4,
                    "label": str(names.get(cls_id, "object")),
                    "source": "yolo",
                    "role": "object",
                }
            )
    return detections


def _detect_ocr(image: np.ndarray) -> List[Dict[str, Any]]:
    reader = _get_ocr()
    if reader is None:
        return []
    try:
        rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        rows = reader.readtext(rgb)
    except Exception as exc:
        logger.warning("OCR inference failed: %s", exc)
        return []

    detections: List[Dict[str, Any]] = []
    for item in rows:
        if len(item) < 3:
            continue
        points, text, confidence = item[0], str(item[1]).strip(), float(item[2])
        if not text or confidence < 0.25:
            continue
        xs = [p[0] for p in points]
        ys = [p[1] for p in points]
        detections.append(
            {
                "xyxy": (float(min(xs)), float(min(ys)), float(max(xs)), float(max(ys))),
                "confidence": confidence,
                "label": text,
                "ocr_text": text,
                "source": "ocr",
                "role": "text",
            }
        )
    return detections


def _merge_detections(groups: List[List[Dict[str, Any]]]) -> List[Dict[str, Any]]:
    merged: List[Dict[str, Any]] = []
    for group in groups:
        for detection in group:
            box = detection["xyxy"]
            overlap_index = next((i for i, existing in enumerate(merged) if _iou(existing["xyxy"], box) >= 0.45), None)
            if overlap_index is None:
                merged.append(detection)
                continue
            current = merged[overlap_index]
            ocr_text = detection.get("ocr_text") or current.get("ocr_text") or ""
            label = detection.get("ocr_text") or current.get("ocr_text") or current.get("label") or detection.get("label")
            current.update(
                {
                    "confidence": max(current.get("confidence", 0), detection.get("confidence", 0)),
                    "label": label,
                    "ocr_text": ocr_text,
                    "source": "+".join(sorted({current.get("source", ""), detection.get("source", "")} - {""})),
                    "role": "interactive" if "opencv" in (current.get("source", "") + detection.get("source", "")) else current.get("role", "interactive"),
                }
            )
            if detection.get("confidence", 0) > current.get("box_confidence", current.get("confidence", 0)):
                current["xyxy"] = box
    return merged


def _rank_query_match(detections: List[Dict[str, Any]], query_text: Optional[str]) -> Optional[str]:
    if not query_text:
        return None
    tokens = [token for token in re.findall(r"[a-z0-9]+", query_text.lower()) if len(token) > 2]
    if not tokens:
        return None
    best_id = None
    best_score = 0
    for index, detection in enumerate(detections):
        haystack = f"{detection.get('label', '')} {detection.get('ocr_text', '')}".lower()
        score = sum(1 for token in tokens if token in haystack)
        if score > best_score:
            best_score = score
            best_id = f"vis_{index + 1}"
    return best_id if best_score > 0 else None


def analyze_image(image: np.ndarray, task_id: Optional[str] = None, query_text: Optional[str] = None) -> VisionAnalyzeResponse:
    height, width = image.shape[:2]
    opencv_boxes = _detect_ui_contours(image)
    yolo_boxes = _detect_yolo(image)
    ocr_boxes = _detect_ocr(image)
    merged = _merge_detections([ocr_boxes, opencv_boxes, yolo_boxes])
    merged.sort(key=lambda item: item.get("confidence", 0), reverse=True)
    merged = merged[: settings.MAX_DETECTIONS]

    engines = []
    if opencv_boxes:
        engines.append("opencv")
    if yolo_boxes:
        engines.append("yolo")
    if ocr_boxes:
        engines.append("ocr")

    matched = _rank_query_match(merged, query_text)
    elements: List[VisionElement] = []
    screen_state: List[dict] = []
    for index, detection in enumerate(merged, start=1):
        x1, y1, x2, y2 = detection["xyxy"]
        bounds = _box_from_xyxy(x1, y1, x2, y2, width, height)
        tap_x = _clip01((bounds.nx1 + bounds.nx2) / 2)
        tap_y = _clip01((bounds.ny1 + bounds.ny2) / 2)
        ocr_text = str(detection.get("ocr_text") or "").strip()
        label = str(detection.get("label") or ocr_text or f"control_{index}")
        element_id = f"vis_{index}"
        element = VisionElement(
            element_id=element_id,
            label=label,
            ocr_text=ocr_text,
            role=str(detection.get("role") or "interactive"),
            confidence=float(detection.get("confidence") or 0),
            source=str(detection.get("source") or "opencv"),
            tap_x=tap_x,
            tap_y=tap_y,
            pixel_x=int(round((bounds.x1 + bounds.x2) / 2)),
            pixel_y=int(round((bounds.y1 + bounds.y2) / 2)),
            bounds=bounds,
        )
        elements.append(element)
        screen_state.append(
            {
                "text": ocr_text or label,
                "content_description": label,
                "view_id": element_id,
                "class_name": "vision.DetectedControl",
                "is_clickable": True,
                "is_editable": False,
                "source": "vision",
                "tap_x": tap_x,
                "tap_y": tap_y,
                "pixel_x": element.pixel_x,
                "pixel_y": element.pixel_y,
                "bounds": f"{int(bounds.x1)},{int(bounds.y1)},{int(bounds.x2)},{int(bounds.y2)}",
                "confidence": element.confidence,
                "matched": element_id == matched,
            }
        )

    logger.info(
        "Vision analysis complete task_id=%s size=%sx%s detections=%s engines=%s matched=%s",
        task_id,
        width,
        height,
        len(elements),
        "+".join(engines) or "none",
        matched,
    )
    return VisionAnalyzeResponse(
        task_id=task_id,
        image_width=width,
        image_height=height,
        engine="+".join(engines) or "none",
        query_text=query_text,
        matched_element_id=matched,
        elements=elements,
        screen_state=screen_state,
    )
