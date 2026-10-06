# PROJECT HISTORY & DECISION CONTEXT: ACCESSBRIDGE AI

---

## 1. PROJECT ORIGIN

* **Why did we start this project?**
* Modern smartphone operating systems (specifically Android) remain heavily visual and touch-centric, creating significant accessibility barriers for users with physical motor impairments, visual disabilities, or situational limitations.
* Existing automation solutions are either fragile macro-recordings, restricted by limited official app APIs, or reliant on cloud LLMs that introduce high latency, token costs, and screen privacy concerns.


* **What problem were we trying to solve?**
* Enabling a voice-first, persistent, multi-step smartphone automation system that can dynamically launch applications, resolve phonetic/spoken misspellings, navigate arbitrary third-party application interfaces without hardcoded screen coordinates or app-specific scripts, and safely execute user tasks.


* **Who was it intended for?**
* Individuals with motor or visual impairments needing voice-first mobile access, as well as hands-free automation scenarios. (It is also developed as a major academic/capstone project for a Bachelor of Technology in Information Technology degree).


* **What was the original goal?**
* To build a 4-tier hybrid mobile automation engine (**AccessBridge AI**) connecting a native Android Accessibility Client with local AI reasoning (Ollama) and vision perception, operating under zero-hardcoding principles and protected by safety guardrails.



---

## 2. ORIGINAL ARCHITECTURE

* **What architecture did we originally discuss?**
* A 4-service hybrid architecture dividing local fast-path execution, network orchestration, LLM reasoning, and vision perception across microservices.


* **What components did we plan?**
1. **Native Execution (Android Client):** Kotlin-based client using Android `AccessibilityService`, native `PackageManager`, and `SpeechRecognizer`.
2. **Gateway-Core (Service 01):** Node.js / TypeScript service managing real-time WebSocket communication, payload routing, session state persistence, DOM compression, and safety stops.
3. **Intent & Safety Engine (Service 02):** Python / FastAPI service hosting local Ollama LLM inference (`llama3.2` / `qwen2.5`) for parsing compressed DOM states into structured action JSON directives (`OPEN_APP`, `CLICK`, `TYPE_TEXT`, `SCROLL`, `FINISH`).
4. **Vision & Perception (Service 04):** Python service utilizing OCR (PaddleOCR) / multi-modal vision to process Base64 screenshots and return spatial $(x, y)$ tap coordinates when UI metadata is missing.


* **What technologies were selected & why?**
* **Kotlin (Android):** Native OS access, `AccessibilityNodeInfo` inspection, and `AccessibilityService` lifecycle integration.
* **Node.js / TypeScript (Gateway-Core):** High-concurrency event-driven WebSocket handling and lightweight DOM compression/tree stripping.
* **Python / FastAPI + Ollama (Service 02):** Rapid AI/ML integration, structured JSON schema generation (`format="json"` in Ollama), and local/edge LLM inference (`llama3.2` 3B/8B) for privacy, zero token cost, and sub-second reasoning latency.
* **WebSockets:** Real-time bi-directional streaming between Android client and backend services.



---

## 3. ARCHITECTURE EVOLUTION

* **CHANGE 1: App Launch Routing (Fast-Path vs. Server Navigation)**
* **OLD APPROACH:** When a user requested an app (e.g., *"Open Ola"* or *"Cab on Ola"*), if extra action tokens were present, the client passed the full request to Service 02. Service 02 then tried to navigate to the Android home screen (`GO_HOME`), swipe up to open the App Drawer (`SWIPE_UP`), and visually search for the app icon.
* **NEW APPROACH:** Client-side `AppResolver` uses native `PackageManager` queries, Double Metaphone phonetic encoding, and Jaro-Winkler string similarity to detect installed app packages locally. If an installed package match is found (even in multi-step prompts like *"Cab on Ola"*), the Android client immediately triggers native launch via `packageManager.getLaunchIntentForPackage(packageName)` before passing screen control to Service 02.
* **WHY WE CHANGED IT:** Home-screen swiping was extremely slow, unreliable across custom Android launchers (e.g., Samsung OneUI vs. Stock Android), and frequently reported installed apps as "Not Installed".
* **WHAT PROBLEM IT SOLVED:** Bypassed UI navigation overhead, enabling sub-50ms app launches and preventing false "App Not Installed" failures.
* **WHAT MAY STILL REMAIN FROM THE OLD APPROACH:** Legacy prompt templates in Service 02 that suggest issuing `GO_HOME` or `SWIPE_UP` to find apps if `package_name` is omitted.


