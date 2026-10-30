# Manages package resolutions and fallback operations for launcher and app drawer navigation.
import re
import uuid
from typing import List, Dict, Any, Optional, Tuple
from app.schemas.action_plan import ActionPlanResponse, ActionType, PlanStep, RiskLevel, WaterfallTier
from app.services.planner.utils import sanitize_text
from app.services.planner.ui_topology import (
    classify_ui_topology, node_text, node_target_label, node_is_editable, node_is_clickable,
    find_best_app_match, find_best_app_match_with_score, is_launcher_utility_control
)
from app.services.planner.entity_extractor import normalize_app_query

APP_PACKAGE_HINTS = {
    "whatsapp": ("com.whatsapp", None, "WhatsApp"),
    "व्हाट्सएप": ("com.whatsapp", None, "WhatsApp"),
    "व्हाट्सॲप": ("com.whatsapp", None, "WhatsApp"),
    "uber": ("com.ubercab", None, "Uber"),
    "उबर": ("com.ubercab", None, "Uber"),
    "hotstar": ("com.ripl.hotstar", "in.startv.hotstar", "Hotstar"),
    "jio hotstar": ("com.ripl.hotstar", "in.startv.hotstar", "Jio Hotstar"),
    "disney hotstar": ("com.ripl.hotstar", "in.startv.hotstar", "Disney Hotstar"),
    "हॉटस्टार": ("com.ripl.hotstar", "in.startv.hotstar", "Hotstar"),
    "amazon": ("com.amazon.mShop.android.shopping", None, "Amazon"),
    "amazon shopping": ("com.amazon.mShop.android.shopping", None, "Amazon Shopping"),
    "अमेझॉन": ("com.amazon.mShop.android.shopping", None, "Amazon"),
    "clock": ("com.google.android.deskclock", "com.sec.android.app.clockpackage", "Clock"),
    "alarm": ("com.google.android.deskclock", "com.sec.android.app.clockpackage", "Clock"),
    "घड्याळ": ("com.google.android.deskclock", "com.sec.android.app.clockpackage", "Clock"),
    "अलार्म": ("com.google.android.deskclock", "com.sec.android.app.clockpackage", "Clock"),
    "google maps": ("com.google.android.apps.maps", None, "Google Maps"),
    "maps": ("com.google.android.apps.maps", None, "Google Maps"),
    "गूगल मॅप्स": ("com.google.android.apps.maps", None, "Google Maps"),
    "मॅप्स": ("com.google.android.apps.maps", None, "Google Maps"),
    "bookmyshow": ("com.bt.bms", None, "BookMyShow"),
    "youtube": ("com.google.android.youtube", None, "YouTube"),
    "यूट्यूब": ("com.google.android.youtube", None, "YouTube"),
    "युट्युब": ("com.google.android.youtube", None, "YouTube"),
    "युटूब": ("com.google.android.youtube", None, "YouTube"),
    "यूट्यब": ("com.google.android.youtube", None, "YouTube"),
    "gmail": ("com.google.android.gm", None, "Gmail"),
    "जीमेल": ("com.google.android.gm", None, "Gmail"),
    "chrome": ("com.android.chrome", None, "Chrome"),
    "क्रोम": ("com.android.chrome", None, "Chrome"),
    "telegram": ("org.telegram.messenger", None, "Telegram"),
    "टेलिग्राम": ("org.telegram.messenger", None, "Telegram"),
    "instagram": ("com.instagram.android", None, "Instagram"),
    "इंस्टाग्राम": ("com.instagram.android", None, "Instagram"),
    "facebook": ("com.facebook.katana", None, "Facebook"),
    "फेसबुक": ("com.facebook.katana", None, "Facebook"),
    "spotify": ("com.spotify.music", None, "Spotify"),
    "स्पॉटिफाय": ("com.spotify.music", None, "Spotify"),
    "calendar": ("com.google.android.calendar", None, "Calendar"),
    "कॅलेंडर": ("com.google.android.calendar", None, "Calendar"),
    "camera": ("com.google.android.GoogleCamera", None, "Camera"),
    "कैमरा": ("com.google.android.GoogleCamera", None, "Camera"),
    "कॅमेरा": ("com.google.android.GoogleCamera", None, "Camera"),
    "contacts": ("com.google.android.contacts", None, "Contacts"),
    "कॉन्टेक्ट्स": ("com.google.android.contacts", None, "Contacts"),
    "संपर्क": ("com.google.android.contacts", None, "Contacts"),
    "messages": ("com.google.android.apps.messaging", None, "Messages"),
    "मैसेज": ("com.google.android.apps.messaging", None, "Messages"),
    "संदेश": ("com.google.android.apps.messaging", None, "Messages"),
    "phone": ("com.google.android.dialer", None, "Phone"),
    "फोन": ("com.google.android.dialer", None, "Phone"),
    "settings": ("com.android.settings", None, "Settings"),
    "सेटिंग्स": ("com.android.settings", None, "Settings"),
    "blinkit": ("com.grofers.customerapp", "com.blinkit", "Blinkit"),
    "ब्लिंकिट": ("com.grofers.customerapp", "com.blinkit", "Blinkit"),
    "savana": ("com.savana.android", "com.savana", "Savana"),
    "zepto": ("com.zepto.app", "com.zepto", "Zepto"),
    "झिप्टो": ("com.zepto.app", "com.zepto", "Zepto"),
    "zomato": ("com.application.zomato", "com.zomato", "Zomato"),
    "जोमैटो": ("com.application.zomato", "com.zomato", "Zomato"),
    "झोमॅटो": ("com.application.zomato", "com.zomato", "Zomato"),
    "hungama": ("com.hungama.myplay.activity", "com.hungama", "Hungama"),
}

