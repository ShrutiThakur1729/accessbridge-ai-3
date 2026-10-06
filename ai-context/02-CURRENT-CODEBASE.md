PROJECT CONTEXT VERSION: 1.0.0
DATE: October 6, 2026
PROJECT NAME: AccessBridge AI
ONE-SENTENCE DESCRIPTION: A 4-tier hybrid Android voice-first accessibility and screen automation platform that uses local AI planning (FastAPI + Ollama/OpenAI) and vision perception (FastAPI + OpenCV/EasyOCR/YOLO) routed through a Node.js WebSocket API gateway to dynamically resolve and execute multi-step mobile UI actions without hardcoded scripts.

==================================================
PART 1 — PROJECT OVERVIEW
=========================

1. Project Name: AccessBridge AI

2. Project Purpose:
AccessBridge AI is designed to act as an autonomous, voice-driven AI co-pilot for Android smart devices. It allows users to control installed Android applications and perform complex, multi-step smartphone tasks using spoken natural language commands. Instead of relying on rigid, pre-recorded macros or hardcoded app-specific scripts, AccessBridge AI inspects on-screen layout trees dynamically, feeds compressed screen structures to a local AI planner, and automatically carries out physical touch interactions (like launching apps, tapping buttons, entering text, or scrolling) on the user's behalf.

3. Problem It Is Trying to Solve:
* Motor & Visual Impairment Barriers: Modern smartphone operating systems (such as Android) are heavily touch-centric and visually dependent. Users with motor disabilities, tremors, visual impairments, or temporary physical limitations struggle with complex navigation.
* Brittle Scripting & Fragmented APIs: Existing automation tools break whenever an app updates its visual UI layout because they rely on fixed screen pixel coordinates or specific app APIs.
* Privacy & High Cloud Costs: Continuous cloud-based computer-vision and cloud LLM automation APIs incur high latency, steep monthly token fees, and expose confidential user screen data (personal text messages, bank details, contact lists) to third-party cloud servers.

4. Target Users:
* Individuals with physical motor disabilities or visual impairments requiring hands-free, voice-first smartphone navigation.
* Users in situational hands-free environments (e.g., driving, cooking, working).
* Developers and researchers exploring zero-hardcoding local AI agent automation on mobile operating systems.

5. Main Features:
* Dynamic Local Fast-Path App Launcher: Instant resolution and launching of installed Android packages using Android's native `PackageManager` combined with Double Metaphone phonetic encoding and Jaro-Winkler string distance (`LauncherAppResolver.kt`).
* Dynamic AI Task Planning: Multi-turn natural language task reasoning powered by a local LLM (`llama3.1:8b` via Ollama/OpenAI interface in `planner_service.py` & `llm_client.py`).
* Real-Time Bidirectional WebSocket Streaming: Low-latency communication hub between the mobile device and backend microservices (`wsHandler.ts`).
* Accessibility Node Tree Extraction & Compression: Automated harvesting of on-screen elements via Android `AccessibilityService` (`AccessibilityAgentService.kt`) compressed up to 80% (`compressScreenState` in `wsHandler.ts` / `screen_compressor.py`).
* Fallback Computer-Vision Engine: Spatial $(x,y)$ coordinate detection using OpenCV contour analysis, EasyOCR text extraction, and YOLO object detection when Accessibility DOM nodes are missing or unlabelled (`vision_service.py`).
* Safety Interceptor & Guardrails: 3-tier risk classification engine enforcing biometric authentication (`CONFIRM_BIOMETRIC`), explicit user voice confirmations (`AWAITING_USER_CONFIRMATION`), and emergency aborts (`HARD_STOP` / `USER_TAKE_OVER`) for sensitive operations (`safety_service.py`).
* Multi-Turn Global Session Memory: Persistent session tracking (`global_session_id`) across cross-application transitions (`SessionManager.kt`, `conversation_state_manager`).
* Loop & Execution Anomaly Recovery: Automated detection of repeating execution loops (`LoopDetector.kt`) and non-responsive screen states with escalation pathways (`wsHandler.ts`).

6. Current Implementation Status:
The project is a working hybrid microservices prototype. Core microservice communication, WebSocket streaming, session persistence, fast-path app launching, Accessibility tree extraction, screen compression, safety guardrails, and basic vision perception are built and functional. However, several integration edges and heuristic guardrails contain known logic bugs and incomplete features.

7. What Is Actually Working:
* Android native Accessibility Service registration and event handling (`AccessibilityAgentService.kt`).
* Native WebSocket connection establishing and keep-alive pinging between Kotlin client and Node.js Gateway-Core (`WebSocketManager.kt` <-> `wsHandler.ts`).
* In-memory `global_session_id` context persistence across app switches and physical button presses.
* Local fast-path app resolution for exact and partial app label matches (`LauncherAppResolver.kt`).
* Node.js DOM compression filtering out non-interactive layout containers and empty nodes (`wsHandler.ts`).
* FastAPI microservice routing for `/api/v1/plan` (`main.py` in `services/intent-safety`).
* FastAPI vision perception fallback endpoints (`main.py` & `vision_service.py` in `services/vision-perception`).
* Biometric authentication trigger via Android `BiometricPrompt` (`MainActivity.kt`).

8. What Is Partially Implemented:
* Dynamic LLM Confirmation Routing: Prompt logic for confirming ambiguous app matches ($0.60 \le \text{score} < 0.85$) exists in Python (`planner_service.py`), but Gateway-Core (`wsHandler.ts`) lacks dedicated `/classify-intent` routing.
* Play Store Installation Fallback: Python planner contains logic to generate 3-step Play Store installation plans, but automated Play Store UI navigation on-device is untested and incomplete.
* Vision Perception Integration: OpenCV contour detection and EasyOCR reading work in `vision_service.py`, but YOLO model loading falls back to OpenCV if `yolov8n.pt` is absent.

9. What Is Not Implemented:
* Shared Schemas across microservices: `shared/json-schemas` and `shared/websocket-events` folders are currently empty 0-byte directories.
* Database Persistence: No relational database (PostgreSQL/MySQL) or document store (MongoDB/Redis) is integrated. All state is held in-memory.
* Automated Telemetry Logging / Anonymized Feedback Loop.

10. Major Limitations:
* In-Memory State Only: Restarting any backend service or the Android service wipes active session state and loop history immediately.
* Device Network Configuration: WebSocket server URL is currently hardcoded to a local IP address (`ws://192.168.29.217:3000/ws/agent`) inside `AccessibilityAgentService.kt`.
* Single Active Connection: Gateway-Core holds single task context objects in memory without multi-tenant authentication token isolation.
* Model Dependency: High dependency on local Ollama service availability (`http://localhost:11434/v1`).


==================================================
PART 2 — COMPLETE TECHNOLOGY STACK
==================================

FRONTEND / NATIVE MOBILE CLIENT:
* Technology: Android SDK / Native Android Framework
  - Purpose: Provides native mobile OS capabilities, screen reading, touch synthesis, and hardware access.
  - Where used: `apps/mobile-android/app/src/main/java/com/accessbridgeai/`
  - Why used: Operating system accessibility hooks require native Android API integration.
  - Important files: `AccessibilityAgentService.kt`, `MainActivity.kt`, `AndroidManifest.xml`
  - Interaction: Talks to Gateway-Core via WebSockets and local OS via Accessibility APIs.

* Technology: Kotlin & Kotlin Coroutines
  - Purpose: Primary language for Android app development and asynchronous event processing.
  - Where used: Entire `apps/mobile-android` codebase.
  - Why used: Official standard for modern Android engineering; Coroutines manage non-blocking UI and socket jobs (`serviceScope.launch`).
  - Important files: `AccessibilityAgentService.kt`, `VoiceAssistantManager.kt`, `ExecutionEngine.kt`
  - Interaction: Coordinates native OS events, SpeechRecognizer, and WebSocket frame dispatching.

* Technology: Jetpack Compose
  - Purpose: Declarative UI framework for Android.
  - Where used: `apps/mobile-android/app/src/main/java/com/accessbridgeai/ui/`
  - Why used: Renders modern UI screens (Dashboard, Onboarding, Language Selection, Login).
  - Important files: `DashboardScreen.kt`, `OnboardingFlow.kt`, `LoginScreen.kt`
  - Interaction: Observes state flows from `AccessibilityAgentService` and `OnboardingViewModel`.

* Technology: Android SpeechRecognizer API
  - Purpose: Converts spoken user voice audio into text transcripts directly on device.
  - Where used: `VoiceAssistantManager.kt`
  - Why used: Native, low-latency, zero-cost speech-to-text conversion.
  - Important files: `VoiceAssistantManager.kt`
  - Interaction: Sends text transcripts to `AccessibilityAgentService.sendUserIntent()`.

