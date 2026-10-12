import json
from typing import Optional

import httpx
from pydantic import BaseModel

from app.config import settings

OLLAMA_CHAT_URL = "http://localhost:11434/api/chat"
MAX_TEXT_CHARS = 300
VALID_ANSWERS = {"YES", "NO", "OTHER"}

SYSTEM_PROMPT = """You classify a user's spoken reply to a question a voice assistant just asked.
Reply with ONLY JSON: {"answer": "YES" or "NO" or "OTHER"}.
- YES: the user agrees or confirms.
- NO: the user declines, or says the suggestion is wrong.
- OTHER: the reply is unclear, unrelated, or asks for something new instead of answering.
Judge the reply in the context of the question.

Question: "Did you mean Telegram?" Reply: "yeah that one" -> {"answer":"YES"}
Question: "Did you mean Telegram?" Reply: "no that's not it" -> {"answer":"NO"}
Question: "Did you mean Telegram?" Reply: "open the camera" -> {"answer":"OTHER"}
Question: "Do you want me to install Zomato?" Reply: "sounds good" -> {"answer":"YES"}
Question: "Do you want me to install Zomato?" Reply: "never mind" -> {"answer":"NO"}"""


class ClassifyResult(BaseModel):
    ok: bool                    # False = the AI step itself failed
    answer: str = "OTHER"       # YES | NO | OTHER (only meaningful when ok=True)
    reason: Optional[str] = None


async def classify_reply(question: str, reply: str) -> ClassifyResult:
    question = question.strip()[:MAX_TEXT_CHARS]
    reply = reply.strip()[:MAX_TEXT_CHARS]
    if not reply:
        return ClassifyResult(ok=True, answer="OTHER", reason="EMPTY_REPLY")

    body = {
        "model": settings.CLASSIFY_MODEL,
        "stream": False,
        "format": "json",
        "keep_alive": "1h",
        "options": {"temperature": 0, "num_predict": 30, "num_ctx": 1024},
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f'Question: "{question}"\nReply: "{reply}"'},
        ],
    }
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(OLLAMA_CHAT_URL, json=body)
            resp.raise_for_status()
        data = json.loads(resp.json()["message"]["content"])
    except httpx.HTTPError as e:
        return ClassifyResult(ok=False, reason=f"LLM_UNAVAILABLE: {e}")
    except (KeyError, ValueError) as e:
        return ClassifyResult(ok=False, reason=f"LLM_BAD_OUTPUT: {e}")

    answer = str(data.get("answer", "")).strip().upper() if isinstance(data, dict) else ""
    if answer not in VALID_ANSWERS:
        # Never guess YES. An unrecognised answer is treated as "not an answer".
        return ClassifyResult(ok=True, answer="OTHER", reason="INVALID_ANSWER")
    return ClassifyResult(ok=True, answer=answer)