EXCLUDED_LAUNCHER_UTILITY_TOKENS = (
    "speak", "voice search", "mic", "google", "voice", "search apps"
)

def resolve_app_launch(user_prompt: str) -> Optional[Tuple[Optional[str], Optional[str], str]]:
    normalized_prompt = user_prompt.lower()
    for app_name in sorted(APP_PACKAGE_HINTS, key=len, reverse=True):
        if app_name in normalized_prompt:
            return APP_PACKAGE_HINTS[app_name]

    match = re.search(r"(?:open|launch|set|create|send|play|watch|find|on|in|using|kholo|chalao|ughda|chalu|lava|shuru)\s+(.+)", user_prompt, re.IGNORECASE)
    if match:
        raw_target = match.group(1).strip()
        target_clause = re.split(r"\b(?:and|to|for|with|on|in|then|search|play|watch|find|buy|send|aur|aani|var|par|pe)\b", raw_target, flags=re.IGNORECASE)[0].strip()
        if target_clause:
            normalized_clause = target_clause.lower()
            for app_name in sorted(APP_PACKAGE_HINTS, key=len, reverse=True):
                if app_name in normalized_clause:
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

def is_compound_or_multi_step_intent(user_prompt: str) -> bool:
    if not user_prompt:
        return False
    prompt_lower = user_prompt.lower().strip()
    
    # Check for compound conjunctions / connectors
    connectors_pattern = r"\b(?:aur|and|then|phir|aani|for|in|on|using|with|to|pe|par|me|mein)\b"
    # Check for action verbs / sub-task keywords
    action_verbs_pattern = r"\b(?:search|play|watch|find|send|message|call|type|buy|lagao|laga|chalao|chala|dikhao|dikhayein|bajao|open|kholo|khol|ughada|lav)\b"

    verb_matches = len(re.findall(action_verbs_pattern, prompt_lower))
    has_connector = bool(re.search(connectors_pattern, prompt_lower))

    app_info = resolve_app_launch(user_prompt)
    if app_info and app_info[2]:
        app_name = app_info[2].lower()
        cleaned = re.sub(r"\b(?:open|launch|start|kholo|chalao|ughada|app|application|set|create|send|play|watch|find|show)\b", "", prompt_lower, flags=re.IGNORECASE).strip()
        cleaned = cleaned.replace(app_name, "").strip()
        if len(cleaned) > 2:
            return True

    return verb_matches >= 2 or has_connector

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
    pending_install_intents: Optional[Dict[str, str]] = None,
    session_states: Optional[Dict[str, str]] = None,
    pending_app_confirmations: Optional[Dict[str, str]] = None,
) -> Optional[ActionPlanResponse]:
    if is_compound_or_multi_step_intent(user_prompt):
        return None

    current_state = (session_states or {}).get(session_key, "")

    # =========================================================================
    # FIX 2: POST-CLICK SETTLEMENT STATE GUARD (TURN t+1 INFLATION PROTECTION)
    # =========================================================================
    if current_state == "APP_LAUNCH_IN_PROGRESS":
        if is_app_foreground(current_app_package, user_prompt):
            if session_states is not None:
                session_states[session_key] = "COMPLETED"
            return None  # Pass through to Pre-Plan Foreground Package Lock Guard

        if len(screen_state) <= 12:
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}",
                user_goal=user_prompt,
                risk_level=risk_level,
                total_steps=1,
                current_step_index=0,
                is_completed=False,
                speech_response="Waiting for application to complete launch.",
                steps=[PlanStep(
                    step_id=1,
                    action=ActionType.SCROLL,
                    target_text="rescan_wait",
                    tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                    reason="Post-Click Settlement Protection: App launch clicked; granting 1500ms settlement window for DOM inflation.",
                )],
            )
        else:
            if session_states is not None:
                session_states[session_key] = "APP_DRAWER_ACTIVE"

    if is_app_foreground(current_app_package, user_prompt):
        return None

    action = str(target_action or "").upper()
    status = action_status.upper()
    last = (action_history or [])[-1] if action_history else {}
    last_action = str(last.get("action_type") or last.get("action") or action).upper()

    app_info = resolve_app_launch(user_prompt)
    is_unknown_app = app_info is not None and app_info[0] is None
    launch_failed = (status == "FAILED" and action == ActionType.OPEN_APP.value)

    if current_state != "APP_DRAWER_ACTIVE":
        if last_action == ActionType.GO_HOME.value:
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                speech_response="Opening App Drawer to locate application.",
                steps=[PlanStep(step_id=1, action=ActionType.SWIPE_UP, target_node="home_screen",
                                target_text="Open App Drawer", tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                                reason="App launch recovery Step 2: opening the App Drawer from home screen.")],
            )
        needs_recovery = launch_failed or is_unknown_app
        if needs_recovery and last_action not in {ActionType.SWIPE_UP.value, ActionType.TYPE_TEXT.value} and not looks_like_launcher_or_app_drawer(current_app_package, screen_state):
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                speech_response="Navigating home to open App Drawer.",
                steps=[PlanStep(step_id=1, action=ActionType.GO_HOME, target_node="home_screen",
                                target_text="go_home", tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                                reason="App launch recovery Step 1: navigating to home screen before opening App Drawer.")],
            )

    drawer_active = (
        current_state == "APP_DRAWER_ACTIVE"
        or (status in {"SUCCESS", "INITIAL"} and last_action in {ActionType.SWIPE_UP.value, ActionType.TYPE_TEXT.value})
        or looks_like_launcher_or_app_drawer(current_app_package, screen_state)
    )
    if not drawer_active:
        return None

    if session_states is not None:
        session_states[session_key] = "APP_DRAWER_ACTIVE"

    app_name = app_drawer_searches.get(session_key) or extract_app_name_for_search(user_prompt)
    app_drawer_searches[session_key] = app_name
    candidates = normalize_app_query(app_name)

    topology = classify_ui_topology(screen_state)
    list_items = topology.get("LIST_ITEM_ZONE", [])

    filtered_items = []
    for item in list_items:
        if not isinstance(item, dict) or not node_is_clickable(item):
            continue
        if is_launcher_utility_control(item):
            continue
        filtered_items.append(item)

    # STRICT MATCH FLOOR EVALUATION (>= 65%)
    if last_action == ActionType.TYPE_TEXT.value or len(filtered_items) > 0:
        item_labels = [node_target_label(item) or node_text(item) for item in filtered_items]
        best_match_label, best_score = find_best_app_match_with_score(candidates, item_labels)

        # 1. High Confidence Match (>= 85%) -> Direct Click + Transition to APP_LAUNCH_IN_PROGRESS
        if best_match_label and best_score >= 0.85:
            target = best_match_label
            if session_states is not None:
                session_states[session_key] = "APP_LAUNCH_IN_PROGRESS"
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                speech_response=f"Found matching app '{target}'. Launching.",
                steps=[PlanStep(step_id=1, action=ActionType.CLICK, target_node=target, target_text=target,
                                tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                                reason=f"App Drawer search result match: clicking {target}.")],
            )
        # 2. Moderate Match (65% <= score < 85%) -> Candidate Prompt
        elif best_match_label and 0.65 <= best_score < 0.85:
            if pending_app_confirmations is not None:
                pending_app_confirmations[session_key] = best_match_label
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                execution_state="AWAITING_USER_CONFIRMATION",
                speech_response=f"I couldn't find '{app_name}', but I found '{best_match_label}'. Would you like me to open that?",
                steps=[PlanStep(step_id=1, action=ActionType.FINISH,
                                reason="Ambiguous app match: prompting user for confirmation of candidate app.")],
            )
        # 3. Low Match (< 65%) or Zero Items -> Play Store Auto-Install
        elif last_action == ActionType.TYPE_TEXT.value or len(screen_state) == 0 or (len(filtered_items) > 0 and best_score < 0.65):
            if pending_install_intents is not None:
                pending_install_intents[session_key] = app_name
            return ActionPlanResponse(
                plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
                total_steps=1, current_step_index=0, is_completed=False,
                execution_state="AWAITING_USER_CONFIRMATION",
                speech_response=f"The app '{app_name}' is not installed on your phone. Would you like me to install it from the Play Store?",
                steps=[PlanStep(step_id=1, action=ActionType.FINISH,
                                reason="App not installed (match score < 65%): prompting user for Play Store installation confirmation.")],
            )

    # APP DRAWER SEARCH BAR FOCUS & BINDING
    search_bar = next((
        node for node in (topology.get("INPUT_ZONE", []) + screen_state)
        if isinstance(node, dict) and (
            node_is_editable(node)
            or any(token in node_text(node).lower() for token in (
                "search", "find", "edittext", "apps", "search_box", "search_src_text", "search_bar"
            ))
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

    target = node_target_label(search_bar) or "Search"
    return ActionPlanResponse(
        plan_id=f"plan_{uuid.uuid4().hex[:8]}", user_goal=user_prompt, risk_level=risk_level,
        total_steps=1, current_step_index=0, is_completed=False,
        speech_response=f"App Drawer active. Searching for {app_name}.",
        steps=[PlanStep(step_id=1, action=ActionType.TYPE_TEXT, target_node=target, target_text=target,
                        input_data=candidates["raw"], tier=WaterfallTier.TIER_3_ACCESSIBILITY,
                        reason="App Drawer recovery: typing normalized app query into search field.")],
    )