* Technology: Android Biometric API (`BiometricPrompt`)
  - Purpose: Hardware biometric authentication (fingerprint/face unlock).
  - Where used: `MainActivity.kt`
  - Why used: Enforces high-security guardrails prior to executing high-risk financial or sensitive actions.
  - Important files: `MainActivity.kt`, `AccessibilityAgentService.kt`
  - Interaction: Triggered by `CONFIRM_BIOMETRIC` directive from backend.

BACKEND API GATEWAY:
* Technology: Node.js (v20+) & TypeScript
  - Purpose: High-concurrency event-driven API Gateway and WebSocket router.
  - Where used: `services/gateway-core/`
  - Why used: Excellent asynchronous I/O performance for handling persistent client WebSockets and HTTP proxying.
  - Important files: `server.ts`, `wsHandler.ts`, `intentClient.ts`, `env.ts`
  - Interaction: Acts as the central middleware between the Android client, Service 02 (Intent-Safety), and Service 04 (Vision-Perception).

* Technology: Express.js
  - Purpose: HTTP web framework for Node.js.
  - Where used: `services/gateway-core/src/server.ts`
  - Why used: Mounts health check endpoints and HTTP middleware (CORS, compression, pino logging).
  - Important files: `server.ts`
  - Interaction: Manages HTTP server lifecycle and exposes `/health`.

* Technology: ws (WebSocket library for Node.js)
  - Purpose: WebSocket server implementation.
  - Where used: `services/gateway-core/src/websockets/wsHandler.ts`
  - Why used: Standard, lightweight, fast WebSocket server library.
  - Important files: `wsHandler.ts`
  - Interaction: Handles real-time bidirectional frame communication with `WebSocketManager.kt`.

* Technology: Pino & Pino-HTTP
  - Purpose: Structured JSON logging framework.
  - Where used: `services/gateway-core/src/utils/logger.ts`, `server.ts`
  - Why used: High-performance logging with auto-generated correlation request IDs (`genReqId`).
  - Important files: `logger.ts`, `server.ts`
  - Interaction: Attaches `X-Request-ID` tracing headers across service calls.

AI & REASONING SERVICES (SERVICE 02):
* Technology: Python 3.10+ & FastAPI
  - Purpose: High-speed asynchronous web framework hosting the intent planning logic.
  - Where used: `services/intent-safety/`
  - Why used: Python is the standard ecosystem for AI/LLM integration; FastAPI provides fast async performance and Pydantic validation.
  - Important files: `app/main.py`, `app/services/planner/planner_service.py`, `app/config.py`
  - Interaction: Receives compressed DOM state from Gateway-Core via HTTP POST `/api/v1/plan`, returns `ActionPlanResponse`.

* Technology: Pydantic & Pydantic-Settings
  - Purpose: Data validation, schema definition, and environment management.
  - Where used: `services/intent-safety/app/schemas/`, `config.py`
  - Why used: Guarantees strict type safety and JSON schema validation for LLM inputs and outputs.
  - Important files: `request.py`, `action_plan.py`, `plan_schema.py`
  - Interaction: Validates inbound requests from Gateway-Core and structures outbound JSON directives.

* Technology: AsyncOpenAI SDK & Ollama LLM Backend
  - Purpose: LLM inference client connecting to local Ollama instance (`llama3.1:8b` or `llama3.2`).
  - Where used: `services/intent-safety/app/services/planner/llm_client.py`
  - Why used: Executes structured JSON prompt completion locally without incurring token costs or exposing user screen data.
  - Important files: `llm_client.py`, `config.py`
  - Interaction: Converts turn-by-turn user intent and screen topology into JSON action directives.

COMPUTER VISION & PERCEPTION (SERVICE 04):
* Technology: Python 3.10+ & FastAPI
  - Purpose: Computer vision perception service providing OCR and spatial coordinate extraction.
  - Where used: `services/vision-perception/`
  - Why used: Hosts heavy image processing libraries (OpenCV, EasyOCR, PyTorch/YOLO).
  - Important files: `app/main.py`, `app/services/vision_service.py`, `app/config.py`
  - Interaction: Receives Base64 screenshots from Gateway-Core via HTTP POST `/api/v1/analyze`, returns detected bounding boxes and normalized $(x,y)$ tap coordinates.

* Technology: OpenCV (cv2) & NumPy
  - Purpose: Image decoding, grayscale conversion, Gaussian blurring, Canny edge detection, and contour analysis.
  - Where used: `services/vision-perception/app/services/vision_service.py` (`_detect_ui_contours`)
  - Why used: Lightweight, instant UI control box detection without heavy model inference overhead.
  - Important files: `vision_service.py`
  - Interaction: Generates bounding boxes for interactive UI components when DOM metadata is missing.

* Technology: EasyOCR & PyTorch
  - Purpose: Optical Character Recognition (OCR) for reading text on screenshot images.
  - Where used: `services/vision-perception/app/services/vision_service.py` (`_detect_ocr`)
  - Why used: Reads visible text labels directly from rendered image pixels.
  - Important files: `vision_service.py`
  - Interaction: Returns text labels, confidence scores, and bounding boxes.

* Technology: Ultralytics YOLO (Optional / Fallback)
  - Purpose: Object detection model for recognizing UI icons and buttons.
  - Where used: `services/vision-perception/app/services/vision_service.py` (`_detect_yolo`)
  - Why used: Deep learning fallback for complex visual UI elements.
  - Important files: `vision_service.py`
  - Interaction: Blended with OpenCV and EasyOCR detections via Intersection-Over-Union (`_merge_detections`).

DATABASE:
* Technology: None (In-Memory Data Structures)
  - Purpose: Transient state management using Kotlin objects, Node.js `Map`, and Python `dataclass` / dictionary stores.
  - Where used: `SessionManager.kt`, `sessionContexts` in `wsHandler.ts`, `_session_contexts` in `planner_service.py`.
  - Why used: Prototype phase prioritizes speed; persistent database is currently NOT implemented.

INFRASTRUCTURE & CONTAINERS:
* Technology: Docker & Docker Compose
  - Purpose: Containerization and multi-container orchestration.
  - Where used: `docker-compose.yml`, `Dockerfile` in `services/gateway-core`, `services/intent-safety`, `services/vision-perception`.
  - Why used: Allows spinning up Gateway-Core, Intent-Safety, and Vision-Perception services with standardized environments.
  - Important files: `docker-compose.yml`


==================================================
PART 3 — COMPLETE FOLDER / FILE MAP
===================================

accessbridge-ai/
├── apps/
│   └── mobile-android/
│       ├── app/
│       │   ├── src/main/AndroidManifest.xml
│       │   └── src/main/java/com/accessbridgeai/
│       │       ├── AppResolver.kt
│       │       ├── LoopDetector.kt
│       │       ├── MainActivity.kt
│       │       ├── VoiceAssistantManager.kt
│       │       ├── WebSocketManager.kt
│       │       ├── data/
│       │       │   └── SessionManager.kt
│       │       ├── services/
│       │       │   └── AccessibilityAgentService.kt
│       │       ├── ui/
│       │       │   ├── OnboardingViewModel.kt
│       │       │   └── screens/
│       │       │       ├── DashboardScreen.kt
│       │       │       ├── LanguageScreen.kt
│       │       │       ├── LoginScreen.kt
│       │       │       ├── OnboardingFlow.kt
│       │       │       └── WelcomeScreen.kt
│       │       └── utils/
│       │           ├── ExecutionEngine.kt
│       │           ├── LauncherAppResolver.kt
│       │           └── LoopDetector.kt
│       ├── build.gradle.kts
│       └── settings.gradle.kts
├── services/
│   ├── gateway-core/
│   │   ├── package.json
│   │   ├── tsconfig.json
│   │   ├── Dockerfile
│   │   ├── .env
│   │   └── src/
│   │       ├── server.ts
│   │       ├── config/
│   │       │   └── env.ts
│   │       ├── services/
│   │       │   ├── intentClient.ts
│   │       │   ├── sessionManager.ts
│   │       │   ├── sttClient.ts
│   │       │   └── TaskLockManager.ts
│   │       ├── utils/
│   │       │   └── logger.ts
│   │       └── websockets/
│   │           └── wsHandler.ts
│   ├── intent-safety/
│   │   ├── requirements.txt
│   │   ├── Dockerfile
│   │   ├── .env
│   │   └── app/
│   │       ├── config.py
│   │       ├── main.py
│   │       ├── schemas/
│   │       │   ├── action_plan.py
│   │       │   ├── plan_schema.py
│   │       │   └── request.py
│   │       ├── services/
│   │       │   ├── conversation_state.py
│   │       │   ├── safety_service.py
│   │       │   ├── vision_client.py
│   │       │   └── planner/
│   │       │       ├── app_drawer_handler.py
│   │       │       ├── entity_extractor.py
│   │       │       ├── llm_client.py
│   │       │       ├── planner_service.py
│   │       │       ├── state_guards.py
│   │       │       ├── ui_topology.py
│   │       │       └── utils.py
│   │       └── utils/
│   │           └── screen_compressor.py
│   └── vision-perception/
│       ├── requirements.txt
│       ├── Dockerfile
│       └── app/
│           ├── config.py
│           ├── main.py
│           ├── schemas.py
│           └── services/
│               └── vision_service.py
├── shared/
│   ├── json-schemas/ (Empty)
│   └── websocket-events/ (Empty)
├── ai-context/
│   └── 01-PROJECT-HISTORY.md
├── docker-compose.yml
├── package.json
└── README.md

