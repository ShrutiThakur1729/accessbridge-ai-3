from typing import Any, Dict, List, Optional


def _value(node: Dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = node.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def _truthy(node: Dict[str, Any], *keys: str) -> bool:
    return any(bool(node.get(key)) for key in keys)


def _clean_node_entry(node: Dict[str, Any], role: str) -> Dict[str, Any]:
    entry = {
        "text": _value(node, "text"),
        "content_description": _value(node, "content_description", "contentDescription"),
        "view_id": _value(node, "view_id", "viewId", "resource-id", "resource_id"),
        "class_name": _value(node, "class_name", "className"),
        "is_clickable": _truthy(node, "is_clickable", "isClickable", "clickable"),
        "is_editable": _truthy(node, "is_editable", "isEditable", "editable"),
        "is_focused": _truthy(node, "is_focused", "isFocused", "focused"),
        "role": role,
    }
    bounds = _value(node, "bounds")
    if bounds:
        entry["bounds"] = bounds
    if node.get("source"):
        entry["source"] = node.get("source")
    if node.get("tap_x") is not None and node.get("tap_y") is not None:
        entry["tap_x"] = node.get("tap_x")
        entry["tap_y"] = node.get("tap_y")
        entry["is_clickable"] = True
    return entry


def compress_screen_state(screen_state: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Cleans and categorizes raw Android accessibility tree nodes into two structural arrays:
    - interactive_targets: Clickable items, buttons, actionable titles, input/search fields.
    - secondary_metadata: Subtitles, message preview snippets, timestamps, status text.
    """
    interactive_targets: List[Dict[str, Any]] = []
    secondary_metadata: List[Dict[str, Any]] = []
    active_input_focused = False
    focused_hint: Optional[str] = None

    if not isinstance(screen_state, list):
        return {
            "active_input_focused": False,
            "focused_hint": None,
            "interactive_targets": [],
            "secondary_metadata": [],
        }

    for raw_node in screen_state:
        if not isinstance(raw_node, dict):
            continue

        class_name = _value(raw_node, "class_name", "className").lower()
        view_id = _value(raw_node, "view_id", "viewId", "resource-id", "resource_id").lower()
        text = _value(raw_node, "text")
        content_desc = _value(raw_node, "content_description", "contentDescription")

        # Skip system soft keyboard keys and decoration noise
        if "keyboard" in class_name or "keyboard" in view_id or "softkeyboard" in class_name:
            continue

        is_clickable = _truthy(raw_node, "is_clickable", "isClickable", "clickable")
        is_editable = _truthy(raw_node, "is_editable", "isEditable", "editable")
        is_focused = _truthy(raw_node, "is_focused", "isFocused", "focused")
        has_tap = raw_node.get("tap_x") is not None and raw_node.get("tap_y") is not None
        looks_like_input = any(token in view_id for token in ("input", "search", "edittext", "query"))

        # Detect active focused input field
        if (is_editable and is_focused) or (is_focused and looks_like_input):
            active_input_focused = True
            if not focused_hint:
                focused_hint = _value(
                    raw_node,
                    "hint",
                    "placeholder",
                    "text",
                    "content_description",
                    "contentDescription",
                    "view_id",
                    "viewId",
                ) or None

        # Categorize into interactive_targets vs secondary_metadata
        if is_clickable or is_editable or is_focused or looks_like_input or has_tap:
            if len(interactive_targets) < 60:
                interactive_targets.append(_clean_node_entry(raw_node, "interactive_target"))
        elif text or content_desc:
            if len(secondary_metadata) < 60:
                secondary_metadata.append(_clean_node_entry(raw_node, "secondary_metadata"))

    return {
        "active_input_focused": active_input_focused,
        "focused_hint": focused_hint,
        "interactive_targets": interactive_targets,
        "secondary_metadata": secondary_metadata,
    }
