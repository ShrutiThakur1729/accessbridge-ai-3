import json
import re
from typing import Optional

import httpx
from pydantic import BaseModel

from app.config import settings

OLLAMA_CHAT_URL = "http://localhost:11434/api/chat"

SYSTEM_PROMPT = """You parse a voice command for a phone assistant.
Reply with ONLY JSON. Include only the fields that apply:
app_name, kind (SEARCH|MESSAGE|RIDE), query, recipient, message, destination, task
- app_name: the app named in the command, copied exactly as spoken. Omit if none.
- kind: SEARCH (find/order/play something), MESSAGE (send a message), RIDE (cab/auto/ride). Omit otherwise.
- Copy query, recipient, message, destination word for word. Never invent words.
- task: the command without the app name. "" if the user only wants to open the app.

Command: "order milk on zepto" -> {"app_name":"zepto","kind":"SEARCH","query":"milk","task":"order milk"}
Command: "send a message to siddhi on whatsapp saying hello" -> {"app_name":"whatsapp","kind":"MESSAGE","recipient":"siddhi","message":"hello","task":"send a message to siddhi saying hello"}
Command: "book a cab to thane on uber" -> {"app_name":"uber","kind":"RIDE","destination":"thane","task":"book a cab to thane"}
Command: "open face book" -> {"app_name":"face book","task":""}
Command: "click search" -> {"task":"click search"}"""
KINDS = {"SEARCH", "MESSAGE", "RIDE"}


class ExtractResult(BaseModel):
    ok: bool
    app_name: Optional[str] = None
    task: str = ""
    kind: Optional[str] = None
    query: Optional[str] = None
    recipient: Optional[str] = None
    message: Optional[str] = None
    destination: Optional[str] = None
    reason: Optional[str] = None


def _field(value, transcript_words):
    """A field is kept only if all its words were really spoken."""
    if not isinstance(value, str) or not value.strip():
        return None
    return value.strip() if _words(value) <= transcript_words else None


def _words(text: str) -> set:
    return set(re.findall(r"[a-z0-9]+", text.lower()))


async def extract_app(transcript: str) -> ExtractResult:
    body = {
        "model": settings.EXTRACTION_MODEL,
        "stream": False,
        "format": "json",
        "keep_alive": "1h",
        "options": {"temperature": 0, "num_predict": 120, "num_ctx": 2048},
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f'Command: "{transcript}"'},
        ],
    }
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(OLLAMA_CHAT_URL, json=body)
            resp.raise_for_status()
        payload = resp.json()
        data = json.loads(resp.json()["message"]["content"])
    except httpx.HTTPError as e:
        return ExtractResult(ok=False, reason=f"LLM_UNAVAILABLE: {type(e).__name__}: {e}")
    except (KeyError, ValueError) as e:
        return ExtractResult(ok=False, reason=f"LLM_BAD_OUTPUT: {e}")

    if not isinstance(data, dict):
        return ExtractResult(ok=False, reason="LLM_BAD_OUTPUT: not an object")

    name = data.get("app_name")
    task = data.get("task")
    task = task.strip() if isinstance(task, str) else ""

    if not isinstance(name, str) or not name.strip():
        return ExtractResult(ok=True, app_name=None, task=task or transcript)

    name = name.strip()
    # Safety check: a real app name must come from what the user actually said
    if not _words(name) <= _words(transcript):
        return ExtractResult(ok=True, app_name=None, task=transcript, reason="NAME_NOT_IN_SENTENCE")

    tw = _words(transcript)
    kind = data.get("kind")
    kind = kind.upper() if isinstance(kind, str) and kind.upper() in KINDS else None
    return ExtractResult(
        ok=True, app_name=name, task=task, kind=kind,
        query=_field(data.get("query"), tw), recipient=_field(data.get("recipient"), tw),
        message=_field(data.get("message"), tw), destination=_field(data.get("destination"), tw),
    )