DETAILED FILE ANALYSIS (IMPORTANT FILES):

1. `apps/mobile-android/app/src/main/java/com/accessbridgeai/services/AccessibilityAgentService.kt`
* Purpose: Core native Android service extending `AccessibilityService`. Captures screen node trees, manages session IDs, sends WebSocket frames, receives backend directives, and executes native touch gestures.
* Important Functions: `onServiceConnected()`, `startAssistant()`, `sendUserIntent()`, `handleBackendDirective()`, `executeDirectiveAction()`, `captureScreenStateWithRetry()`, `extractElements()`.
* What Calls It: Android OS lifecycle; native Accessibility Button tap; WebSocket callbacks.
* What It Calls: `WebSocketManager`, `VoiceAssistantManager`, `LauncherAppResolver`, `ExecutionEngine`, `SessionManager`, `LoopDetector`.
* Inputs: `AccessibilityEvent`, WebSocket directive JSON objects, voice speech triggers.
* Outputs: WebSocket JSON payloads (`USER_INTENT`, `USER_INTENT_FEEDBACK`), native touch/scroll/type gestures.
* Dependencies: Android Accessibility APIs, Coroutines, Org.JSON.
* Importance: HIGH (Primary Android Controller).

2. `apps/mobile-android/app/src/main/java/com/accessbridgeai/utils/LauncherAppResolver.kt`
* Purpose: Dynamic fast-path app resolver that scans installed package managers and matches spoken prompts using Double Metaphone phonetic algorithm and Jaro-Winkler string similarity distance.
* Important Functions: `evaluateLaunchIntent()`, `resolveApp()`, `resolveLauncherApps()`.
* What Calls It: `AccessibilityAgentService.sendUserIntent()` and `handleBackendDirective()`.
* What It Calls: Android `PackageManager`, `LauncherApps`.
* Inputs: App name or prompt string, `Context`.
* Outputs: `LaunchGatingResult` (`SIMPLE_LAUNCH`, `AMBIGUOUS_LAUNCH`, `COMPLEX_INTENT`, `NO_LOCAL_MATCH`).
* Dependencies: Android `PackageManager`, `LauncherApps`.
* Importance: HIGH (Eliminates home-screen swiping overhead).

3. `apps/mobile-android/app/src/main/java/com/accessbridgeai/utils/ExecutionEngine.kt`
* Purpose: Primitive action execution engine performing physical UI mutations on Android.
* Important Functions: `executeDirectiveAction()`.
* What Calls It: `AccessibilityAgentService.executeDirectiveAction()`.
* What It Calls: `AccessibilityAgentService` methods (`clickElementByText`, `typeTextIntoFocusedNode`, `performScroll`, `dispatchClickAt`).
* Inputs: Directive JSON object (`action_type`, `target_text`, `package_name`, `tap_x`, `tap_y`).
* Outputs: `ActionResult` (success boolean, status code, failure reason).
* Dependencies: Android Accessibility APIs.
* Importance: HIGH (Physical touch execution layer).

4. `apps/mobile-android/app/src/main/java/com/accessbridgeai/WebSocketManager.kt`
* Purpose: Manages persistent OkHttp WebSocket connection to Gateway-Core with auto-reconnect logic.
* Important Functions: `connect()`, `sendPayload()`, `disconnect()`.
* What Calls It: `AccessibilityAgentService`.
* What It Calls: OkHttp `WebSocket` client.
* Inputs: Server URL, JSON payloads.
* Outputs: Inbound backend JSON directives passed to callback `onMessageReceived`.
* Dependencies: OkHttp3.
* Importance: HIGH (Mobile network communications).

5. `services/gateway-core/src/server.ts`
* Purpose: Gateway-Core entry point. Initializes Express app, HTTP server, Pino logging middleware, compression, and mounts WebSocket server.
* Important Functions: Express setup, `setupWebSocketServer(wss)`.
* What Calls It: Node.js runtime (`npm start` / `npm run dev`).
* What It Calls: `wsHandler.ts`, `env.ts`, `logger.ts`.
* Inputs: Environment variables, HTTP requests, incoming TCP connections.
* Outputs: HTTP server running on port 3000.
* Dependencies: `express`, `ws`, `pino-http`, `compression`.
* Importance: HIGH (Backend Gateway Startup).

6. `services/gateway-core/src/websockets/wsHandler.ts`
* Purpose: WebSocket event multiplexer and session orchestrator. Receives frames from Android, compresses screen node trees (up to 45 nodes), evaluates 0-element escalation chains, routes payloads to Service 02, and queries Service 04 vision fallback.
* Important Functions: `setupWebSocketServer()`, `compressScreenState()`, `shouldRouteToVision()`, `extractScreenNodes()`.
* What Calls It: `server.ts` on WebSocket client message event.
* What It Calls: `IntentClient.evaluateIntent()`, `VisionClient.analyze()`, `SttClient.transcribe()`.
* Inputs: Inbound WebSocket frames from Android client.
* Outputs: Outbound `ResponsePayload` JSON objects to Android client.
* Dependencies: `ws`, `crypto`, `intentClient.js`, `logger.js`.
* Importance: HIGH (Core Backend Orchestrator).

7. `services/gateway-core/src/services/intentClient.ts`
* Purpose: HTTP client wrapper forwarding intent evaluation requests to Service 02 (`http://127.0.0.1:8000/api/v1/plan`) and Service 04 (`http://127.0.0.1:8010/api/v1/analyze`).
* Important Classes: `IntentClient`, `VisionClient`.
* What Calls It: `wsHandler.ts`.
* What It Calls: Backend HTTP endpoints via `fetch`.
* Inputs: `IntentRequestPayload` or vision screenshot payload.
* Outputs: `IntentPlanResponse` or vision detections.
* Dependencies: Native Node.js `fetch`, `TaskLockManager`.
* Importance: HIGH (Service-to-Service HTTP bridge).

8. `services/intent-safety/app/main.py`
* Purpose: Service 02 entry point and API route definitions. Exposes `/health` and POST `/api/v1/plan`.
* Important Functions: `health_check()`, `generate_plan()`, `dispatch_plan()`, `apply_session_metadata()`.
* What Calls It: Uvicorn ASGI server.
* What It Calls: `SafetyService`, `planner_service`, `conversation_state_manager`.
* Inputs: HTTP POST JSON matching `PlannerRequest`.
* Outputs: `ActionPlanResponse` JSON.
* Dependencies: FastAPI, Pydantic.
* Importance: HIGH (Intent Planning API Controller).

9. `services/intent-safety/app/services/planner/planner_service.py`
* Purpose: Brain of the system. Generates structured turn-by-turn action plans using rule-based state guards, topology classifiers, pending context handlers, and LLM completions.
* Important Functions/Classes: `PlannerService.generate_plan()`, `classify_intent_metadata()`, `_get_or_create_session()`.
* What Calls It: `main.py` (`generate_plan`).
* What It Calls: `LLMPlannerClient`, `SafetyService`, `ui_topology`, `entity_extractor`, `app_drawer_handler`, `state_guards`.
* Inputs: User prompt, screen elements, action history, current package, session ID.
* Outputs: `ActionPlanResponse` with ordered `PlanStep` array.
* Dependencies: Python standard library, internal modules.
* Importance: HIGH (Core Multi-Step AI Planner).

