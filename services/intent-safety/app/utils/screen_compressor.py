from typing import Any, Dict, List


def _value(node: Dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = node.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def _truthy(node: Dict[str, Any], *keys: str) -> bool:
    return any(bool(node.get(key)) for key in keys)


def _node_summary(node: Dict[str, Any], role: str) -> Dict[str, Any]:
    return {
        "text": _value(node, "text"),
        "content_description": _value(node, "content_description", "contentDescription"),
        "view_id": _value(node, "view_id", "viewId", "resource-id", "resource_id"),
        "is_clickable": _truthy(node, "is_clickable", "isClickable", "clickable"),
        "is_editable": _truthy(node, "is_editable", "isEditable", "editable"),
        "role": role,
    }


def compress_screen_state(screen_state: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Build a compact, structural LLM view without guessing app-specific roles."""
    interactive_targets: List[Dict[str, Any]] = []
    secondary_metadata: List[Dict[str, Any]] = []
    active_input_focused = False
    focused_hint = ""

    for raw_node in screen_state:
        if not isinstance(raw_node, dict):
            continue
        node = raw_node
        view_id = _value(node, "view_id", "viewId", "resource-id", "resource_id").lower()
        is_editable = _truthy(node, "is_editable", "isEditable", "editable")
        is_focused = _truthy(node, "is_focused", "isFocused", "focused")
        looks_like_input = any(token in view_id for token in ("input", "search", "edittext", "query"))
        if is_editable or is_focused or looks_like_input:
            active_input_focused = True
            if not focused_hint:
                focused_hint = _value(
                    node, "hint", "placeholder", "text", "content_description", "contentDescription", "view_id", "viewId"
                )

        if _truthy(node, "is_clickable", "isClickable", "clickable"):
            if len(interactive_targets) < 45:
                interactive_targets.append(_node_summary(node, "interactive_target"))
        elif _value(node, "text", "content_description", "contentDescription") and len(secondary_metadata) < 75:
            secondary_metadata.append(_node_summary(node, "preview_snippet"))

    return {
        "active_input_focused": active_input_focused,
        "focused_hint": focused_hint,
        "interactive_targets": interactive_targets,
        "secondary_metadata": secondary_metadata,
    }
