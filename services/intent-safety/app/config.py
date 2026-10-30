import os
from functools import lru_cache
from typing import Any
from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    APP_NAME: str = "Service 02"
    APP_ENV: str = "development"
    DEBUG: bool = True
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    
    # API Configurations
    API_PREFIX: str = "/api/v1"
    
    # LLM Configuration fields
    GROQ_API_KEY: str = ""
    DEFAULT_LLM_MODEL: str = "llama3.1:8b"
    EXTRACTION_MODEL: str = "llama3.2:3b"
    CLASSIFY_MODEL: str = "llama3.2:3b"
    USE_LLM_MOCK: bool = False
    VISION_PERCEPTION_URL: str = "http://127.0.0.1:8010/api/v1"
    
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore"
    )

    @field_validator("DEBUG", mode="before")
    @classmethod
    def parse_debug(cls, v: Any) -> bool:
        if isinstance(v, bool):
            return v
        if isinstance(v, str):
            normalized = v.strip().lower()
            if normalized in ("true", "1", "yes", "debug", "dev", "development"):
                return True
            if normalized in ("false", "0", "no", "release", "prod", "production"):
                return False
        return bool(v)


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