* **CHANGE 2: Dynamic Confirmation Intent Routing**
* **OLD APPROACH:** All incoming WebSocket messages—including follow-up user answers to system confirmation questions like *"Did you mean Nykaa Fashion?"*—were routed directly to Service 02's main planning endpoint (`/api/v1/plan`).
* **NEW APPROACH:** Gateway-Core inspects frame payloads for `confirmation_context` or `context_type` (`APP_CONFIRMATION` or `PLAY_STORE_INSTALL`). If present, Gateway-Core routes the turn to a specialized intent classification endpoint (`/api/v1/classify-intent`).
* **WHY WE CHANGED IT:** The main planner (`/plan`) attempted to interpret conversational confirmation phrases like *"Yeah sure go ahead"* as UI layout navigation commands, causing action execution failures.
* **WHAT PROBLEM IT SOLVED:** Separated multi-turn UI planning from conversational state evaluation.



---

## 4. MAJOR TECHNICAL DECISIONS

* **DECISION 1: Local Ollama LLM Inference over Cloud APIs (OpenAI/Anthropic)**
* **OPTIONS CONSIDERED:** Cloud LLM APIs (GPT-4o/Claude) vs. Local open-source models via Ollama (`llama3.2`, `qwen2.5`).
* **CHOSEN OPTION:** Local Ollama instance.
* **REASON:** Zero API token expenses, total privacy for sensitive on-screen data, offline capability, and low latency.
* **TRADEOFF:** Constrained parameter size (3B–8B) requires strict system prompts and structured JSON constraints.
* **CURRENT STATUS:** Documented & Active.


* **DECISION 2: Zero Hardcoding for App Names and Confirmation Intent**
* **OPTIONS CONSIDERED:** Hardcoded dictionaries/if-statements (e.g., `if transcript == "yes"`, or string-mapping `"mica"` -> `"nykaa"`) vs. Dynamic algorithmic resolution (`Double Metaphone` + `Jaro-Winkler`) and LLM classification (`AFFIRMATIVE` / `NEGATIVE` / `AMBIGUOUS`).
* **CHOSEN OPTION:** Fully dynamic algorithmic and LLM-based classification.
* **REASON:** Scaling to thousands of Android apps without maintenance bloat and supporting flexible human voice responses (*"yeah sure"*, *"why not"*, *"nope"*, *"cancel"*).
* **TRADEOFF:** Requires edge-case handling for ambiguous phonetic scores ($0.60 \le \text{score} < 0.85$).
* **CURRENT STATUS:** Documented & Implemented.


* **DECISION 3: Native Accessibility Shortcut Icon vs. Floating Overlay Window**
* **OPTIONS CONSIDERED:** Custom floating overlay window requiring `SYSTEM_ALERT_WINDOW` permissions vs. Native Android Accessibility Button (`flagRequestAccessibilityButton` / `onAccessibilityButtonClicked`).
* **CHOSEN OPTION:** Native Android Accessibility Button / Shortcut.
* **REASON:** Lighter weight, cleaner system integration, eliminates intrusive overlay permission requirements, and naturally binds to `AccessibilityAgentService`.
* **TRADEOFF:** Tied to device navbar/side-key gesture configurations.
* **CURRENT STATUS:** Documented & Active.


* **DECISION 4: Modular Codebase Refactoring**
* **OPTIONS CONSIDERED:** Continuing in monolith files (`AccessibilityAgentService.kt`, `server.ts`) vs. Modularizing into dedicated handlers (`SessionManager.kt`, `AppResolver.kt`, `sessionContextManager.ts`, `intentValidator.ts`).
* **CHOSEN OPTION:** Modular architecture approved.
* **REASON:** File bloat was making debugging difficult and increasing risk of regression.
* **CURRENT STATUS:** Strategy approved; execution was planned incrementally. *(Inference: Verify repository to confirm how far file splitting was completed).*



---

## 5. FEATURES WE PLANNED

* **Feature 1: Dynamic App Launcher with Phonetic & Fuzzy Matching**
* **Original Purpose:** Resolve launch requests dynamically, including phonetic misspellings (*"Mica"* $\rightarrow$ *"Nykaa"*).
* **Implementation Approach:** `PackageManager` scanning + Double Metaphone + Jaro-Winkler similarity scoring.
* **Current Known Status:** Implemented in `AppResolver.kt`.
* **Important Unfinished Parts:** Fine-tuning threshold boundaries between $0.60$ and $0.85$.


