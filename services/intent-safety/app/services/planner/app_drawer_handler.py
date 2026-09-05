#Manages package resolutions and fallback operations for launcher and app drawer navigation.
import re
import uuid
from typing import List, Dict, Any, Optional, Tuple
from app.schemas.action_plan import ActionPlanResponse, ActionType, PlanStep, RiskLevel, WaterfallTier
from app.services.planner.utils import sanitize_text
from app.services.planner.ui_topology import classify_ui_topology, node_text, node_target_label, node_is_editable

APP_PACKAGE_HINTS = {
    "whatsapp": ("com.whatsapp", None, "WhatsApp"),
    "uber": ("com.ubercab", None, "Uber"),
    "hotstar": ("com.ripl.hotstar", "in.startv.hotstar", "Hotstar"),
    "jio hotstar": ("com.ripl.hotstar", "in.startv.hotstar", "Jio Hotstar"),
    "disney hotstar": ("com.ripl.hotstar", "in.startv.hotstar", "Disney Hotstar"),
    "amazon": ("com.amazon.mShop.android.shopping", None, "Amazon"),
    "amazon shopping": ("com.amazon.mShop.android.shopping", None, "Amazon Shopping"),
    "clock": ("com.google.android.deskclock", "com.sec.android.app.clockpackage", "Clock"),
    "alarm": ("com.google.android.deskclock", "com.sec.android.app.clockpackage", "Clock"),
    "google maps": ("com.google.android.apps.maps", None, "Google Maps"),
    "maps": ("com.google.android.apps.maps", None, "Google Maps"),
    "bookmyshow": ("com.bt.bms", None, "BookMyShow"),
    "youtube": ("com.google.android.youtube", None, "YouTube"),
    "gmail": ("com.google.android.gm", None, "Gmail"),
    "chrome": ("com.android.chrome", None, "Chrome"),
    "telegram": ("org.telegram.messenger", None, "Telegram"),
    "instagram": ("com.instagram.android", None, "Instagram"),
    "facebook": ("com.facebook.katana", None, "Facebook"),
    "spotify": ("com.spotify.music", None, "Spotify"),
    "calendar": ("com.google.android.calendar", None, "Calendar"),
    "camera": ("com.google.android.GoogleCamera", None, "Camera"),
    "contacts": ("com.google.android.contacts", None, "Contacts"),
    "messages": ("com.google.android.apps.messaging", None, "Messages"),
    "phone": ("com.google.android.dialer", None, "Phone"),
    "settings": ("com.android.settings", None, "Settings"),
}

def resolve_app_launch(user_prompt: str) -> Optional[Tuple[Optional[str], Optional[str], str]]:
    normalized_prompt = user_prompt.lower()
    for app_name in sorted(APP_PACKAGE_HINTS, key=len, reverse=True):
        if re.search(rf"\b{re.escape(app_name)}\b", normalized_prompt):
            return APP_PACKAGE_HINTS[app_name]

    match = re.search(r"\b(?:open|launch|set|create|send|play|watch|find|on|in|using)\s+([a-zA-Z0-9\s]+)", user_prompt, re.IGNORECASE)
    if match:
        raw_target = match.group(1).strip()
        target_clause = re.split(r"\b(?:and|to|for|with|on|in|then|search|play|watch|find|buy|send)\b", raw_target, flags=re.IGNORECASE)[0].strip()
        if target_clause:
            normalized_clause = target_clause.lower()
            for app_name in sorted(APP_PACKAGE_HINTS, key=len, reverse=True):
                if re.search(rf"\b{re.escape(app_name)}\b", normalized_clause):
                    return APP_PACKAGE_HINTS[app_name]
            extracted_label = " ".join(word.capitalize() for word in target_clause.split())
            return (None, None, extracted_label)
    return None

def extract_app_name_for_search(user_prompt: str) -> str:
    app_launch = resolve_app_launch(user_prompt)
    if app_launch and app_launch[2]:
        return app_launch[2]
    clean = re.sub(r"^(?:open|launch|search|find|play|watch|book|set|create|send)\s+(?:for\s+)?", "", user_prompt.strip(), flags=re.IGNORECASE).strip()
    clean = re.split(r"\b(?:and|to|for|with|on|in|then)\b", clean, flags=re.IGNORECASE)[0].strip()
    return " ".join(word.capitalize() for word in clean.split()) if clean else user_prompt