10. `services/intent-safety/app/services/planner/llm_client.py`
* Purpose: Encapsulates system prompts and AsyncOpenAI calls to local Ollama inference server (`http://localhost:11434/v1`).
* Important Classes: `LLMPlannerClient`.
* What Calls It: `planner_service.py`.
* What It Calls: Local Ollama / OpenAI API.
* Inputs: Formatted user context prompt, screen topology JSON, conversation history.
* Outputs: Structured JSON matching `ActionPlanResponse` schema.
* Dependencies: `openai`.
* Importance: HIGH (LLM Context & System Prompt Engine).

11. `services/intent-safety/app/services/safety_service.py`
* Purpose: Evaluates prompt and action risk levels (GREEN, YELLOW, RED), enforces 3-tier safety intercepts, and builds `HARD_STOP` / `USER_TAKE_OVER` responses.
* Important Classes/Methods: `SafetyService.evaluate_risk()`, `classify_red_reason()`, `intercept_plan()`, `classify_universal_tier()`.
* What Calls It: `main.py` and `planner_service.py`.
* What It Calls: None (Pure validation rules).
* Inputs: User prompt string, `ActionPlanResponse`, screen nodes.
* Outputs: Risk classification, intercepted action plans.
* Dependencies: Internal schemas.
* Importance: HIGH (Safety & Guardrail Enforcer).

12. `services/vision-perception/app/main.py` & `vision_service.py`
* Purpose: Service 04 entry point and computer vision processing service. Performs OCR text extraction, OpenCV contour analysis, and YOLO object detection on Base64 screenshots.
* Important Functions: `analyze_json()`, `analyze_image()`, `_detect_ui_contours()`, `_detect_ocr()`, `_detect_yolo()`, `_merge_detections()`.
* What Calls It: Uvicorn ASGI server; HTTP requests from Gateway-Core (`VisionClient`).
* What It Calls: OpenCV, EasyOCR, Ultralytics YOLO.
* Inputs: Base64 screenshot image string or uploaded image file.
* Outputs: `VisionAnalyzeResponse` with bounding boxes and normalized tap coordinates.
* Dependencies: OpenCV, NumPy, EasyOCR, PIL, Ultralytics.
* Importance: MEDIUM (Spatial Coordinate Fallback Engine).


==================================================
PART 4 — ENTRY POINTS
=====================

1. FRONTEND / MOBILE CLIENT STARTUP:
* Component: Android `AccessibilityAgentService`
* Trigger: User enables "AccessBridge AI" in Android Settings -> Accessibility, or taps native Accessibility Button.
* Trace:
  START
  → `AccessibilityAgentService.onServiceConnected()`
  → Initialize `SessionManager(this)`
  → Register native Accessibility Button callback (`setupAccessibilityButton()`)
  → Instantiate `WebSocketManager` with server URL `ws://192.168.29.217:3000/ws/agent`
  → Call `webSocketManager.connect()` (Opens OkHttp WebSocket connection)
  → Instantiate `VoiceAssistantManager` (Prepares Android `SpeechRecognizer`)

2. BACKEND API GATEWAY STARTUP:
* Service: Gateway-Core (Service 01)
* Command: `npm start` (or `npm run dev`)
* Trace:
  START
  → `services/gateway-core/src/server.ts`
  → Load config `ENV` from `./config/env.ts`
  → Instantiate Express `app` and HTTP `server`
  → Attach middleware: `compression()`, `cors()`, `express.json()`, `pinoHttp()`
  → Instantiate `WebSocketServer({ server })`
  → Execute `setupWebSocketServer(wss)` in `src/websockets/wsHandler.ts`
  → Set keep-alive timeouts (`keepAliveTimeout = 65000`)
  → `server.listen(ENV.PORT, ...)` (Binds to TCP Port 3000)

3. INTENT & SAFETY PLANNING SERVICE STARTUP:
* Service: Intent-Safety Engine (Service 02)
* Command: `uvicorn app.main:app --host 0.0.0.0 --port 8000`
* Trace:
  START
  → `services/intent-safety/app/main.py`
  → Load configuration `settings` from `app.config` (`Settings()`)
  → Instantiate FastAPI `app` with title and API prefix `/api/v1`
  → Attach `CORSMiddleware`
  → Initialize singletons: `planner_service = PlannerService()`, `conversation_state_manager`
  → Instantiate `LLMPlannerClient()` (Connects to `http://localhost:11434/v1`)
  → Listen on TCP Port 8000

4. VISION PERCEPTION SERVICE STARTUP:
* Service: Vision-Perception Engine (Service 04)
* Command: `uvicorn app.main:app --host 0.0.0.0 --port 8010`
* Trace:
  START
  → `services/vision-perception/app/main.py`
  → Load configuration `settings` from `app.config`
  → Trigger `lifespan` context manager logging configuration (YOLO/OCR flags)
  → Attach `CORSMiddleware` and `log_inference_calls` HTTP timing middleware
  → Lazy-load models on first call (`_get_ocr()` via EasyOCR, `_get_yolo()` via Ultralytics)
  → Listen on TCP Port 8010


==================================================
PART 5 — ACTUAL ARCHITECTURE
==================================================

TEXT ARCHITECTURE DIAGRAM:

