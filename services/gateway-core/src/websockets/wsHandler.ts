import { WebSocket, WebSocketServer } from "ws";
import { IncomingMessage } from "http";
import crypto from "crypto";
import { ENV } from "../config/env.js";
import { logger } from "../utils/logger.js";
import { ActionHistoryEntry, IntentClient, PlannerActionType, PlannerDirective, VisionClient } from "../services/intentClient.js";
import { SttClient, SttError } from "../services/sttClient.js";
import { getBlacklistMemory } from "../services/databricksClient.js";

export interface ResponsePayload {
  task_id: string;
  session_id: string;
  language_code: string;
  execution_state: string;
  requires_confirmation?: boolean;
  approach: string;
  speech_response: string;
  action_type: PlannerActionType;
  target_text: string;
  package_name: string;
  text_to_type: string;
  scroll_direction: string;
  execution_mode: "SYSTEM_INTENT" | "ACCESSIBILITY" | "COORDINATE";
  error: string;
  is_completed: boolean;
  tap_x?: number;
  tap_y?: number;
}

interface CustomWebSocket extends WebSocket {
  isAlive?: boolean;
}

interface TaskContext {
  transcript: string;
  userId: string;
  sessionId: string;
  sessionLanguage: string;
  actionHistory: ActionHistoryEntry[];
  blacklistMemory: string[];
  zeroElementAttempts?: number;
  databricksBlacklistCache?: {
    packageName: string;
    entries: string[];
    fetchedAt: number;
  };
}

interface PendingHybridAudio {
  taskId: string;
  metadata: Record<string, any>;
  chunks: Buffer[];
  byteLength: number;
  mimeType: string;
}

class HybridPayloadError extends Error {
  constructor(public readonly code: "AUDIO_CORRUPT" | "AUDIO_SILENT" | "DOM_EMPTY" | "STT_UNAVAILABLE", message: string) {
    super(message);
    this.name = "HybridPayloadError";
  }
}

function isHybridStart(message: Record<string, any>): boolean {
  return ["HYBRID_AUDIO_START", "AUDIO_STREAM_START"].includes(String(message.message_type || message.type || "").toUpperCase());
}

function isHybridEnd(message: Record<string, any>): boolean {
  return ["HYBRID_AUDIO_END", "AUDIO_STREAM_END"].includes(String(message.message_type || message.type || "").toUpperCase());
}

function screenshotFrom(message: Record<string, any>): string {
  const nested = message.screen_state && typeof message.screen_state === "object" && !Array.isArray(message.screen_state)
    ? message.screen_state
    : null;
  const raw =
    message.screenshot_base64 ||
    message.screenshot ||
    message.image_base64 ||
    message.screen_capture ||
    nested?.screenshot_base64 ||
    "";
  return String(raw).replace(/^data:[^;]+;base64,/, "").trim();
}

function visionNodesFrom(payload: any): any[] {
  if (!payload) return [];
  if (Array.isArray(payload.screen_state) && payload.screen_state.length > 0) {
    return payload.screen_state;
  }
  return (payload.elements || []).map((element: any, index: number) => ({
    text: element.ocr_text || element.label || `control_${index + 1}`,
    content_description: element.label || "",
    view_id: element.element_id || `vis_${index + 1}`,
    class_name: "vision.DetectedControl",
    is_clickable: true,
    source: "vision",
    tap_x: element.tap_x,
    tap_y: element.tap_y,
    confidence: element.confidence,
    matched: Boolean(element.matched) || element.element_id === payload.matched_element_id,
  }));
}

function nestedScreenObject(message: Record<string, any>): Record<string, any> | null {
  const raw = message.screen_state;
  return raw && typeof raw === "object" && !Array.isArray(raw) ? raw : null;
}

function extractScreenNodes(message: Record<string, any>): any[] {
  const nested = nestedScreenObject(message);
  const candidates = [
    message.screenElements,
    Array.isArray(message.screen_state) ? message.screen_state : null,
    nested?.screenElements,
    nested?.screen_state,
  ];
  const nodes = candidates.find((value) => Array.isArray(value));
  return nodes || [];
}

