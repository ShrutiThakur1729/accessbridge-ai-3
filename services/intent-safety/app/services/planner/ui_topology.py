#Handles DOM inspection, spatial parsing, node labels, and UI zone classification.
# Handles DOM inspection, spatial parsing, node labels, and UI zone classification.
import re
import difflib
from typing import List, Dict, Any, Optional
from app.services.planner.utils import sanitize_text

def node_text(node: Dict[str, Any]) -> str:
    """Return sanitized, searchable metadata for an accessibility node."""
    return " ".join(
        sanitize_text(node.get(field))
        for field in ("class_name", "className", "view_id", "viewId", "resource_id", "resourceId",
                      "content_description", "contentDescription", "hint", "text")
        if sanitize_text(node.get(field))
    )

def node_target_label(node: Dict[str, Any]) -> str:
    """Fallback to resource_id or class name to prevent empty targets."""
    if not isinstance(node, dict):
        return ""
    label = next((
        sanitize_text(node.get(field))
        for field in (
            "text", "content_description", "contentDescription", 
            "view_id", "viewId", "resource_id", "resourceId", "hint", "class_name"
        )
        if sanitize_text(node.get(field))
    ), "")
    if not label:
        cls = sanitize_text(node.get("class_name") or node.get("className"))
        if cls:
            label = cls.split(".")[-1]
    return label

def node_is_clickable(node: Dict[str, Any]) -> bool:
    return bool(node.get("is_clickable") or node.get("isClickable") or node.get("clickable"))

def node_is_editable(node: Dict[str, Any]) -> bool:
    return bool(node.get("is_editable") or node.get("isEditable") or node.get("editable"))

def node_top(node: Dict[str, Any]) -> Optional[float]:
    bounds = node.get("bounds") or node.get("bounds_in_screen") or node.get("boundsInScreen")
    if isinstance(bounds, (list, tuple)) and len(bounds) >= 2:
        try:
            return float(bounds[1])
        except (TypeError, ValueError):
            return None
    if isinstance(bounds, str):
        values = re.findall(r"-?\d+(?:\.\d+)?", bounds)
        if len(values) >= 2:
            return float(values[1])
    for key in ("top", "y", "bounds_top"):
        if node.get(key) is not None:
            try:
                return float(node[key])
            except (TypeError, ValueError):
                pass
    return None


def detect_context_mode(screen_state: List[Dict[str, Any]]) -> str:
    """Return the active UI context from visible accessibility-node metadata.

    Picker controls take precedence over forms because a date/time picker can
    contain editable controls while still requiring picker-specific handling.
    """
    picker_tokens = (
        "datepicker", "date picker", "timepicker", "time picker", "numberpicker",
        "number picker", "calendarview", "calendar view", "radialtimepicker",
        "radial time picker", "time wheel", "date wheel", "wheel picker",
    )
    dialog_tokens = ("dialog", "modal", "alertdialog", "bottomsheet", "bottom sheet")
    form_tokens = (
        "form", "address", "name", "email", "phone", "password", "save", "submit",
        "create", "edit", "add",
    )

    visible_nodes = [node for node in (screen_state or []) if isinstance(node, dict)]
    metadata = [node_text(node).lower() for node in visible_nodes]
    if any(any(token in text for token in picker_tokens) for text in metadata):
        return "IN_DIALOG_PICKER"

    has_dialog_container = any(
        bool(node.get("is_modal") or node.get("isModal"))
        or any(token in text for token in dialog_tokens)
        for node, text in zip(visible_nodes, metadata)
    )
    editable_nodes = [node for node in visible_nodes if node_is_editable(node)]
    has_form_signal = any(any(token in text for token in form_tokens) for text in metadata)
    if has_dialog_container and (editable_nodes or has_form_signal):
        return "FORM_FILLING"

    return "DEFAULT"

