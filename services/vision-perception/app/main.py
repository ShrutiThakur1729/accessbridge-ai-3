import logging
import time
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.schemas import VisionAnalyzeRequest, VisionAnalyzeResponse
from app.services.vision_service import analyze_image, decode_base64_image, _decode_image

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")
logger = logging.getLogger("vision-perception")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    logger.info(
        "Service 03 starting env=%s yolo=%s ocr=%s port=%s",
        settings.APP_ENV,
        settings.ENABLE_YOLO,
        settings.ENABLE_OCR,
        settings.PORT,
    )
    yield
    logger.info("Service 03 shutting down")


app = FastAPI(
    title=settings.APP_NAME,
    debug=settings.DEBUG,
    lifespan=lifespan,
    openapi_url=f"{settings.API_PREFIX}/openapi.json",
    docs_url=f"{settings.API_PREFIX}/docs",
    redoc_url=f"{settings.API_PREFIX}/redoc",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def log_inference_calls(request: Request, call_next):
    started = time.perf_counter()
    response = await call_next(request)
    logger.info(
        "HTTP %s %s status=%s duration_ms=%.1f",
        request.method,
        request.url.path,
        response.status_code,
        (time.perf_counter() - started) * 1000,
    )
    return response


@app.get("/health", tags=["Health Check"])
async def health_check():
    return {
        "status": "healthy",
        "service": settings.APP_NAME,
        "environment": settings.APP_ENV,
        "yolo_enabled": settings.ENABLE_YOLO,
        "ocr_enabled": settings.ENABLE_OCR,
    }


async def _analyze_from_bytes(
    image_bytes: bytes,
    task_id: Optional[str],
    query_text: Optional[str],
) -> VisionAnalyzeResponse:
    try:
        image = _decode_image(image_bytes)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Unable to decode screenshot: {exc}") from exc
    return analyze_image(image, task_id=task_id, query_text=query_text)


@app.post(
    f"{settings.API_PREFIX}/analyze",
    response_model=VisionAnalyzeResponse,
    tags=["Vision Perception"],
    summary="Analyze a screenshot for OCR text and UI tap coordinates",
)
async def analyze_json(request: VisionAnalyzeRequest) -> VisionAnalyzeResponse:
    raw = request.image_base64 or request.screenshot_base64
    if not raw:
        raise HTTPException(status_code=400, detail="image_base64 or screenshot_base64 is required")
    logger.info("Received JSON vision request task_id=%s", request.task_id)
    try:
        image = decode_base64_image(raw)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return analyze_image(image, task_id=request.task_id, query_text=request.query_text)


@app.post(
    f"{settings.API_PREFIX}/analyze-upload",
    response_model=VisionAnalyzeResponse,
    tags=["Vision Perception"],
    summary="Multipart screenshot upload for Gateway fallback routing",
)
async def analyze_upload(
    file: UploadFile = File(...),
    task_id: Optional[str] = Form(default=None),
    query_text: Optional[str] = Form(default=None),
) -> VisionAnalyzeResponse:
    logger.info("Received multipart vision request task_id=%s filename=%s", task_id, file.filename)
    image_bytes = await file.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="Uploaded screenshot is empty")
    return await _analyze_from_bytes(image_bytes, task_id, query_text)


@app.get(f"{settings.API_PREFIX}/health", tags=["Health Check"])
async def prefixed_health_check():
    return await health_check()
