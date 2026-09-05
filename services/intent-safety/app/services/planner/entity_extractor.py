#Handles regex intent parsing and parameter isolation.
import re
from typing import Dict
from app.services.planner.utils import sanitize_text

def extract_intent_payloads(user_prompt: str, field_type: str) -> str:
    prompt = sanitize_text(user_prompt)
    if not prompt:
        return ""
    kind = sanitize_text(field_type).upper()

    def clean(value: str) -> str:
        value = sanitize_text(value).strip(" \t\"'")
        value = re.sub(r"\s+\b(?:on|via|using|in)\s+.+$", "", value, flags=re.IGNORECASE)
        return value.strip(" \t\"'")

    if kind == "MESSAGE_PAYLOAD":
        match = re.search(r"\b(?:saying|texting)\s+(?:that\s+)?[\"']?(.+?)[\"']?(?:\s*$)", prompt, re.I)
        if not match:
            match = re.search(r"\b(?:message|text|write)\s+(?!to\b)(?:that\s+)?[\"']?(.+?)[\"']?(?:\s*$)", prompt, re.I)
        if match:
            return clean(match.group(1))
        match = re.search(r"\b(?:send|say)\s+[\"']?(.+?)[\"']?\s+\b(?:to|via)\b", prompt, re.I)
        return clean(match.group(1)) if match else ""

    patterns = (
        r"\b(?:search(?:\s+for)?|find|look\s*up|play|watch|navigate\s+to|go\s+to|open|select)\s+[\"']?(.+?)[\"']?(?=\s+\b(?:on|via|using|in|saying|message|text)\b|$)",
        r"\b(?:send|message|text|call)\s+(?:a\s+)?(?:message\s+)?\bto\s+[\"']?(.+?)[\"']?(?=\s+\b(?:on|via|using|in|saying|message|text)\b|$)",
        r"\b(?:set|create|add|remind)\b.*?\b(?:for|at|to)\s+[\"']?(.+?)[\"']?(?=\s+\b(?:on|via|using|in|saying|message|text)\b|$)",
    )
    for pattern in patterns:
        match = re.search(pattern, prompt, re.I)
        if match and clean(match.group(1)):
            return clean(match.group(1))
    if not re.search(r"\b(?:to|on|via|in|saying|using)\b", prompt, re.I):
        return clean(prompt)
    return ""

def extract_clean_entity(user_prompt: str, field_context: str = "") -> str:
    field_hint = sanitize_text(field_context).lower()
    if any(keyword in field_hint for keyword in (
        "type a message", "message", "write a reply", "type message", "chat_input",
        "entry_message", "input_message", "send message",
    )):
        return extract_intent_payloads(user_prompt, "MESSAGE_PAYLOAD")
    return extract_intent_payloads(user_prompt, "TARGET_ENTITY")

def normalize_app_query(raw_text: str) -> Dict[str, str]:
    """Returns candidate search variations for phonetic normalization and STT error handling."""
    raw = sanitize_text(raw_text or "").strip()
    raw_lower = raw.lower()
    no_spaces = re.sub(r"\s+", "", raw_lower)
    consonants_only = re.sub(r"[aeiou\s\W\d]", "", raw_lower)
    return {
        "raw": raw,
        "no_spaces": no_spaces,
        "consonants_only": consonants_only,
    }