def classify_ui_topology(screen_state: List[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
    zones = {name: [] for name in (
        "INPUT_ZONE", "ACTION_SUBMIT_ZONE", "LIST_ITEM_ZONE", "HEADER_NAV_ZONE", "SYSTEM_CONTAINER_ZONE"
    )}
    input_tops = []
    prepared = []
    for node in screen_state or []:
        if not isinstance(node, dict):
            continue
        metadata = node_text(node).lower()
        top = node_top(node)
        prepared.append((node, metadata, top))
        if node_is_editable(node) or any(token in metadata for token in (
            "edittext", "search", "type a message", "message", "text box", "textbox", "ask"
        )):
            zones["INPUT_ZONE"].append(node)
            if top is not None:
                input_tops.append(top)

    lowest_input = max(input_tops) if input_tops else None
    for node, metadata, top in prepared:
        clickable = node_is_clickable(node)
        class_name = sanitize_text(node.get("class_name") or node.get("className")).lower()
        is_input = node in zones["INPUT_ZONE"]
        is_system = any(token in metadata for token in (
            "folder name", "samsung, folder", "launcher", "workspace", "folder_title", "folder title"
        )) or ("launcher" in class_name and ("folder" in metadata or "container" in metadata))
        
        if is_system:
            zones["SYSTEM_CONTAINER_ZONE"].append(node)
        if clickable and any(token in metadata for token in (
            "send", "submit", "save", "done", "check", "search", "magnifier", "btn_search",
            "fab", "floatingactionbutton", "add", "plus", "+"
        )):
            zones["ACTION_SUBMIT_ZONE"].append(node)
        if clickable and any(token in metadata for token in (
            "back", "navigate up", "call", "video", "profile", "toolbar", "header"
        )):
            zones["HEADER_NAV_ZONE"].append(node)
        if not clickable and top is not None and top <= 220 and not is_input:
            zones["HEADER_NAV_ZONE"].append(node)
        if clickable and not is_input and not is_system:
            is_below_input = lowest_input is None or top is None or top > lowest_input
            is_action = node in zones["ACTION_SUBMIT_ZONE"] or node in zones["HEADER_NAV_ZONE"]
            if is_below_input and not is_action:
                zones["LIST_ITEM_ZONE"].append(node)
    return zones

def _similarity_ratio(str1: str, str2: str) -> float:
    """Calculates Levenshtein-like similarity ratio between two strings [0.0, 1.0]."""
    if not str1 or not str2:
        return 0.0
    if str1 == str2:
        return 1.0
    return difflib.SequenceMatcher(None, str1, str2).ratio()

def _partial_substring_ratio(short_str: str, long_str: str) -> float:
    """Calculates fuzzy substring ratio of short_str within long_str."""
    if not short_str or not long_str:
        return 0.0
    if short_str in long_str:
        return 1.0
    
    len_short = len(short_str)
    len_long = len(long_str)
    if len_short > len_long:
        short_str, long_str = long_str, short_str
        len_short, len_long = len_long, len_short
        
    best_ratio = 0.0
    for w_size in range(max(1, len_short - 1), min(len_long + 1, len_short + 2)):
        for i in range(len_long - w_size + 1):
            sub = long_str[i:i + w_size]
            ratio = difflib.SequenceMatcher(None, short_str, sub).ratio()
            if ratio > best_ratio:
                best_ratio = ratio
    return best_ratio

def find_best_app_match(candidates: Dict[str, str], visible_node_labels: List[str]) -> Optional[str]:
    """Evaluates visible App Drawer item labels against query candidates using fuzzy matching / Levenshtein ratio.
    
    Returns the visible label if a candidate achieves an 80%+ (0.80) match score.
    """
    if not candidates or not visible_node_labels:
        return None

    raw_cand = (candidates.get("raw") or "").lower().strip()
    no_spaces_cand = (candidates.get("no_spaces") or "").lower().strip()
    consonants_cand = (candidates.get("consonants_only") or "").lower().strip()

    best_match_label = None
    best_score = 0.0

    for raw_label in visible_node_labels:
        if not raw_label or not isinstance(raw_label, str):
            continue
        label = raw_label.lower().strip()
        label_no_spaces = re.sub(r"\s+", "", label)
        label_consonants = re.sub(r"[aeiou\s\W\d]", "", label)

        scores = []

        # 1. Compare raw and no_spaces candidates against label using full and partial substring ratios
        for cand in (raw_cand, no_spaces_cand):
            if not cand:
                continue
            if cand in label or label in cand or cand in label_no_spaces or label_no_spaces in cand:
                scores.append(1.0)
            else:
                scores.append(_similarity_ratio(cand, label))
                scores.append(_similarity_ratio(cand, label_no_spaces))
                scores.append(_partial_substring_ratio(cand, label))
                scores.append(_partial_substring_ratio(cand, label_no_spaces))

        # 2. Compare consonants_only candidate against label's consonants_only
        if consonants_cand and label_consonants:
            if consonants_cand == label_consonants or consonants_cand in label_consonants or label_consonants in consonants_cand:
                scores.append(1.0)
            else:
                scores.append(_similarity_ratio(consonants_cand, label_consonants))
                scores.append(_partial_substring_ratio(consonants_cand, label_consonants))

        max_score_for_label = max(scores) if scores else 0.0
        if max_score_for_label > best_score:
            best_score = max_score_for_label
            best_match_label = raw_label

    if best_score >= 0.80:
        return best_match_label
    return None