* **Feature 2: Dynamic LLM Confirmation Flow**
* **Original Purpose:** Ask users for verbal confirmation when app matching is ambiguous ($0.60 \le \text{score} < 0.85$).
* **Implementation Approach:** System speaks TTS prompt; user voice response is sent via WebSocket to `/api/v1/classify-intent` where Ollama returns `AFFIRMATIVE`, `NEGATIVE`, or `AMBIGUOUS`.
* **Current Known Status:** Designed and prompts constructed.


* **Feature 3: Missing App Play Store Fallback**
* **Original Purpose:** Handle requests for uninstalled applications.
* **Implementation Approach:** If `AppResolver` returns `NOT_INSTALLED` ($\text{score} < 0.60$), system asks via TTS if user wants to download it from Play Store. Upon `AFFIRMATIVE`, client issues `market://details?id=<package_name>` or Play Store search intent, then passes Play Store DOM to Service 02 to click "Install".
* **Current Known Status:** Designed and prompts constructed.


* **Feature 4: Global Session Persistence across App Boundaries**
* **Original Purpose:** Maintain conversation and task context (`global_session_id`, `actionHistory`) when switching across different applications.
* **Implementation Approach:** Long-running `AccessibilityAgentService` maintains an in-memory `currentSessionId` across native accessibility button taps; Gateway-Core stores context in an in-memory Map keyed by `sessionId` with an inactivity timeout (e.g., 5 minutes).
* **Current Known Status:** Implemented and verified via server logs.


* **Feature 5: DOM Tree Compression**
* **Original Purpose:** Reduce token footprint and payload size sent to Service 02.
* **Implementation Approach:** Strip non-interactive layout containers, empty text nodes, and irrelevant attributes from `AccessibilityNodeInfo` trees (up to 80% compression).
* **Current Known Status:** Fully operational in Gateway-Core.



---

## 6. FEATURES WE ACTUALLY BUILT

*(Based on explicit conversation logs and verified server outputs)*

* **Confirmed Built & Operational:**
1. Native WebSocket streaming between Kotlin client and Gateway-Core.
2. In-memory `global_session_id` persistence maintaining context across app switches and button presses.
3. DOM tree extraction and compression pipeline (reducing raw UI nodes to compressed JSON).
4. Single-action execution primitives (`OPEN_APP`, `CLICK`, `TYPE_TEXT`, `SCROLL`, `FINISH`).
5. Local fast-path app resolution via `AppResolver.kt` (using `PackageManager`, Double Metaphone, and Jaro-Winkler distance).
6. Local Ollama integration (`llama3.2`) returning structured JSON planner directives.



---

## 7. IMPORTANT PROBLEMS WE ENCOUNTERED

* **Problem 1: Infinite `rescan_wait` Loop in Multi-Step Execution**
* **What Happened:** Service 02 issued a `CLICK` directive with an empty string target (`target_text: ""`). Android failed to click an empty target, returning `actionStatus: "FAILED"`. Service 02 then responded with `SCROLL` / `target_text: "rescan_wait"`. Android captured the UI and sent `actionStatus: "FAILED"` again, causing Service 02 and Gateway-Core to loop infinitely.
* **Suspected Cause:** Service 02 lacked validation forcing `CLICK` or `TYPE_TEXT` directives to contain non-empty target text or node IDs, and lacked a retry-limit guard.
* **What We Tried:** Identified the root cause in Gateway-Core logs; designed retry guard rules and non-empty target validation.
* **Current Known Status:** Problem diagnosed; fix rules formulated.


* **Problem 2: Installed App Reported as "Not Installed" (e.g., Ola / Nykaa)**
* **What Happened:** User said *"cab on Ola"*. `AppResolver` returned `MULTI_STEP_INTENT` due to the word *"cab"*. It bypassed native launching and sent the task to Service 02. Service 02 issued `GO_HOME` and `SWIPE_UP` to open the App Drawer, failed to locate the icon visually on Samsung OneUI, and falsely declared: *"The app 'Ola' is not installed..."*.
* **Suspected Cause:** Bypassing native `getLaunchIntentForPackage()` on multi-step prompts when a package match was already found.
* **What We Tried:** Revised `AppResolver` rules to ensure that if a `packageName` is matched with $\text{score} \ge 0.60$, Android launches the package natively via System Intent immediately, regardless of leftover tokens.
* **Current Known Status:** Root cause identified; prompt instructions delivered.