function isActionableNode(node: any): boolean {
  if (!node || typeof node !== "object") return false;
  return Boolean(
    node.is_clickable ||
      node.isClickable ||
      node.clickable ||
      node.is_editable ||
      node.isEditable ||
      (node.tap_x != null && node.tap_y != null)
  );
}

function shouldRouteToVision(nodes: any[], screenshot: string, message: Record<string, any>): boolean {
  if (!screenshot) return false;
  const status = String(message.status || message.failure_reason || message.error_message || "").toUpperCase();
  const accessibilityFailed = ["NODE_MISSING", "NOT_CLICKABLE", "EMPTY_UI_STATE", "OFF_SCREEN", "ACTION_FAILED"].some(
    (code) => status.includes(code)
  );
  return nodes.length === 0 || !nodes.some(isActionableNode) || accessibilityFailed;
}

function hybridDom(message: Record<string, any>): any[] {
  return extractScreenNodes(message);
}

function normalizeLanguage(value: unknown, fallback = "en-US"): string {
  const language = String(value || "").trim();
  return /^[a-z]{2,3}(?:-[A-Za-z]{2,4})?$/.test(language) ? language : fallback;
}

function actionTypeFor(step: PlannerDirective | null): PlannerActionType {
  return step ? (step.action_type || step.action) : "FINISH";
}

function compressScreenState(screenState: any[]): any[] {
  if (!Array.isArray(screenState)) return [];

  const filtered = screenState
    .filter((node) => {
      if (!node || typeof node !== "object") return false;

      const className = String(node.class_name || node.className || "").toLowerCase();
      const viewId = String(node.view_id || node.viewId || "").toLowerCase();
      const text = String(node.text || node.content_description || node.contentDescription || "").trim();

      const isClickable = Boolean(node.is_clickable || node.isClickable || node.clickable || (node.tap_x != null && node.tap_y != null));
      const isEditable = Boolean(node.is_editable || node.isEditable || node.editable);

      if (isClickable || isEditable || text.length > 0) return true;

      if (className.includes("keyboard") || viewId.includes("keyboard")) return false;

      return false;
    })
    .slice(0, 45)
    .map((node) => {
      const cleanNode: Record<string, any> = {};

      const text = String(node.text || "").trim();
      const contentDesc = String(node.content_description || node.contentDescription || "").trim();
      const viewId = String(node.view_id || node.viewId || "").trim();

      if (text) cleanNode.text = text;
      if (contentDesc && contentDesc !== text) cleanNode.content_description = contentDesc;
      if (viewId) cleanNode.view_id = viewId;

      if (node.is_clickable || node.isClickable || node.clickable) cleanNode.is_clickable = true;
      if (node.is_editable || node.isEditable || node.editable) cleanNode.is_editable = true;
      if (node.source) cleanNode.source = node.source;
      if (node.tap_x != null && node.tap_y != null) {
        cleanNode.tap_x = node.tap_x;
        cleanNode.tap_y = node.tap_y;
        cleanNode.is_clickable = true;
      }

      return cleanNode;
    });

  return filtered;
}

