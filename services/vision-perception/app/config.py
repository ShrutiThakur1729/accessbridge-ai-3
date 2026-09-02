from functools import lru_cache
from typing import Any

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    APP_NAME: str = "Service 03 - Vision-Perception Engine"
    APP_ENV: str = "development"
    DEBUG: bool = True
    HOST: str = "0.0.0.0"
    PORT: int = 8010
    API_PREFIX: str = "/api/v1"

    ENABLE_YOLO: bool = True
    ENABLE_OCR: bool = True
    YOLO_MODEL: str = "yolov8n.pt"
    MAX_DETECTIONS: int = 40
    MIN_BOX_AREA_RATIO: float = 0.0008
    MAX_BOX_AREA_RATIO: float = 0.45

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore",
    )

    @field_validator("DEBUG", "ENABLE_YOLO", "ENABLE_OCR", mode="before")
    @classmethod
    def parse_bool(cls, v: Any) -> bool:
        if isinstance(v, bool):
            return v
        if isinstance(v, str):
            return v.strip().lower() in {"true", "1", "yes", "on"}
        return bool(v)


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