* **Problem 3: Missing `/classify-intent` Routing in Gateway-Core**
* **What Happened:** Confirmation voice responses (*"Yeah sure"*) were routed to `/api/v1/plan` instead of an intent classification endpoint.
* **Suspected Cause:** Gateway-Core blindly forwarded all incoming WebSocket payloads to `/api/v1/plan`.
* **What We Tried:** Formulated routing logic in Gateway-Core to inspect payloads for `confirmation_context` and redirect to `/api/v1/classify-intent`.
* **Current Known Status:** Root cause identified; prompt instructions delivered.



---

## 8. FAILED / ABANDONED APPROACHES

* **ABANDONED 1: Naive Start-of-Sentence Verb Stripping**
* **Why Abandoned:** Initially considered stripping verbs like `"open"` or `"launch"` from the start of voice inputs. Abandoned because it broke action-oriented commands like *"Play music on Spotify"* or *"Send message on WhatsApp"*, where verbs like *"Play"* or *"Send"* are essential to the multi-step intent.
* **Replacement:** Sentence structure analysis that checks for leftover action tokens outside prepositions (`"on"`, `"in"`, `"using"`).


* **ABANDONED 2: App Drawer Swiping (`GO_HOME` + `SWIPE_UP`) for App Launching**
* **Why Abandoned:** Highly brittle, launcher-dependent, slow, and prone to false "Not Installed" errors.
* **Replacement:** Direct native OS launching via `PackageManager.getLaunchIntentForPackage()`.



---

## 9. IMPORTANT PROJECT REQUIREMENTS

* **MUST HAVE:**
* Zero-hardcoding principle (no static app dictionaries or hardcoded string comparisons for user responses).
* Local LLM execution via Ollama (no cloud token dependencies for core planning).
* Dynamic app resolution with phonetic mismatch handling (Double Metaphone + Jaro-Winkler).
* Global session persistence (`global_session_id`) across cross-app navigation.
* Strict JSON output schemas from Service 02.
* Non-root operation on standard consumer Android devices using Accessibility APIs.


* **SHOULD HAVE:**
* Dynamic LLM-driven confirmation classification (`AFFIRMATIVE` / `NEGATIVE` / `AMBIGUOUS`).
* Play Store installation fallback flow for uninstalled apps.
* Vision fallback (Service 04) when DOM nodes are missing or unlabelled.
* Modularized file structure across Android and Node.js codebases.


* **NICE TO HAVE:**
* Anonymized telemetry feedback loop for execution failure learning.
* Cross-platform expansion.


* **ABANDONED:**
* Hardcoded trigger word lists for confirmation (`"yes"`, `"sure"`, `"ok"`).
* Visual App Drawer navigation for opening installed apps.



---

## 10. SECURITY / SAFETY DECISIONS

* **Local Data Boundary:** On-screen accessibility text and compressed DOM trees are processed locally via Ollama to avoid exposing user screen data to third-party cloud LLM endpoints.
* **Human-in-the-Loop Interception:** High-risk actions (e.g., financial payments, data deletion) require explicit user voice confirmation before the engine executes the action.
* **Emergency Override:** Emergency `HARD_STOP` signals or `SYSTEM_BACK` triggers halt automation immediately if an execution loop occurs.

---

## 11. PERFORMANCE / COST DECISIONS

* **Fast-Path Latency Optimization:** Simple app launches are executed locally on the Android device in $< 5\text{ ms}$, entirely bypassing network requests and LLM evaluation.
* **DOM Compression:** Gateway-Core compresses raw Android Accessibility Node trees by up to 80% before making HTTP calls to Service 02, saving context window space and speeding up Ollama inference.
* **Cost Elimination:** Using open-source local LLMs (`llama3.2` 3B/8B) via Ollama ensures $0 API operation costs during task planning.

---

## 12. MY DEVELOPMENT GOALS

* **Learning Objectives:**
* I am building this project to deeply understand **full-stack software architecture, AI agent system design, backend microservices, and native Android accessibility engineering**.
* **CRITICAL FOR CLAUDE:** Do NOT simply generate complete code files without explanation. I want to understand:
* *Why* specific architectural patterns or algorithms are chosen.
* *How* data flows across the system (Android $\leftrightarrow$ Gateway-Core $\leftrightarrow$ Service 02 $\leftrightarrow$ Service 04).
* *How to debug* complex multi-service interactions and trace failure points.
* *How to write and refactor* key system modules myself.





---

## 13. IMPORTANT CONVERSATION-BASED KNOWLEDGE