export function setupWebSocketServer(wss: WebSocketServer) {
  const sessionContexts = new Map<string, TaskContext>();

  const pingInterval = setInterval(() => {
    wss.clients.forEach((ws: CustomWebSocket) => {
      if (ws.isAlive === false) {
        logger.warn("Terminating inactive WebSocket client connection");
        return ws.terminate();
      }

      ws.isAlive = false;
      ws.ping();
    });
  }, 15000);

  wss.on("close", () => {
    clearInterval(pingInterval);
  });

  wss.on("connection", (ws: CustomWebSocket, req: IncomingMessage) => {
    const clientId = crypto.randomUUID();
    const clientIp = req.socket.remoteAddress;

    ws.isAlive = true;

    ws.on("pong", () => {
      ws.isAlive = true;
    });

    logger.info({ clientId, clientIp }, "Client connected via WebSocket");

    let pendingHybridAudio: PendingHybridAudio | undefined;

    const sendHybridFallback = (taskId: string | undefined, error: HybridPayloadError) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          task_id: taskId || "",
          session_id: "",
          speech_response: error.message,
          action_type: "FINISH",
          target_text: "",
          package_name: "",
          text_to_type: "",
          scroll_direction: "",
          execution_mode: "ACCESSIBILITY",
          language_code: "en-US",
          execution_state: "ERROR",
          approach: "",
          error: error.code,
          is_completed: true,
        }));
      }
    };

    ws.on("message", async (message: Buffer, isBinary: boolean) => {
      const requestId = `req_${crypto.randomUUID().slice(0, 8)}`;
      let hybridTaskId: string | undefined;

      try {
        if (isBinary) {
          if (!pendingHybridAudio) {
            throw new HybridPayloadError("AUDIO_CORRUPT", "Received audio data before hybrid audio metadata.");
          }
          const chunk = Buffer.from(message);
          if (pendingHybridAudio.byteLength + chunk.length > ENV.MAX_AUDIO_BYTES) {
            const taskId = pendingHybridAudio.taskId;
            pendingHybridAudio = undefined;
            hybridTaskId = taskId;
            throw new HybridPayloadError("AUDIO_CORRUPT", "Voice recording exceeds the allowed audio size.");
          }
          pendingHybridAudio.chunks.push(chunk);
          pendingHybridAudio.byteLength += chunk.length;
          return;
        }

        let parsedMessage: Record<string, any> = JSON.parse(message.toString());
        let audioBuffer: Buffer | undefined;
        let hybridPayload = false;

        if (isHybridStart(parsedMessage)) {
          const taskId = String(parsedMessage.task_id || `task_${crypto.randomUUID().slice(0, 8)}`);
          hybridTaskId = taskId;
          const dom = hybridDom(parsedMessage);
          if (dom.length === 0 && !screenshotFrom(parsedMessage)) {
            throw new HybridPayloadError("DOM_EMPTY", "No screen DOM was supplied with the voice request.");
          }
          pendingHybridAudio = {
            taskId,
            metadata: { ...parsedMessage, task_id: taskId },
            chunks: [],
            byteLength: 0,
            mimeType: String(parsedMessage.audio_mime_type || parsedMessage.mime_type || "audio/webm"),
          };
          return;
        }

        if (isHybridEnd(parsedMessage)) {
          if (!pendingHybridAudio) {
            throw new HybridPayloadError("AUDIO_CORRUPT", "Received hybrid audio completion without an active audio stream.");
          }
          if (parsedMessage.task_id && String(parsedMessage.task_id) !== pendingHybridAudio.taskId) {
            throw new HybridPayloadError("AUDIO_CORRUPT", "Hybrid audio completion did not match the active task.");
          }
          parsedMessage = { ...pendingHybridAudio.metadata, ...parsedMessage, task_id: pendingHybridAudio.taskId };
          hybridTaskId = pendingHybridAudio.taskId;
          audioBuffer = Buffer.concat(pendingHybridAudio.chunks, pendingHybridAudio.byteLength);
          parsedMessage.audio_mime_type = pendingHybridAudio.mimeType;
          pendingHybridAudio = undefined;
          hybridPayload = true;
        } else if (typeof parsedMessage.audio_base64 === "string") {
          const rawBase64 = parsedMessage.audio_base64.replace(/^data:[^;]+;base64,/, "").trim();
          if (!rawBase64 || !/^[A-Za-z0-9+/]*={0,2}$/.test(rawBase64)) {
            throw new HybridPayloadError("AUDIO_CORRUPT", "Voice payload is not valid base64 audio.");
          }
          audioBuffer = Buffer.from(rawBase64, "base64");
          if (audioBuffer.length > ENV.MAX_AUDIO_BYTES) {
            throw new HybridPayloadError("AUDIO_CORRUPT", "Voice recording exceeds the allowed audio size.");
          }
          hybridPayload = true;
        }

        if (hybridPayload && hybridDom(parsedMessage).length === 0 && !screenshotFrom(parsedMessage)) {
          throw new HybridPayloadError("DOM_EMPTY", "No screen DOM was supplied with the voice request.");
        }
        if (audioBuffer) {
          try {
            const sttResult = await SttClient.transcribe(
              audioBuffer,
              String(parsedMessage.audio_mime_type || parsedMessage.mime_type || "audio/webm")
            );
            parsedMessage.transcript = sttResult.transcript;
            parsedMessage.session_language = parsedMessage.session_language || parsedMessage.language_code || sttResult.language;
          } catch (error) {
            if (error instanceof SttError) {
              throw new HybridPayloadError(error.code, error.message);
            }
            throw error;
          }
        }

        const taskId = parsedMessage.task_id || `task_${crypto.randomUUID().slice(0, 8)}`;
        const sessionId = String(parsedMessage.session_id || parsedMessage.sessionId || taskId);
        const messageType =
          parsedMessage.message_type === "USER_INTENT_FEEDBACK" || parsedMessage.type === "USER_INTENT_FEEDBACK"
            ? "USER_INTENT_FEEDBACK"
            : "USER_INTENT";
        
        const providedTranscript = String(parsedMessage.transcript || parsedMessage.user_prompt || parsedMessage.original_intent || "").trim();
        
        // Context lookup now uses sessionId to ensure continuous memory across per-turn taskIds
        const storedContext = sessionContexts.get(sessionId);
        const transcript = providedTranscript || storedContext?.transcript || "";

        if (!transcript && messageType === "USER_INTENT") {
          logger.warn({ taskId, sessionId, requestId }, "Received USER_INTENT with empty transcript. Aborting call to AI Planner.");
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({
              task_id: taskId,
              session_id: sessionId,
              speech_response: "I didn't capture any speech. Please try speaking your command again.",
              action_type: "FINISH",
              target_text: "",
              package_name: "",
              text_to_type: "",
              scroll_direction: "",
              execution_mode: "ACCESSIBILITY",
              language_code: "en-US",
              execution_state: "ERROR",
              approach: "",
              error: "EMPTY_TRANSCRIPT",
              is_completed: true,
            }));
          }
          return;
        }

        const userId = parsedMessage.user_id || storedContext?.userId || "default_user";
        const sessionLanguage = normalizeLanguage(
          parsedMessage.session_language || parsedMessage.sessionLanguage || parsedMessage.language_code,
          storedContext?.sessionLanguage || "en-US"
        );
        const taskContext: TaskContext = storedContext || {
          transcript: "",
          userId,
          sessionId,
          sessionLanguage,
          actionHistory: [],
          blacklistMemory: [],
        };
        if (!taskContext.blacklistMemory) {
          taskContext.blacklistMemory = [];
        }
        if (providedTranscript) {
          taskContext.transcript = providedTranscript;
          taskContext.userId = userId;
        }
        taskContext.sessionId = sessionId;
        taskContext.sessionLanguage = sessionLanguage;
        const nestedScreen = nestedScreenObject(parsedMessage);
        const screenState = extractScreenNodes(parsedMessage);
        const actionStatus = String(parsedMessage.action_status || parsedMessage.actionStatus || parsedMessage.status || "INITIAL");
        const currentPackageName = String(
          parsedMessage.current_package_name ||
            parsedMessage.current_app_package ||
            parsedMessage.current_package ||
            nestedScreen?.current_package_name ||
            nestedScreen?.current_app_package ||
            ""
        ).trim();
        const activeInputFocused = Boolean(
          parsedMessage.active_input_focused ??
            parsedMessage.activeInputFocused ??
            nestedScreen?.active_input_focused ??
            nestedScreen?.activeInputFocused ??
            false
        );
        const focusedHint = String(
          parsedMessage.focused_hint ||
            parsedMessage.focusedHint ||
            nestedScreen?.focused_hint ||
            nestedScreen?.focusedHint ||
            ""
        ).trim();
        const originalElementsCount = Array.isArray(screenState) ? screenState.length : 0;
        const executedAction = parsedMessage.target_action || parsedMessage.action_type || parsedMessage.action;

        if (executedAction && actionStatus.toUpperCase() !== "INITIAL") {
          taskContext.actionHistory.push({
            action_type: String(executedAction),
            target_node: parsedMessage.target_node || undefined,
            target_text: parsedMessage.target_text || undefined,
            package_name: parsedMessage.package_name || parsedMessage.target_package || undefined,
            status: actionStatus.toUpperCase(),
          });
        }

        const rawStatus = String(parsedMessage.status || parsedMessage.action_status || parsedMessage.actionStatus || "").toUpperCase();
        if (rawStatus === "LOOP_DETECTED" || String(actionStatus).toUpperCase() === "LOOP_DETECTED") {
          const actionType = String(executedAction || parsedMessage.action_type || parsedMessage.action || "SCROLL").trim();
          const targetText = String(parsedMessage.target_text || parsedMessage.target_node || "").trim();
          const fallbackStr = `${actionType}_${targetText || "rescan_wait"}`;
          const targetActionStr = String(parsedMessage.targetActionStr || parsedMessage.target_action_str || fallbackStr).trim();
          const lastTargetNode = String(parsedMessage.target_node || parsedMessage.target_text || "").trim();

          const isPassiveDelay = /rescan_wait|scroll/i.test(targetActionStr) || /rescan_wait|scroll/i.test(lastTargetNode);
          if (!isPassiveDelay) {
            if (targetActionStr && targetActionStr !== "" && !taskContext.blacklistMemory.includes(targetActionStr)) {
              taskContext.blacklistMemory.push(targetActionStr);
            }
            if (lastTargetNode && lastTargetNode !== "" && !taskContext.blacklistMemory.includes(lastTargetNode)) {
              taskContext.blacklistMemory.push(lastTargetNode);
            }
          }
          logger.warn(
            { requestId, taskId, sessionId, targetActionStr, isPassiveDelay, blacklistMemory: taskContext.blacklistMemory },
            "LOOP_DETECTED feedback received: processed session blacklist memory"
          );
        }

        // Store back in sessionContexts keyed by persistent sessionId
        sessionContexts.set(sessionId, taskContext);

        const compressedScreenState = compressScreenState(screenState);
        const screenshotBase64 = screenshotFrom(parsedMessage);
        let visionElements = Array.isArray(parsedMessage.vision_elements) ? parsedMessage.vision_elements : [];

        if (shouldRouteToVision(compressedScreenState, screenshotBase64, parsedMessage)) {
          try {
            const visionPayload = await VisionClient.analyze(
              { screenshot_base64: screenshotBase64, task_id: taskId, query_text: transcript },
              requestId
            );
            visionElements = visionNodesFrom(visionPayload);
          } catch (vErr: any) {
            logger.warn({ requestId, taskId, error: vErr.message }, "Vision perception call failed or timed out");
          }
        }

        // 0-Element Escalation Chain handling
        if (visionElements.length > 0) {
          taskContext.zeroElementAttempts = 0;
        } else if (originalElementsCount === 0) {
          taskContext.zeroElementAttempts = (taskContext.zeroElementAttempts || 0) + 1;

          if (taskContext.zeroElementAttempts >= 3) {
            logger.warn(
              { requestId, taskId, sessionId, zeroElementAttempts: taskContext.zeroElementAttempts },
              "[0-Element Escalation] Attempt >= 3: Screen unresponsive. Emitting HARD_STOP."
            );
            const responsePayload: ResponsePayload = {
              task_id: taskId,
              session_id: sessionId,
              language_code: sessionLanguage,
              execution_state: "ERROR",
              approach: "SAFETY_INTERCEPT",
              speech_response: "Screen unresponsive.",
              action_type: "HARD_STOP",
              target_text: "",
              package_name: "",
              text_to_type: "",
              scroll_direction: "",
              execution_mode: "ACCESSIBILITY",
              error: "Screen unresponsive",
              is_completed: true,
            };
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify(responsePayload));
              logger.info({ requestId, taskId, sessionId, responsePayload }, "Sent AI planner directive to Android");
            }
            return;
          }

          if (taskContext.zeroElementAttempts === 2) {
            logger.warn(
              { requestId, taskId, sessionId, zeroElementAttempts: taskContext.zeroElementAttempts },
              "[0-Element Escalation] Attempt 2: Vision returned 0 elements. Issuing SYSTEM_BACK overlay clear."
            );
            const responsePayload: ResponsePayload = {
              task_id: taskId,
              session_id: sessionId,
              language_code: sessionLanguage,
              execution_state: "EXECUTING",
              approach: "SAFETY_INTERCEPT",
              speech_response: "Screen unresponsive. Clearing overlay.",
              action_type: "SYSTEM_BACK",
              target_text: "",
              package_name: "",
              text_to_type: "",
              scroll_direction: "",
              execution_mode: "SYSTEM_INTENT",
              error: "",
              is_completed: false,
            };
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify(responsePayload));
              logger.info({ requestId, taskId, sessionId, responsePayload }, "Sent AI planner directive to Android");
            }
            return;
          }

          if (taskContext.zeroElementAttempts === 1) {
            logger.warn(
              { requestId, taskId, sessionId, zeroElementAttempts: taskContext.zeroElementAttempts },
              "[0-Element Escalation] Attempt 1: 0 elements detected. Requesting rescan/screenshot."
            );
            const responsePayload: ResponsePayload = {
              task_id: taskId,
              session_id: sessionId,
              language_code: sessionLanguage,
              execution_state: "EXECUTING",
              approach: "SERVER_REPLANNING",
              speech_response: "Rescanning screen state...",
              action_type: "SCROLL",
              target_text: "rescan_wait",
              package_name: "",
              text_to_type: "",
              scroll_direction: "",
              execution_mode: "ACCESSIBILITY",
              error: "",
              is_completed: false,
            };
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify(responsePayload));
            }
            return;
          }
        } else {
          taskContext.zeroElementAttempts = 0;
        }

        logger.info(
          {
            requestId,
            taskId,
            sessionId,
            sessionLanguage,
            actionStatus,
            transcriptLength: transcript.length,
            originalElementsCount,
            compressedElementsCount: compressedScreenState.length,
            visionElementsCount: visionElements.length,
            screenshotPresent: Boolean(screenshotBase64),
          },
          "Compressed screen state and querying Python AI Planner"
        );

        // ── Merge persisted Databricks failure history into blacklist ────
