"""
Localization Helper for Python Guard Speech Responses.
Supports 'en' (English), 'hi' (Hindi in Devanagari), and 'mr' (Marathi in Devanagari).
"""

MESSAGES = {
    "OPENING_APP": {
        "en": "Opening {app}.",
        "hi": "{app} खोला जा रहा है।",
        "mr": "{app} उघडत आहे.",
    },
    "CANCELLED_APP_LAUNCH": {
        "en": "Cancelled app launch.",
        "hi": "ऐप खोलना रद्द कर दिया गया।",
        "mr": "ॲप उघडणे रद्द केले.",
    },
    "APP_NOT_INSTALLED": {
        "en": "The app '{app}' is not installed on your phone. Would you like me to install it from the Play Store?",
        "hi": "ऐप '{app}' आपके फोन पर इंस्टॉल नहीं है। क्या आप इसे प्ले स्टोर से इंस्टॉल करना चाहते हैं?",
        "mr": "ॲप '{app}' तुमच्या फोनवर इंस्टॉल केलेले नाही. तुम्ही ते प्ले स्टोअरवरून इंस्टॉल करू इच्छिता का?",
    },
    "INSTALLING_APP": {
        "en": "Installing {app} from Google Play Store.",
        "hi": "गूगल प्ले स्टोर से {app} इंस्टॉल किया जा रहा है।",
        "mr": "गूगल प्ले स्टोअरवरून {app} इंस्टॉल करत आहे.",
    },
    "CANCELLED_INSTALL": {
        "en": "Cancelled Play Store installation.",
        "hi": "प्ले स्टोर इंस्टॉल रद्द कर दिया गया।",
        "mr": "प्ले स्टोअर इन्स्टॉलेशन रद्द केले.",
    },
    "AMBIGUOUS_LAUNCH_QUERY": {
        "en": "Which application would you like me to open?",
        "hi": "आप कौन सा ऐप खोलना चाहते हैं?",
        "mr": "तुम्हाला कोणते ॲप उघडायचे आहे?",
    },
    "APP_IS_OPEN": {
        "en": "{app} is open.",
        "hi": "{app} खुला है।",
        "mr": "{app} उघडे आहे.",
    },
    "QA_OPTIONS_FOUND": {
        "en": "On screen options: {summary}.",
        "hi": "स्क्रीन पर विकल्प: {summary}।",
        "mr": "स्क्रीनवरील पर्याय: {summary}.",
    },
    "QA_NO_OPTIONS": {
        "en": "Inspected on-screen contents. No interactive options detected.",
        "hi": "स्क्रीन की जांच की गई। कोई इंटरेक्टिव विकल्प नहीं मिले।",
        "mr": "स्क्रीन सामग्री तपासली. कोणतेही परस्परसंवादी पर्याय आढळले नाहीत.",
    },
    "LOOP_DETECTED_RECOVERY": {
        "en": "Loop detected. Selecting alternative control.",
        "hi": "लूप की पहचान हुई। वैकल्पिक नियंत्रण चुना जा रहा है।",
        "mr": "लूप आढळला. पर्यायी नियंत्रण निवडत आहे.",
    },
    "ACTION_FAILED_RETRY": {
        "en": "Action failed in foreground app. Selecting next target '{target}'.",
        "hi": "ऐप में कार्रवाई विफल रही। अगला लक्ष्य '{target}' चुना जा रहा है।",
        "mr": "ॲपमध्ये क्रिया अयशस्वी झाली. पुढील लक्ष्य '{target}' निवडत आहे.",
    },
    "ACTION_FAILED_RESCAN": {
        "en": "Action failed in foreground app. Rescanning UI topology.",
        "hi": "ऐप में कार्रवाई विफल रही। स्क्रीन को पुनः स्कैन किया जा रहा है।",
        "mr": "ॲपमध्ये क्रिया अयशस्वी झाली. UI पुन्हा स्कॅन करत आहे.",
    },
    "WAITING_FOR_SCREEN_RENDER": {
        "en": "App is foregrounded. Waiting for screen elements to render.",
        "hi": "ऐप खुला है। स्क्रीन सामग्री लोड होने की प्रतीक्षा की जा रही है।",
        "mr": "ॲप उघडे आहे. स्क्रीन घटक लोड होण्याची वाट पाहत आहे.",
    },
    "EMPTY_DOM_RECOVER_LAUNCH": {
        "en": "Empty screen state detected. Launching {app} to re-establish UI.",
        "hi": "स्क्रीन खाली दिखाई दे रही है। UI रीसेट करने के लिए {app} फिर से खोला जा रहा है।",
        "mr": "स्क्रीन रिकामी आढळली. UI रीसेट करण्यासाठी {app} पुन्हा उघडत आहे.",
    },
    "ENTERING_TEXT_FOREGROUND": {
        "en": "App is foregrounded. Entering text into {target}.",
        "hi": "{target} में टेक्स्ट दर्ज किया जा रहा है।",
        "mr": "{target} मध्ये मजकूर प्रविष्ट करत आहे.",
    },
    "INTERACTING_WITH_TARGET": {
        "en": "App is foregrounded. Interacting with {target}.",
        "hi": "{target} के साथ इंटरैक्ट किया जा रहा है।",
        "mr": "{target} सोबत संवाद साधत आहे.",
    },
    "EXECUTIVE_PLAN": {
        "en": "Executing plan for {goal}.",
        "hi": "{goal} के लिए योजना निष्पादित की जा रही है।",
        "mr": "{goal} साठी योजना कार्यान्वित करत आहे.",
    }
}


def get_localized_message(msg_key: str, lang: str = "en", **kwargs) -> str:
    norm_lang = (lang or "en").strip().lower() if isinstance(lang, str) else "en"
    if norm_lang.startswith("hi"):
        effective_lang = "hi"
    elif norm_lang.startswith("mr"):
        effective_lang = "mr"
    else:
        effective_lang = "en"

    template_dict = MESSAGES.get(msg_key, {})
    template = template_dict.get(effective_lang) or template_dict.get("en") or "Executing action."

    try:
        return template.format(**kwargs)
    except Exception:
        return template
