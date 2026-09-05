##Contains pure text sanitization and base helper functions.
import re
import unicodedata
from typing import Optional

def sanitize_text(text: Optional[str]) -> str:
    """Strip invisible spaces, control characters, and non-printable artifacts."""
    if text is None:
        return ""
    clean = unicodedata.normalize("NFKC", str(text)).replace("\u00a0", " ")
    clean = re.sub(r"[\u0000-\u001f\u007f-\u009f\u200b-\u200d\ufeff\ufffd]", "", clean)
    clean = clean.replace("Ã—", "×").replace("Â", "")
    return re.sub(r"\s+", " ", clean).strip()