// Cached per session per package, with a TTL, to avoid hitting Databricks
// on every single WebSocket message (which would add real latency to
// every user action, especially on a cold serverless warehouse).
const BLACKLIST_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
const cache = taskContext.databricksBlacklistCache;
const cacheIsFresh =
  cache &&
  cache.packageName === currentPackageName &&
  Date.now() - cache.fetchedAt < BLACKLIST_CACHE_TTL_MS;

let databricksBlacklist: string[];
if (cacheIsFresh) {
  databricksBlacklist = cache!.entries;
} else {
  // getBlacklistMemory() never throws — returns [] on failure/unconfigured
  databricksBlacklist = await getBlacklistMemory(userId, currentPackageName);
  taskContext.databricksBlacklistCache = {
    packageName: currentPackageName,
    entries: databricksBlacklist,
    fetchedAt: Date.now(),
  };
}

const mergedBlacklistSet = new Set<string>([
  ...taskContext.blacklistMemory,
  ...databricksBlacklist,
]);
const mergedBlacklist = Array.from(mergedBlacklistSet);

        if (databricksBlacklist.length > 0) {
          logger.info(
            {
              requestId,
              taskId,
              clientEntries: taskContext.blacklistMemory.length,
              databricksEntries: databricksBlacklist.length,
              mergedTotal: mergedBlacklist.length,
            },
            "Merged Databricks failure history into blacklist_memory"
          );
        }

        const aiPlan = await IntentClient.evaluateIntent(
          {
            user_id: userId,
            task_id: taskId,
            session_id: sessionId,
            session_language: sessionLanguage,
            message_type: messageType,
            action_status: actionStatus,
            status: parsedMessage.status || "",
            target_action: parsedMessage.target_action || parsedMessage.action || "",
            target_node: parsedMessage.target_node || parsedMessage.target_package || "",
            failure_reason: parsedMessage.failure_reason || parsedMessage.error_message || "",
            execution_attempt: Number(parsedMessage.execution_attempt || 0),
            input_type: "TEXT",
            user_prompt: transcript,
            text_prompt: transcript,
            current_package_name: currentPackageName,
            current_app_package: currentPackageName,
            active_input_focused: activeInputFocused,
            focused_hint: focusedHint,
            screen_state: compressedScreenState,
            screenshot_base64: screenshotBase64 || undefined,
            vision_elements: visionElements,
            action_history: taskContext.actionHistory,
            blacklist_memory: mergedBlacklist,
          },
          requestId
        );

        const currentStepIndex = aiPlan.current_step_index || 0;
        const currentStep =
          aiPlan.steps && aiPlan.steps.length > currentStepIndex ? aiPlan.steps[currentStepIndex] : null;

        const prematureCompletion =
          aiPlan.is_completed === true &&
          actionStatus.toUpperCase() === "FAILED" &&
          originalElementsCount === 0;

        if (prematureCompletion) {
          logger.warn(
            { requestId, taskId, actionStatus, originalElementsCount },
            "[Gateway-Core] Intercepted invalid premature completion from Planner."
          );
        }

        const actionType = actionTypeFor(currentStep);
        const isHardStop = actionType === "HARD_STOP" || aiPlan.error === "HARD_STOP";
        const responsePayload: ResponsePayload = {
          task_id: taskId,
          session_id: sessionId,
          language_code: normalizeLanguage(aiPlan.response_language || aiPlan.session_language, sessionLanguage),
          execution_state: isHardStop ? "ERROR" : aiPlan.execution_state || "EXECUTING",
          requires_confirmation: aiPlan.requires_confirmation,
          approach: isHardStop ? "SAFETY_INTERCEPT" : aiPlan.approach || "SERVER_REPLANNING",
          speech_response: prematureCompletion
            ? "Target application could not be launched."
            : aiPlan.speech_response ||
              (currentStep ? `Executing step: ${currentStep.action}` : "Task completed."),
          action_type: actionType,
          target_text: currentStep ? currentStep.target_text || currentStep.target_node || "" : "",
          package_name:
            currentStep && currentStep.action === "OPEN_APP"
              ? currentStep.package_name || currentStep.target_node || ""
              : "",
          text_to_type: currentStep?.input_data || "",
          scroll_direction: currentStep?.scroll_direction || "",
          execution_mode:
            currentStep?.execution_mode ||
            (currentStep && currentStep.action === "OPEN_APP" ? "SYSTEM_INTENT" : "ACCESSIBILITY"),
          error: isHardStop ? "HARD_STOP" : aiPlan.error || "",
          is_completed: isHardStop || (!prematureCompletion && actionType === "FINISH" && aiPlan.is_completed === true),
          tap_x: currentStep?.tap_x,
          tap_y: currentStep?.tap_y,
        };

        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify(responsePayload));
          logger.info({ requestId, taskId, sessionId, responsePayload }, "Sent AI planner directive to Android");
          
          // Step 1: Session context is cleared ONLY when task is marked fully completed AND execution_state is COMPLETED
          if (responsePayload.is_completed === true && responsePayload.execution_state === "COMPLETED") {
            sessionContexts.delete(sessionId);
          }
        }
      } catch (err: any) {
        if (err instanceof HybridPayloadError) {
          logger.warn({ requestId, taskId: hybridTaskId, error: err.code }, "Rejected hybrid voice/DOM payload");
          sendHybridFallback(hybridTaskId, err);
          return;
        }
        if (err?.name === "AbortError") {
          logger.info({ requestId }, "Dropped superseded planner request");
          return;
        }
        logger.error(
          { requestId, err: err.message },
          "Error processing WebSocket message or contacting AI planner"
        );
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(
            JSON.stringify({
              speech_response: "Sorry, I encountered an error communicating with the AI planner.",
              action_type: "FINISH",
              target_text: "",
              package_name: "",
              text_to_type: "",
              scroll_direction: "",
              language_code: "en-US",
              execution_state: "ERROR",
              approach: "",
              is_completed: true,
            })
          );
        }
      }
    });

    ws.on("close", () => {
      logger.info({ clientId }, "Client disconnected from WebSocket");
    });
  });
}