* **AccessBridge AI is an Independent Platform:** It is designed as a standalone, independent voice automation system and must not be framed as an integration with external government portals.
* **Backend Microservice Focus:** Primary technical ownership concentrates on the backend microservices, real-time WebSocket orchestration, planning logic, DOM compression, and safety guardrail interceptors.
* **System Prompting Constraints:** Local models (e.g., `llama3.2`) strictly rely on Ollama's `format="json"` schema to prevent outputting conversational preamble text that breaks JSON parsers.

---

## 14. CURRENT PROJECT STATE FROM OUR DISCUSSIONS

* **CONFIRMED:**
* Core WebSocket communication between Kotlin client and Node.js Gateway-Core is functional.
* Persistent `global_session_id` logic across app switches is verified and working in logs.
* DOM compression in Gateway-Core is functioning (compressing 61 elements to 45, etc.).
* Fast-path native app launching for exact matches works.
* `AppResolver.kt` is implemented with Double Metaphone and Jaro-Winkler algorithms.


* **LIKELY:**
* The `/api/v1/classify-intent` endpoint and Gateway-Core conditional context router were partially applied via AI agent prompts.
* Android Studio and VS Code codebases contain prompt-generated code for `AppResolver` and intent classification that needs validation against recent failures.


* **UNKNOWN:**
* Whether the modular file refactoring (`SessionManager.kt`, `sessionContextManager.ts`) has been fully committed or remains in single files.
* Whether the empty `target_text: ""` guard and retry limit in Service 02 have been completely tested on-device.



---

## 15. OPEN QUESTIONS FOR CLAUDE TO VERIFY IN CODEBASE

1. **Verify `AppResolver.kt`:** Check if `AppResolver` forces native app launch (`packageManager.getLaunchIntentForPackage`) when a package is matched, even if `confidence` is marked as `MULTI_STEP_INTENT`.
2. **Verify Gateway-Core Routing:** Check `server.ts` or `wsHandler.ts` in `gateway-core` to see if incoming payloads containing `confirmation_context` are routed to `/api/v1/classify-intent` or still hardcoded to `/api/v1/plan`.
3. **Verify Service 02 Target Validation:** Check `service-02` (Python/FastAPI) to confirm if `CLICK` or `TYPE_TEXT` directives validate that `target_text` is non-empty, preventing the infinite `rescan_wait` loop.
4. **Verify Modularization:** Inspect directory structures in Android and `gateway-core` to check if files have been split (`AppResolver.kt`, `SessionManager.kt`, `sessionContextManager.ts`).

---

## 16. HANDOFF SUMMARY FOR CLAUDE

> *"The next AI (Claude) should know these things before touching the project:"*
> 1. **Project Identity:** AccessBridge AI is a 4-tier hybrid voice automation platform for Android (Kotlin Client + Node.js Gateway-Core + Python/FastAPI Ollama Service 02 + Python Vision Service 04).
> 2. **Current Milestone:** We successfully implemented and verified **Global Session Persistence (`global_session_id`)** across app switches. We are currently finishing Phase 1 (**Dynamic App Launcher** with phonetic matching, dynamic LLM confirmation, and Play Store fallback) before fixing Phase 2 (**Multi-Step Target Resolution / Rescan Loops**).
> 3. **Core Philosophy:** **ZERO HARDCODING.** App matching uses `PackageManager` + `Double Metaphone` + `Jaro-Winkler`. User confirmation uses LLM intent classification (`AFFIRMATIVE` / `NEGATIVE` / `AMBIGUOUS`).
> 4. **Known Working Features:** Native WebSocket connection, DOM tree compression, persistent session context, and basic `OPEN_APP` execution.
> 5. **Top 2 Bugs To Address First:**
> * **Bug A (Launcher Fallback):** `AppResolver` was handing off app opening to Service 02 on multi-step prompts (e.g., *"cab on Ola"*), causing Service 02 to try opening the App Drawer visually and falsely declaring the app "Not Installed". **Fix:** Force native intent launch whenever a `packageName` is matched.
> * **Bug B (Rescan Loop):** Service 02 occasionally emits `CLICK` with `target_text: ""`, triggering an execution failure and an infinite `rescan_wait` loop. **Fix:** Enforce non-empty target validation and a retry counter.
> 
> 
> 6. **Developer Preference:** The user wants to **learn and understand** architectural design, failure modes, and debugging. Do not write full black-box code dumps without explaining the *why*, the *how*, and the step-by-step reasoning.
> 
>