+-------------------------------------------------------------------------+
|                         ANDROID DEVICE (CLIENT)                         |
|                                                                         |
|  +--------------------------+          +-----------------------------+  |
|  |  VoiceAssistantManager   |          |  AccessibilityAgentService  |  |
|  | (SpeechRecognizer / TTS) |          | (AccessibilityNodeInfo Tree)|  |
|  +------------+-------------+          +--------------+--------------+  |
|               |                                       |                 |
|               +-------------------+-------------------+                 |
|                                   |                                     |
|                        +----------v----------+                          |
|                        |  WebSocketManager   |                          |
|                        +----------+----------+                          |
+-----------------------------------|-------------------------------------+
                                    |
                        WebSocket (ws://PORT 3000)
                                    |
+-----------------------------------v-------------------------------------+
|                      GATEWAY-CORE (SERVICE 01)                          |
|                                                                         |
|  +-------------------------------------------------------------------+  |
|  |                          wsHandler.ts                             |  |
|  |  - In-Memory Session Contexts Map (sessionContexts)              |  |
|  |  - DOM Tree Compression (compressScreenState: max 45 nodes)       |  |
|  |  - 0-Element Escalation Chain Evaluator                          |  |
|  +------------------+------------------------------+-----------------+  |
+---------------------|------------------------------|--------------------+
                      |                              |
            HTTP POST |                              | HTTP POST
      /api/v1/plan    |                              | /api/v1/analyze
                      v                              v
+----------------------------------+    +---------------------------------+
|    INTENT-SAFETY (SERVICE 02)    |    |  VISION-PERCEPTION (SERVICE 04) |
|         (Python / FastAPI)       |    |        (Python / FastAPI)       |
|                                  |    |                                 |
| +------------------------------+ |    | +-----------------------------+ |
| |       SafetyService          | |    | |   _detect_ui_contours()     | |
| | (Risk Level & Tier Guardrail)| |    | |        (OpenCV)           | |
| +--------------+---------------+ |    | +--------------+--------------+ |
|                |                 |    |                |                |
| +--------------v---------------+ |    | +--------------v--------------+ |
| |       PlannerService         | |    | |        _detect_ocr()        | |
| |  (State Guards & Topology)   | |    | |          (EasyOCR)          | |
| +--------------+---------------+ |    | +--------------+--------------+ |
|                |                 |    |                |                |
| +--------------v---------------+ |    | +--------------v--------------+ |
| |       LLMPlannerClient       | |    | |        _detect_yolo()       | |
| | (AsyncOpenAI -> Ollama Local)| |    | |         (Ultralytics)       | |
| +------------------------------+ |    | +-----------------------------+ |
+----------------------------------+    +---------------------------------+

COMPONENT RESPONSIBILITIES & FAILURE POINTS:

1. Mobile Android Client (`AccessibilityAgentService`):
* Responsibility: Extract UI node trees, listen for voice triggers, execute touch/scroll gestures, play TTS feedback.
* Inputs: Physical user speech, screen window change events, backend WebSocket directives.
* Outputs: Outbound JSON WebSocket payloads (`USER_INTENT`, `USER_INTENT_FEEDBACK`).
* Dependencies: Android Accessibility APIs, SpeechRecognizer.
* Failure Points: Accessibility Service disabled by Android OS; UI nodes unlabelled/hidden; WebSocket network disconnection.

2. Gateway-Core (`wsHandler.ts`):
* Responsibility: Route WebSocket streams, compress screen DOM payload (up to 45 nodes), enforce 0-element escalation rules, proxy HTTP calls to microservices.
* Inputs: Raw JSON WebSocket payloads from mobile client.
* Outputs: Compressed JSON sent to Service 02/04; Directive JSON returned to mobile client.
* Dependencies: Node.js, `ws`, HTTP `fetch`.
* Failure Points: Crash on invalid JSON; memory leak if `sessionContexts` map is never cleared; timeout contacting Service 02.

3. Intent-Safety Service (`planner_service.py`):
* Responsibility: Multi-turn task reasoning, risk evaluation, generating step-by-step UI action directives.
* Inputs: User prompt, compressed screen state, action history, session ID.
* Outputs: `ActionPlanResponse` containing `PlanStep` array.
* Dependencies: FastAPI, Pydantic, Ollama LLM.
* Failure Points: Ollama local service unreachable/down; LLM JSON parsing error; infinite loop generation.

4. Vision-Perception Service (`vision_service.py`):
* Responsibility: Fallback screenshot analysis using OpenCV, EasyOCR, and YOLO to detect visual bounding boxes and tap coordinates.
* Inputs: Base64 screenshot image.
* Outputs: Bounding box array with normalized $(x,y)$ tap coordinates.
* Dependencies: FastAPI, OpenCV, EasyOCR, NumPy.
* Failure Points: Invalid Base64 image payload; high CPU memory pressure during OCR processing.


==================================================
PART 6 — END-TO-END FEATURE FLOWS
=================================

FEATURE 1: Dynamic Fast-Path Application Launching (e.g., "Open WhatsApp")

User action: User taps native Accessibility button and says "Open WhatsApp".
↓
Frontend component/file: `apps/mobile-android/app/src/main/java/com/accessbridgeai/VoiceAssistantManager.kt`
↓
Function: `onResults` callback receives transcript string "Open WhatsApp" and invokes `onSpeechRecognized("Open WhatsApp")`.
↓
Frontend service: `apps/mobile-android/app/src/main/java/com/accessbridgeai/services/AccessibilityAgentService.kt`
↓
Function: `sendUserIntent("Open WhatsApp")`
↓
Local Gating Evaluator: `apps/mobile-android/app/src/main/java/com/accessbridgeai/utils/LauncherAppResolver.kt`
↓
Function: `LauncherAppResolver.evaluateLaunchIntent(context, "Open WhatsApp")`
  - Queries Android `PackageManager` for installed packages.
  - Matches "WhatsApp" using Double Metaphone & Jaro-Winkler distance.
  - Returns `LaunchGatingResult.SIMPLE_LAUNCH(packageName="com.whatsapp", appLabel="WhatsApp")`.
↓
Local Execution: `AccessibilityAgentService.kt` launches app via `LauncherApps.startMainActivity()` locally.
↓
HTTP/WebSocket/event: `AccessibilityAgentService.sendFeedbackAfterAction()` dispatches `USER_INTENT_FEEDBACK` payload over WebSocket to Gateway-Core (`wsHandler.ts`).
↓
Backend Gateway: `wsHandler.ts` receives payload and forwards to Service 02 POST `/api/v1/plan`.
↓
Backend Planner: `planner_service.py` -> `generate_plan()` checks `is_app_foreground("com.whatsapp", "Open WhatsApp")`.
↓
Response: `planner_service.py` returns `ActionPlanResponse` with `action: "FINISH"`, `is_completed: true`, `speech_response: "WhatsApp is open."`.
↓
Frontend: `AccessibilityAgentService.kt` receives response in `handleBackendDirective()`.
↓
UI Result: Phone displays WhatsApp in foreground; TTS speaks "WhatsApp is open."

--------------------------------------------------

FEATURE 2: Multi-Step In-App UI Action (e.g., "Click Search in active app")

User action: User speaks "Click search" while an app is active.
↓
Frontend component: `VoiceAssistantManager.kt` captures transcript "Click search".
↓
Function: `AccessibilityAgentService.sendUserIntent("Click search")`.
↓
Local Evaluator: `LauncherAppResolver.evaluateLaunchIntent()` returns `COMPLEX_INTENT` (bypasses fast-path).
↓
Screen Capture: `AccessibilityAgentService.captureScreenStateWithRetry()` harvests active window `AccessibilityNodeInfo` tree.
↓
WebSocket Dispatch: `sendPayload()` transmits `USER_INTENT` frame containing `screenElements` array to Gateway-Core.
↓
Gateway-Core processing: `wsHandler.ts` receives message, compresses `screenElements` down to max 45 interactive nodes using `compressScreenState()`.
↓
HTTP Proxy: `IntentClient.evaluateIntent()` sends HTTP POST request to Service 02 at `http://127.0.0.1:8000/api/v1/plan`.
↓
Backend FastAPI Endpoint: `services/intent-safety/app/main.py` -> `generate_plan()`.
↓
Safety Intercept: `SafetyService.evaluate_risk("Click search")` returns `RiskLevel.GREEN`.
↓
Planner Engine: `planner_service.py` -> `generate_plan()` evaluates screen topology.
↓
LLM Inference: `llm_client.py` sends prompt to local Ollama instance (`llama3.1:8b`).
↓
Ollama Response: Returns structured JSON directive `action: "CLICK"`, `target_text: "Search"`.
↓
Backend Response: FastAPI returns `ActionPlanResponse` to Gateway-Core.
↓
WebSocket Transmission: `wsHandler.ts` sends `ResponsePayload` JSON frame to Android client.
↓
Android Directive Handler: `AccessibilityAgentService.handleBackendDirective()` receives JSON object.
↓
Execution Engine: `ExecutionEngine.executeDirectiveAction()` calls `AccessibilityAgentService.clickElementByText("Search")`.
↓
UI Result: Accessibility node matching "Search" is clicked; UI updates to search screen.


==================================================
PART 7 — DATA FLOW
==================

1. USER INPUT & SCREEN HARVESTING (ANDROID):
* Raw Speech Transcript: String (e.g., `"Open WhatsApp"`)
* Screen State Object (JSON):
```json
{
  "packageName": "com.whatsapp",
  "screenElements": [
    {
      "text": "Search",
      "content_description": "Search chats",
      "resource_id": "com.whatsapp:id/menuitem_search",
      "class": "android.widget.TextView",
      "is_clickable": true,
      "bounds": { "left": 800, "top": 100, "right": 950, "bottom": 200 }
    }
  ]
}
```

2. WEBSOCKET FRAME TRANSMISSION (CLIENT -> GATEWAY-CORE):
```json
{
  "type": "USER_INTENT",
  "transcript": "Open WhatsApp",
  "user_id": "default_user",
  "input_type": "TEXT",
  "session_id": "550e8400-e29b-41d4-a716-446655440000",
  "task_id": "task_a1b2c3d4",
  "current_package_name": "com.whatsapp",
  "screenElements": [...],
  "dom_hash": "a8f3c1d0...",
  "timestamp": 1700000000000
}
```

3. DOM COMPRESSION & HTTP REQUEST (GATEWAY-CORE -> SERVICE 02):
`compressScreenState()` filters elements: retains nodes where `is_clickable == true` or `is_editable == true` or `text.length > 0`, caps array length to 45 items.
HTTP POST `http://127.0.0.1:8000/api/v1/plan` payload:
```json
{
  "user_id": "default_user",
  "task_id": "task_a1b2c3d4",
  "session_id": "550e8400-e29b-41d4-a716-446655440000",
  "session_language": "en-US",
  "message_type": "USER_INTENT",
  "user_prompt": "Open WhatsApp",
  "current_package_name": "com.whatsapp",
  "screen_state": [
    {
      "text": "Search",
      "content_description": "Search chats",
      "view_id": "com.whatsapp:id/menuitem_search",
      "is_clickable": true
    }
  ],
  "action_history": []
}
```

4. PLANNER DIRECTIVE RESPONSE (SERVICE 02 -> GATEWAY-CORE -> ANDROID):
```json
{
  "task_id": "task_a1b2c3d4",
  "session_id": "550e8400-e29b-41d4-a716-446655440000",
  "language_code": "en-US",
  "execution_state": "COMPLETED",
  "approach": "SERVER_REPLANNING",
  "speech_response": "WhatsApp is open.",
  "action_type": "FINISH",
  "target_text": "",
  "package_name": "",
  "text_to_type": "",
  "scroll_direction": "",
  "execution_mode": "ACCESSIBILITY",
  "error": "",
  "is_completed": true
}
```


==================================================
PART 8 — DATABASE
=================

Database Technology: NONE (In-Memory Data Structures Only).

Details:
* Current Repository Database Status: There is no SQL database (e.g., PostgreSQL, SQLite) or NoSQL database (e.g., MongoDB, Redis) integrated into this repository.
* Where Memory State Lives:
  1. Mobile Android Client: `SessionManager.kt` stores `currentSessionId` in Android SharedPreferences (`accessbridge_session_prefs`). `AccessibilityAgentService` retains in-memory job maps and `executionAttempts`.
  2. Gateway-Core: `wsHandler.ts` maintains a JavaScript `Map<string, TaskContext>` named `sessionContexts` keyed by `sessionId`.
  3. Intent-Safety Service: `conversation_state.py` maintains an in-memory dictionary `_states: Dict[str, SessionState]` in `ConversationStateManager` class, and `planner_service.py` maintains `_session_contexts: Dict[str, SessionContext]`.


==================================================
PART 9 — APIs
=============

INTERNAL HTTP APIs (MICROSERVICES):

1. Service 01 (Gateway-Core) Health Check
* METHOD: GET
* PATH: `/health`
* FILE: `services/gateway-core/src/server.ts`
* FUNCTION: Inline Express handler `app.get("/health")`
* INPUT: None
* OUTPUT: `{ "status": "healthy", "service": "gateway-core", "environment": "development", "timestamp": "..." }`

2. Service 02 (Intent-Safety) Plan Generation Endpoint
* METHOD: POST
* PATH: `/api/v1/plan`
* FILE: `services/intent-safety/app/main.py`
* FUNCTION: `async def generate_plan(request: PlannerRequest) -> ActionPlanResponse`
* INPUT SCHEMA: `PlannerRequest` (`user_prompt`, `user_id`, `session_id`, `task_id`, `current_package_name`, `screen_elements`, `action_history`, etc.)
* VALIDATION: Pydantic field validation; requires `user_prompt` for initial turns.
* PROCESSING: Evaluates prompt safety risk level via `SafetyService`; calls `planner_service.generate_plan()`; applies multi-turn session metadata.
* OUTPUT SCHEMA: `ActionPlanResponse` (`plan_id`, `session_id`, `is_completed`, `execution_state`, `speech_response`, `steps` array).

3. Service 04 (Vision-Perception) Image Analysis Endpoint
* METHOD: POST
* PATH: `/api/v1/analyze`
* FILE: `services/vision-perception/app/main.py`
* FUNCTION: `async def analyze_json(request: VisionAnalyzeRequest) -> VisionAnalyzeResponse`
* INPUT SCHEMA: `VisionAnalyzeRequest` (`screenshot_base64` or `image_base64`, `task_id`, `query_text`)
* VALIDATION: Decodes Base64 string to OpenCV BGR image matrix.
* PROCESSING: Runs `_detect_ui_contours()` (OpenCV), `_detect_ocr()` (EasyOCR), and `_detect_yolo()` (Ultralytics); merges overlapping detections via IOU.
* OUTPUT SCHEMA: `VisionAnalyzeResponse` (`task_id`, `image_width`, `image_height`, `engine`, `matched_element_id`, `elements` array, `screen_state` array).

4. Service 04 Multipart Screenshot Upload Endpoint
* METHOD: POST
* PATH: `/api/v1/analyze-upload`
* FILE: `services/vision-perception/app/main.py`
* FUNCTION: `async def analyze_upload(file: UploadFile, task_id: Optional[str], query_text: Optional[str])`


==================================================
PART 10 — REALTIME / WEBSOCKETS
===============================

Connection Establishment:
* Client: `WebSocketManager.kt` initiates an OkHttp WebSocket connection to `ws://192.168.29.217:3000/ws/agent`.
* Server: `wsHandler.ts` listes via `wss.on("connection", (ws, req) => ...)`. Generates random `clientId` UUID.
* Ping/Pong Keep-Alive: Server sets a 15-second timer (`setInterval`, 15000ms). Sets `ws.isAlive = false` and sends `ws.ping()`. If client responds with `pong`, `ws.isAlive` resets to `true`. Inactive sockets are terminated.

Message Formats:
1. Inbound Text Frame (Client -> Server):
   `{ "type": "USER_INTENT", "transcript": "...", "session_id": "...", "screenElements": [...] }`
2. Hybrid Audio Frame (Client -> Server):
   - Start Frame: `{ "message_type": "HYBRID_AUDIO_START", "task_id": "...", "audio_mime_type": "audio/webm", "screen_state": [...] }`
   - Binary Frames: Binary audio chunk data (`Buffer`).
   - End Frame: `{ "message_type": "HYBRID_AUDIO_END", "task_id": "..." }`
3. Outbound Directive Frame (Server -> Client):
   `ResponsePayload` JSON containing `action_type`, `target_text`, `package_name`, `speech_response`, `is_completed`, `execution_state`.

Reconnection Behavior:
* `WebSocketManager.kt` schedules automatic reconnection attempts on connection loss or failure using exponential backoff up to 30 seconds.


==================================================
PART 11 — AI / LLM COMPONENTS
=============================

* Models & Providers: Local Ollama instance (`llama3.1:8b` or `llama3.2`) configured via `DEFAULT_LLM_MODEL` in `services/intent-safety/app/config.py`. Calls local server via `AsyncOpenAI(base_url="http://localhost:11434/v1")`.
* System Prompts: Defined in `SYSTEM_PROMPT` in `llm_client.py`. Enforces strict JSON output formatting without markdown blocks, foreground package verification, and single-step action directives.
* Structured Outputs: `llm_client.py` enforces response formatting matching `ActionPlanResponse` schema.
* Fallbacks & Safety:
  - If prompt risk is classified as RED by `SafetyService`, LLM call is bypassed completely and a `HARD_STOP` plan is issued.
  - If DOM nodes are missing, execution falls back to Service 04 computer vision spatial coordinates.
  - If 3 consecutive 0-element screen states occur, Gateway-Core executes an automated escalation chain (Rescan -> `SYSTEM_BACK` -> `HARD_STOP`).


==================================================
PART 12 — AUTHENTICATION & SECURITY
===================================

* Authentication: Currently NOT IMPLEMENTED on WebSocket or HTTP REST endpoints. Any client can connect to WebSocket port 3000 or HTTP ports 8000/8010.
* Biometric Security Guardrail: High-risk financial or destructive actions trigger `CONFIRM_BIOMETRIC` directive. Android app displays native `BiometricPrompt` dialog requiring valid fingerprint/face scan before proceeding.
* Environment Variables & Secrets: `.env` files store operational ports and URLs. `GROQ_API_KEY` and `OPENAI_API_KEY` fields exist in `config.py` but default to empty strings or `"ollama"` for local inference.
* Security Weaknesses:
  1. No authentication tokens (JWT/API keys) protecting WebSocket or FastAPI endpoints.
  2. Hardcoded IP address (`192.168.29.217`) inside `AccessibilityAgentService.kt`.
  3. Universal CORS (`allow_origins=["*"]`) enabled on FastAPI backend microservices.


==================================================
PART 13 — ERROR HANDLING
========================

Important Error Paths & Fallbacks:
1. Empty Voice Transcript: If SpeechRecognizer emits an empty transcript, `AccessibilityAgentService.sendUserIntent()` suppresses backend dispatch and speaks a prompt asking the user to repeat their command.
2. Unreachable Backend Gateway: If WebSocket fails to connect, `WebSocketManager.kt` retries connection with backoff.
3. Unreachable Service 02: If Gateway-Core cannot reach Service 02 HTTP endpoint, `IntentClient.evaluateIntent()` catches the error and returns a fallback JSON directive (`speech_response: "Sorry, I encountered an error communicating with the AI planner."`, `action_type: "FINISH"`).
4. Unreachable Service 04 Vision: If Vision service is offline or throws an exception, `VisionClient.analyze()` catches the error silently, logs a warning, and returns `null` so planning can continue with DOM tree only.
5. Loop Detection Recovery: `LoopDetector.kt` on Android tracks sequential identical actions. If the same package, action, and target node repeat 3 times, `LoopDetector` emits `LOOP_DETECTED`. Gateway-Core and Service 02 add the target node to `blacklist_memory` and select an alternative interactive element or issue `SCROLL rescan_wait`.


==================================================
PART 14 — STATE MANAGEMENT
==========================

1. Android Client State:
   - `currentSessionId`: UUID string retained in `AccessibilityAgentService` and persisted in `SessionManager` SharedPreferences.
   - `speechState`: StateFlow emitting `"IDLE"`, `"LISTENING"`, or `"PROCESSING"`.
   - `connectionStatus`: StateFlow emitting `true`/`false` WebSocket connection status.
2. Gateway-Core Backend State:
   - `sessionContexts`: `Map<string, TaskContext>` storing ongoing transcript, action history, and `blacklistMemory` keyed by `sessionId`.
3. Intent-Safety Service State:
   - `conversation_state_manager`: In-memory `ConversationStateManager` mapping `session_id` to `SessionState` enum (`IDLE`, `PROCESSING_DOM`, `AWAITING_USER_CONFIRMATION`, `EXECUTING`, `COMPLETED`, `ERROR`).
   - `_session_contexts`: Dictionary mapping user/session keys to `SessionContext` dataclass containing multi-turn conversation turn history.


==================================================
PART 15 — CONFIGURATION
======================

Service Dependencies & Expected Ports:
* Gateway-Core (Service 01): Port 3000 (HTTP & WebSocket server).
* Intent-Safety (Service 02): Port 8000 (FastAPI HTTP server).
* Vision-Perception (Service 04): Port 8010 (FastAPI HTTP server).
* Local LLM (Ollama): Port 11434 (HTTP API).

Environment Variables (`services/gateway-core/.env`):
* `PORT=3000`
* `NODE_ENV=development`
* `INTENT_SAFETY_URL=http://127.0.0.1:8000/api/v1`
* `VISION_PERCEPTION_URL=http://127.0.0.1:8010/api/v1`

Environment Variables (`services/intent-safety/.env`):
* `APP_NAME=Service 02`
* `PORT=8000`
* `API_PREFIX=/api/v1`
* `DEFAULT_LLM_MODEL=llama3.1:8b`
* `USE_LLM_MOCK=False`
* `VISION_PERCEPTION_URL=http://127.0.0.1:8010/api/v1`


==================================================
PART 16 — HOW TO RUN THE PROJECT
================================

Prerequisites:
1. Node.js (v20+), npm
2. Python 3.10+
3. Android Studio (with Android SDK 34 / Android 14+)
4. Ollama (running locally with `llama3.1:8b` pulled)

Startup Procedure (Service Order):

Step 1: Start Local Ollama Server
Command: `ollama run llama3.1:8b` (Verifies port 11434 is active).

Step 2: Start Service 02 (Intent-Safety)
Commands:
`cd services/intent-safety`
`python -m venv venv`
`venv\Scripts\activate` (Windows)
`pip install -r requirements.txt`
`uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload`
Verify: Open `http://127.0.0.1:8000/health` in browser -> should return `{"status": "healthy"}`.

Step 3: Start Service 04 (Vision-Perception)
Commands:
`cd services/vision-perception`
`python -m venv venv`
`venv\Scripts\activate` (Windows)
`pip install -r requirements.txt`
`uvicorn app.main:app --host 0.0.0.0 --port 8010 --reload`
Verify: Open `http://127.0.0.1:8010/health` in browser -> should return `{"status": "healthy"}`.

Step 4: Start Service 01 (Gateway-Core)
Commands:
`cd services/gateway-core`
`npm install`
`npm run dev`
Verify: Open `http://127.0.0.1:3000/health` in browser -> should return `{"status": "healthy"}`.

Step 5: Run Mobile Android Client
- Open `apps/mobile-android` in Android Studio.
- Update server URL in `AccessibilityAgentService.kt` to point to host computer IP (or `10.0.2.2` if running inside Android Emulator).
- Build and deploy APK to device/emulator.
- Enable "AccessBridge AI" in Android Settings -> Accessibility.


==================================================
PART 17 — CURRENT BUGS / PROBLEMS
=================================

PROBLEM 1: Empty `target_text: ""` Infinite Rescan Loop
* Location: `services/intent-safety/app/services/planner/planner_service.py` & `services/gateway-core/src/websockets/wsHandler.ts`.
* Why it is a problem: When Service 02 emits a `CLICK` directive with an empty target (`target_text: ""`), Android Accessibility fails to click an empty string target, returning `actionStatus: "FAILED"`. Gateway-Core then responds with `SCROLL` / `target_text: "rescan_wait"`, causing an infinite loop between Gateway-Core and Service 02.
* Impact: High CPU usage and execution failure during multi-step UI automation.
* Evidence: Documented in `01-PROJECT-HISTORY.md` (Section 7, Problem 1) and visible in `wsHandler.ts` lines 548-557.

PROBLEM 2: Installed Apps Reported as "Not Installed" on Multi-Step Prompts
* Location: `apps/mobile-android/app/src/main/java/com/accessbridgeai/utils/LauncherAppResolver.kt` & `services/intent-safety/app/services/planner/planner_service.py`.
* Why it is a problem: When a user says a multi-step command like "cab on Ola", `LauncherAppResolver` classifies it as `COMPLEX_INTENT` and bypasses native package launching. The task is sent to Service 02, which attempts to navigate home (`GO_HOME`) and swipe up to find the app drawer (`SWIPE_UP`). On custom launchers (e.g. Samsung OneUI), visual app drawer scanning fails and reports the app as "Not Installed".
* Impact: Native installed apps fail to launch.
* Evidence: Documented in `01-PROJECT-HISTORY.md` (Section 7, Problem 2).

PROBLEM 3: Missing `/classify-intent` Context Routing in Gateway-Core
* Location: `services/gateway-core/src/websockets/wsHandler.ts`.
* Why it is a problem: User verbal responses to system confirmation prompts (e.g., "Yeah sure go ahead") are sent to `/api/v1/plan` instead of a dedicated intent classification endpoint, causing the LLM planner to misinterpret conversational confirmations as UI layout targets.
* Impact: Confirmation prompts fail or trigger random UI clicks.
* Evidence: Documented in `01-PROJECT-HISTORY.md` (Section 7, Problem 3).

PROBLEM 4: Hardcoded Server IP Address in Android Client
* Location: `apps/mobile-android/app/src/main/java/com/accessbridgeai/services/AccessibilityAgentService.kt` (Line 87).
* Why it is a problem: The WebSocket server URL is hardcoded to `ws://192.168.29.217:3000/ws/agent`. Connecting from another Wi-Fi network or Android Emulator fails immediately without clear error reporting.
* Impact: Application fails to connect outside developer's local network.


==================================================
PART 18 — INCOMPLETE FEATURES
=============================

1. Play Store Fallback Installation Flow
* Status: PARTIALLY COMPLETE / PLACEHOLDER
* Evidence: `planner_service.py` lines 205-264 contains logic to emit a 3-step plan (`OPEN_APP` com.android.vending -> `TYPE_TEXT` search -> `CLICK` Install), but automated installation confirmation on-device is untested.

2. Shared JSON Schemas Directory
* Status: NOT IMPLEMENTED / PLACEHOLDER
* Evidence: `shared/json-schemas` and `shared/websocket-events` folders are empty 0-byte directories.

3. Database Persistent Storage & Telemetry
* Status: NOT IMPLEMENTED
* Evidence: No database connection code or ORM configurations exist in any service.

4. YOLO Computer Vision Object Detection
* Status: PARTIALLY COMPLETE (FALLBACK TO OPENCV)
* Evidence: `vision_service.py` line 85 catches YOLO load failures silently and falls back to OpenCV UI contours.


==================================================
PART 19 — IMPORTANT DESIGN DECISIONS
====================================

EXPLICITLY DOCUMENTED DECISION 1: Local Ollama LLM Inference over Cloud APIs
* Choice: Local Ollama instance running `llama3.1:8b` / `llama3.2`.
* Documented Reason: Zero API token expenses, complete privacy for sensitive on-screen data, offline capability, low latency.
* Source: `ai-context/01-PROJECT-HISTORY.md` (Section 4, Decision 1).

EXPLICITLY DOCUMENTED DECISION 2: Zero Hardcoding for App Names and Intent Matching
* Choice: Algorithmic matching (`Double Metaphone` + `Jaro-Winkler` in `LauncherAppResolver.kt`) and LLM intent classification (`AFFIRMATIVE` / `NEGATIVE`).
* Documented Reason: Scales to thousands of Android applications without maintaining hardcoded dictionary lists.
* Source: `ai-context/01-PROJECT-HISTORY.md` (Section 4, Decision 2).

INFERRED DECISION 3: Microservice Architecture over Monolith
* Choice: 4 independent microservices (Android Client, Gateway-Core Node.js, Intent-Safety Python, Vision-Perception Python).
* Inferred Reason: Decouples platform-specific native Android APIs from TypeScript network concurrency and heavy Python AI/CV processing.


==================================================
PART 20 — PROJECT HISTORY VISIBLE FROM CODE
===========================================

* Migration Remnants in App Resolver: `AppResolver.kt` (lines 1-49) exists alongside `LauncherAppResolver.kt` (under `utils/`). `AppResolver.kt` is an older, simpler exact/partial label matching object, whereas `LauncherAppResolver.kt` is the upgraded fast-path resolver incorporating phonetic algorithms.
* Abandoned App Drawer Swiping: Earlier implementations relied on issuing `GO_HOME` and `SWIPE_UP` to find apps visually. This approach was superseded by direct native launching via `PackageManager.getLaunchIntentForPackage()`.
* Transition from Legacy Node-to-Node Planning to Hybrid DOM+Vision: Code in `wsHandler.ts` shows an evolution where screenshot Base64 data is analyzed by Service 04 only when Accessibility DOM nodes are sparse or missing (0-element escalation chain).


==================================================
PART 21 — WHAT I ACTUALLY NEED TO LEARN
======================================

LEVEL 1 (ABSOLUTELY CRITICAL TO UNDERSTAND):
1. Android Accessibility APIs & Node Trees:
   - Where it appears: `AccessibilityAgentService.kt` (`captureScreenStateWithRetry()`, `extractElements()`).
   - Why you need it: AccessBridge AI relies on harvesting `AccessibilityNodeInfo` objects to understand what is on the smartphone screen.
   - Files to study: `apps/mobile-android/app/src/main/java/com/accessbridgeai/services/AccessibilityAgentService.kt`.

2. WebSocket Event Handling & Payload Routing:
   - Where it appears: `wsHandler.ts` & `WebSocketManager.kt`.
   - Why you need it: All real-time communication between the phone and AI backend streams through WebSocket messages.
   - Files to study: `services/gateway-core/src/websockets/wsHandler.ts`.

3. LLM Agent System Prompting & Structured Output:
   - Where it appears: `llm_client.py` & `planner_service.py`.
   - Why you need it: Understand how screen elements are formatted into prompt context and parsed into JSON directives.
   - Files to study: `services/intent-safety/app/services/planner/llm_client.py` and `planner_service.py`.

LEVEL 2 (SHOULD UNDERSTAND):
1. Phonetic & Fuzzy String Matching Algorithms (`Double Metaphone`, `Jaro-Winkler`).
   - Study: `LauncherAppResolver.kt`.
2. Microservice Communication & Asynchronous Python (`FastAPI`, `Pydantic`, `uvicorn`).
   - Study: `services/intent-safety/app/main.py` & `config.py`.

LEVEL 3 (USEFUL LATER):
1. Computer Vision Processing (`OpenCV`, `EasyOCR`, `YOLO`).
   - Study: `services/vision-perception/app/services/vision_service.py`.


==================================================
PART 22 — INTERVIEW KNOWLEDGE
=============================

1. WebSockets vs. REST for Real-Time Mobile Systems:
   - AccessBridge AI uses WebSockets because turn-by-turn mobile automation requires bi-directional streaming, low latency (<50ms frame dispatch), and full-duplex communication.

2. Microservice Architecture & API Gateways:
   - Demonstrates separating high-concurrency event routing (Node.js Gateway-Core) from AI/ML computation (Python FastAPI microservices).

3. Algorithm Application (Phonetic Search & String Distance):
   - Demonstrates solving voice-to-text spelling errors using Double Metaphone and Jaro-Winkler string similarity instead of expensive database lookups.

4. Resiliency Patterns (Fallback Chains & Escalation):
   - Demonstrates 3-tier fallback execution: Fast-Path Native Launch -> Accessibility DOM Tree -> Computer Vision Spatial Coordinates.


==================================================
PART 23 — CRITICAL FLOWS I MUST UNDERSTAND
==========================================

RANK 1: Voice Intent to Native Action Execution Flow
* Starting file: `VoiceAssistantManager.kt`
* Ending result: Native UI touch gesture executed on Android.
* Files involved: `VoiceAssistantManager.kt` -> `AccessibilityAgentService.kt` -> `wsHandler.ts` -> `main.py` (Service 02) -> `planner_service.py` -> `ExecutionEngine.kt`.

RANK 2: Fast-Path App Resolution Flow
* Starting file: `AccessibilityAgentService.kt` (`sendUserIntent`)
* Ending result: Native application launched via OS intent in < 50ms.
* Files involved: `AccessibilityAgentService.kt`, `LauncherAppResolver.kt`.

RANK 3: DOM Compression & Screen State Packaging
* Starting file: `AccessibilityAgentService.kt` (`captureScreenStateWithRetry`)
* Ending result: Clean JSON array of max 45 interactive nodes delivered to Service 02.
* Files involved: `AccessibilityAgentService.kt`, `wsHandler.ts` (`compressScreenState`).

RANK 4: 0-Element Escalation & Screen Rescan Recovery
* Starting file: `wsHandler.ts`
* Ending result: Automated recovery via rescan, overlay clearing (`SYSTEM_BACK`), or emergency abort (`HARD_STOP`).
* Files involved: `wsHandler.ts`, `planner_service.py`.

RANK 5: Safety Intercept & Biometric Authentication Flow
* Starting file: `services/intent-safety/app/services/safety_service.py`
* Ending result: Biometric prompt displayed on Android device before execution.
* Files involved: `safety_service.py`, `main.py`, `wsHandler.ts`, `MainActivity.kt`.


==================================================
PART 24 — PROJECT GLOSSARY
==========================

* AccessibilityNodeInfo: An Android OS system object representing a single UI node (button, text box, image) on the screen.
* Fast-Path Execution: Local client-side resolution that executes commands directly on device without sending network requests to backend AI services.
* Double Metaphone: A phonetic algorithm that encodes spoken words into key codes based on their English pronunciation.
* Jaro-Winkler Distance: A string metric measuring edit distance between two sequences, giving higher scores to strings that match from the beginning.
* Gateway-Core: Service 01 in the architecture; acts as the central API Gateway and WebSocket hub.
* Service 02 (Intent-Safety): The Python/FastAPI microservice hosting AI planning logic and safety guardrails.
* Service 04 (Vision-Perception): The Python/FastAPI microservice providing computer-vision fallback analysis.
* 0-Element Escalation Chain: An automated retry sequence triggered when the Android client extracts 0 screen elements.


==================================================
PART 25 — MASTER PROJECT MAP
============================

PROJECT STRUCTURE MAP:

accessbridge-ai/
├── apps/
│   └── mobile-android/ (Native Kotlin Android Client)
├── services/
│   ├── gateway-core/ (Node.js / TypeScript WebSocket API Gateway)
│   ├── intent-safety/ (Python / FastAPI AI Planner & Safety Engine)
│   └── vision-perception/ (Python / FastAPI Computer Vision Engine)
├── shared/ (Shared Schemas Placeholder)
└── ai-context/ (Project History & Decision Context)

IF YOU WANT TO UNDERSTAND THIS PROJECT, READ THESE FILES IN THIS EXACT ORDER:

1. `ai-context/01-PROJECT-HISTORY.md` (Read first for deep background context and design rationale)
2. `apps/mobile-android/app/src/main/java/com/accessbridgeai/services/AccessibilityAgentService.kt` (Primary Android controller entry point)
3. `apps/mobile-android/app/src/main/java/com/accessbridgeai/utils/LauncherAppResolver.kt` (Local fast-path app launch engine)
4. `services/gateway-core/src/server.ts` & `src/websockets/wsHandler.ts` (Backend Gateway entry point and WebSocket orchestrator)
5. `services/intent-safety/app/main.py` & `app/services/planner/planner_service.py` (AI Planner FastAPI API entry point and reasoning engine)
6. `services/intent-safety/app/services/planner/llm_client.py` (System prompts and Ollama LLM integration)
7. `services/intent-safety/app/services/safety_service.py` (Safety interceptors and guardrail rules)
8. `services/vision-perception/app/services/vision_service.py` (Computer vision OCR and spatial coordinate engine)

==================================================
KNOWN UNCERTAINTIES
===================

1. Model Fine-Tuning Status: It is unknown whether the local Ollama `llama3.1:8b` model uses a customized GGUF quant file or a standard base model.
2. Play Store Live Execution: Automated on-device execution of the 3-step Play Store installation flow has not been empirically verified across custom launcher UIs.
3. Network Latency Boundaries: Empirical latency figures under poor 3G/4G cellular connections are unknown since all testing occurred over local Wi-Fi.

==================================================
QUESTIONS THAT CANNOT BE ANSWERED FROM THE CODE ALONE
==================================================

1. Which specific mobile devices / Android OS versions (e.g. Android 12 vs 14) and custom Android OEM skins (e.g. Samsung OneUI, Xiaomi MIUI, Google Pixel Stock) have been physically tested on-device?
2. What are the specific performance constraints (GPU/RAM) of the local host machine running the Ollama service?
3. What is the intended deployment timeline for persistent database storage and multi-user authentication?

