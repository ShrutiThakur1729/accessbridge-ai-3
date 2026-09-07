# Handles DOM inspection, spatial parsing, node labels, and UI zone classification.
import re
import difflib
from typing import List, Dict, Any, Optional, Tuple
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


def parse_node_bounds(node: Dict[str, Any]) -> Optional[Tuple[float, float, float, float]]:
    """Extract (top, left, bottom, right) bounds if present."""
    if not isinstance(node, dict):
        return None
    bounds = node.get("bounds") or node.get("bounds_in_screen") or node.get("boundsInScreen")
    if isinstance(bounds, (list, tuple)) and len(bounds) >= 4:
        try:
            return (float(bounds[1]), float(bounds[0]), float(bounds[3]), float(bounds[2]))
        except (TypeError, ValueError):
            pass
    if isinstance(bounds, str):
        values = re.findall(r"-?\d+(?:\.\d+)?", bounds)
        if len(values) >= 4:
            return (float(values[1]), float(values[0]), float(values[3]), float(values[2]))
    top = node_top(node)
    left = None
    for key in ("left", "x", "bounds_left"):
        if node.get(key) is not None:
            try:
                left = float(node[key])
                break
            except (TypeError, ValueError):
                pass
    if top is not None and left is not None:
        return (top, left, top + 50.0, left + 100.0)
    return None


def group_spatial_nodes(screen_state: List[Dict[str, Any]], proximity_threshold: float = 60.0) -> List[Dict[str, Any]]:
    """Universal Spatial Grouping Algorithm:
    Groups spatially adjacent nodes (vertical/horizontal alignment within threshold dp/pixels
    or shared parent containers) into unified visual objects (cards/options).
    """
    if not screen_state or not isinstance(screen_state, list):
        return []

    valid_nodes = []
    for node in screen_state:
        if not isinstance(node, dict):
            continue
        txt = node_target_label(node) or node_text(node)
        if not txt and not node_is_clickable(node):
            continue
        valid_nodes.append(node)

    if not valid_nodes:
        return []

    groups: List[List[Dict[str, Any]]] = []
    visited = set()

    for i, node_a in enumerate(valid_nodes):
        if i in visited:
            continue
        current_group = [node_a]
        visited.add(i)
        bounds_a = parse_node_bounds(node_a)
        parent_a = node_a.get("parent_id") or node_a.get("container_id") or node_a.get("view_id")

        for j, node_b in enumerate(valid_nodes):
            if j in visited:
                continue
            parent_b = node_b.get("parent_id") or node_b.get("container_id") or node_b.get("view_id")
            same_parent = bool(parent_a and parent_b and parent_a == parent_b)

            bounds_b = parse_node_bounds(node_b)
            near_spatial = False
            if bounds_a and bounds_b:
                top_diff = abs(bounds_a[0] - bounds_b[0])
                left_diff = abs(bounds_a[1] - bounds_b[1])
                near_spatial = (top_diff <= proximity_threshold) or (left_diff <= proximity_threshold and top_diff <= proximity_threshold * 2)

            if same_parent or near_spatial:
                current_group.append(node_b)
                visited.add(j)

        groups.append(current_group)

    grouped_objects = []
    for idx, grp in enumerate(groups, 1):
        texts = [node_target_label(n) for n in grp if node_target_label(n)]
        combined_text = " - ".join(dict.fromkeys(texts))
        clickable_node = next((n for n in grp if node_is_clickable(n)), grp[0])

        card = {
            "card_id": f"card_{idx}",
            "combined_label": combined_text or "Interactive Container",
            "clickable_target": node_target_label(clickable_node),
            "is_clickable": any(node_is_clickable(n) for n in grp),
            "is_editable": any(node_is_editable(n) for n in grp),
            "element_count": len(grp),
        }
        if clickable_node.get("tap_x") is not None and clickable_node.get("tap_y") is not None:
            card["tap_x"] = clickable_node.get("tap_x")
            card["tap_y"] = clickable_node.get("tap_y")

        grouped_objects.append(card)

    return grouped_objects

def detect_context_mode(screen_state: List[Dict[str, Any]]) -> str:
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

        # =========================================================================
        # TASK 4: EXPANDED INPUT_ZONE CLASSIFICATION (SEARCH BARS)
        # =========================================================================
        is_search_field = any(token in metadata for token in (
            "edittext", "search", "search apps", "find apps", "type a message",
            "message", "text box", "textbox", "ask", "search_box", "search_src_text", "search_bar"
        ))
        if node_is_editable(node) or is_search_field:
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
    if not str1 or not str2:
        return 0.0
    if str1 == str2:
        return 1.0
    return difflib.SequenceMatcher(None, str1, str2).ratio()

LAUNCHER_UTILITY_TOKENS = (
    "speak", "voice search", "mic", "google", "clear query", "search", "search apps", "voice", "clear text"
)

def is_launcher_utility_control(item: Any) -> bool:
    if isinstance(item, str):
        lbl = item.lower().strip()
    elif isinstance(item, dict):
        lbl = (node_target_label(item) or node_text(item)).lower().strip()
    else:
        return False
    if lbl in LAUNCHER_UTILITY_TOKENS:
        return True
    return any(tok in lbl for tok in ("speak", "voice search", "mic", "google", "clear query"))

def find_best_app_match_with_score(candidates: Dict[str, str], visible_node_labels: List[str]) -> Tuple[Optional[str], float]:
    if not candidates or not visible_node_labels:
        return None, 0.0

    raw_cand = (candidates.get("raw") or "").lower().strip()
    no_spaces_cand = (candidates.get("no_spaces") or "").lower().strip()
    consonants_cand = (candidates.get("consonants_only") or "").lower().strip()

    best_match_label = None
    best_score = 0.0

    for raw_label in visible_node_labels:
        if not raw_label or not isinstance(raw_label, str):
            continue
        if is_launcher_utility_control(raw_label):
            continue
        label = raw_label.lower().strip()
        label_no_spaces = re.sub(r"\s+", "", label)
        label_consonants = re.sub(r"[aeiou\s\W\d]", "", label)

        # Length mismatch guard: Penalize drastic length differences (e.g., "Hungama" vs "H&M")
        len_diff = abs(len(raw_cand) - len(label))
        if len_diff > 4 and not (raw_cand in label or label in raw_cand):
            continue

        scores = []

        # 1. Full and space-stripped similarity
        for cand in (raw_cand, no_spaces_cand):
            if not cand:
                continue
            if cand == label or cand == label_no_spaces:
                scores.append(1.0)
            elif cand in label or label in cand:
                # Substring bonus weighted by coverage ratio
                coverage = len(cand) / max(len(label), 1)
                scores.append(min(1.0, 0.75 + (0.25 * coverage)))
            else:
                scores.append(_similarity_ratio(cand, label))
                scores.append(_similarity_ratio(cand, label_no_spaces))

        # 2. Consonants similarity
        if consonants_cand and label_consonants:
            if consonants_cand == label_consonants:
                scores.append(0.9)
            elif len(consonants_cand) > 2 and consonants_cand in label_consonants:
                scores.append(0.8)

        max_score_for_label = max(scores) if scores else 0.0
        if max_score_for_label > best_score:
            best_score = max_score_for_label
            best_match_label = raw_label

    return best_match_label, best_score

def find_best_app_match(candidates: Dict[str, str], visible_node_labels: List[str]) -> Optional[str]:
    label, score = find_best_app_match_with_score(candidates, visible_node_labels)
    if score >= 0.85:
        return label
    return None