def looks_like_launcher_or_app_drawer(current_app_package: Optional[str], screen_state: List[Dict[str, Any]]) -> bool:
    package = sanitize_text(current_app_package).lower()
    if any(token in package for token in ("launcher", "quickstep", "home", "trebuchet", "miui")):
        return True
    metadata = " ".join(node_text(node).lower() for node in screen_state if isinstance(node, dict))
    return any(token in metadata for token in ("app drawer", "search apps", "find apps", "all apps", "folder name", "samsung, folder"))

def is_app_foreground(current_app_package: Optional[str], user_prompt: str) -> bool:
    if not current_app_package:
        return False
    app_info = resolve_app_launch(user_prompt)
    if not app_info:
        return False
    pkg, fallback_pkg, app_name = app_info
    curr_pkg = sanitize_text(current_app_package).lower().strip()
    if not curr_pkg:
        return False
    if pkg and (pkg.lower() in curr_pkg or curr_pkg in pkg.lower()):
        return True
    if fallback_pkg and (fallback_pkg.lower() in curr_pkg or curr_pkg in fallback_pkg.lower()):
        return True
    if app_name:
        clean_app_name = app_name.lower().replace(" ", "")
        clean_curr = curr_pkg.replace(".", "").replace("_", "")
        if clean_app_name in clean_curr or clean_curr in clean_app_name:
            return True
    return False

def handle_app_drawer_recovery(
    user_prompt: str,
    screen_state: List[Dict[str, Any]],
    risk_level: RiskLevel,
    action_status: str,
    target_action: Optional[str],
    current_app_package: Optional[str],
    session_key: str,
    app_drawer_searches: Dict[str, str],
    action_history: Optional[List[Dict[str, Any]]] = None,
) -> Optional[ActionPlanResponse]:
    if is_app_foreground(current_app_package, user_prompt):
        return None

    action = str(target_action or "").upper()
    status = action_status.upper()
    last = (action_history or [])[-1] if action_history else {}
    last_action = str(last.get("action_type") or last.get("action") or action).upper()

    app_info = resolve_app_launch(user_prompt)
    is_unknown_app = app_info is not None and app_info[0] is None
    launch_failed = (status == "FAILED" and action == ActionType.OPEN_APP.value)

    if launch_failed or (is_unknown_app and last_action != ActionType.SWIPE_UP.value and not looks_like_launcher_or_app_drawer(current_app_package, screen_state)):
        return ActionPlanResponse(
            plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
            total_steps=1, current_step_index=0, is_completed=False,
            speech_response="Opening App Drawer to locate application.",
            steps=[PlanStep(step_id=1, action=ActionType.SWIPE_UP, target_node="home_screen",
                            target_text="Open App Drawer", tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            reason="App launch recovery: opening the App Drawer to search for application.")],
        )

    drawer_active = (
        (status in {"SUCCESS", "INITIAL"} and last_action == ActionType.SWIPE_UP.value)
        or looks_like_launcher_or_app_drawer(current_app_package, screen_state)
    )
    if not drawer_active:
        return None

    search_bar = next((
        node for node in screen_state
        if isinstance(node, dict) and (
            node_is_editable(node)
            or any(token in node_text(node).lower() for token in ("search", "find", "edittext", "apps"))
        )
    ), None)

    if not search_bar:
        return ActionPlanResponse(
            plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
            total_steps=1, current_step_index=0, is_completed=False,
            speech_response="Waiting for the App Drawer search field.",
            steps=[PlanStep(step_id=1, action=ActionType.SCROLL, target_text="rescan_wait",
                            tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                            reason="App Drawer recovery: waiting for search bar element.")],
        )

    app_name = extract_app_name_for_search(user_prompt)
    app_drawer_searches[session_key] = app_name
    target = node_target_label(search_bar) or "Search"
    return ActionPlanResponse(
        plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
        total_steps=1, current_step_index=0, is_completed=False,
        speech_response=f"App Drawer active. Searching for {app_name}.",
        steps=[PlanStep(step_id=1, action=ActionType.TYPE_TEXT, target_node=target, target_text=target,
                        input_data=app_name, tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="App Drawer recovery: typing extracted app name into search field.")],
    )