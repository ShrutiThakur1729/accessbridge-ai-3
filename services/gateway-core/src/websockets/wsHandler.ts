import { WebSocket, WebSocketServer } from "ws";
import { IncomingMessage } from "http";
import crypto from "crypto";
import { logger } from "../utils/logger.js";
import { ActionHistoryEntry, IntentClient, PlannerActionType, PlannerDirective } from "../services/intentClient.js";

export interface ResponsePayload {
  task_id: string;
  speech_response: string;
  action_type: PlannerActionType;
  target_text: string;
  package_name: string;
  text_to_type: string;
  scroll_direction: string;
  execution_mode: "SYSTEM_INTENT" | "ACCESSIBILITY";
  error: string;
  is_completed: boolean;
}

interface CustomWebSocket extends WebSocket {
  isAlive?: boolean;
}

interface TaskContext {
  transcript: string;
  userId: string;
  actionHistory: ActionHistoryEntry[];
}

function actionTypeFor(step: PlannerDirective | null): PlannerActionType {
  return step ? (step.action_type || step.action) : "FINISH";
}

/**
 * Prunes non-actionable screen elements, soft-keyboard keys, and bloated visual properties
 * to drastically lower LLM token count and prevent TPM rate limits.
 */
function compressScreenState(screenState: any[]): any[] {
  if (!Array.isArray(screenState)) return [];

  const filtered = screenState
    .filter((node) => {
      if (!node || typeof node !== "object") return false;

      const className = String(node.class_name || node.className || "").toLowerCase();
      const viewId = String(node.view_id || node.viewId || "").toLowerCase();
      const text = String(node.text || node.content_description || node.contentDescription || "").trim();

      // Retain actionable, editable, or nodes with meaningful text
      const isClickable = Boolean(node.is_clickable || node.isClickable || node.clickable);
      const isEditable = Boolean(node.is_editable || node.isEditable || node.editable);

      // Never prune actionable controls, including one-character controls and
      // in-app fields that only expose a view ID.
      if (isClickable || isEditable || text.length > 0) return true;

      // Non-actionable keyboard/layout nodes add noise without aiding planning.
      if (className.includes("keyboard") || viewId.includes("keyboard")) return false;

      return false;
    })
    .slice(0, 45)
    // Strip non-essential layout attributes (bounds, class names, focus state, etc.)
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

      return cleanNode;
    });

  return filtered;
}

export function setupWebSocketServer(wss: WebSocketServer) {
  // Task context is deliberately keyed by Android's task_id, not a transient
  // WebSocket connection ID, so execution can continue after reconnecting.
  const taskContexts = new Map<string, TaskContext>();
  // ------------------------------------------------------------------
  // 1. WebSocket Heartbeat Ping-Pong Mechanism (Prevents Timeouts)
  // ------------------------------------------------------------------
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

    ws.on("message", async (message: Buffer) => {
      const requestId = `req_${crypto.randomUUID().slice(0, 8)}`;

      try {
        const parsedMessage = JSON.parse(message.toString());

        // Extract multi-turn protocol fields sent from Android
        const taskId = parsedMessage.task_id || `task_${crypto.randomUUID().slice(0, 8)}`;
        const messageType =
          parsedMessage.message_type === "USER_INTENT_FEEDBACK" || parsedMessage.type === "USER_INTENT_FEEDBACK"
            ? "USER_INTENT_FEEDBACK"
            : "USER_INTENT";
        const providedTranscript = String(parsedMessage.transcript || parsedMessage.original_intent || "").trim();
        const storedContext = taskContexts.get(taskId);
        const transcript = providedTranscript || storedContext?.transcript || "";
        const userId = parsedMessage.user_id || storedContext?.userId || "default_user";
        const taskContext: TaskContext = storedContext || {
          transcript: "",
          userId,
          actionHistory: [],
        };
        if (providedTranscript) {
          taskContext.transcript = providedTranscript;
          taskContext.userId = userId;
        }
        const screenState = parsedMessage.screen_state || parsedMessage.screenElements || [];
        const actionStatus = parsedMessage.action_status || parsedMessage.actionStatus || "INITIAL";
        const currentAppPackage = parsedMessage.current_app_package || parsedMessage.current_package || "";
        const originalElementsCount = Array.isArray(screenState) ? screenState.length : 0;
        const executedAction = parsedMessage.target_action || parsedMessage.action_type || parsedMessage.action;
        if (executedAction && actionStatus.toUpperCase() !== "INITIAL") {
          taskContext.actionHistory.push({
            action_type: String(executedAction),
            package_name: parsedMessage.package_name || parsedMessage.target_package || undefined,
            status: actionStatus.toUpperCase(),
          });
        }
        taskContexts.set(taskId, taskContext);

        // Compress screen state nodes before forwarding to Python Service 02
        const compressedScreenState = compressScreenState(screenState);

        logger.info(
          {
            requestId,
            taskId,
            actionStatus,
            transcriptLength: transcript.length,
            originalElementsCount,
            compressedElementsCount: compressedScreenState.length,
          },
          "Compressed screen state and querying Python AI Planner"
        );

        // Call Service 02 (Python FastAPI Planner) via IntentClient
        const aiPlan = await IntentClient.evaluateIntent(
          {
            user_id: userId,
            task_id: taskId,
            message_type: messageType,
            action_status: actionStatus,
            status: parsedMessage.status || "",
            target_action: parsedMessage.target_action || parsedMessage.action || "",
            target_node: parsedMessage.target_node || parsedMessage.target_package || "",
            failure_reason: parsedMessage.failure_reason || parsedMessage.error_message || "",
            execution_attempt: Number(parsedMessage.execution_attempt || 0),
            input_type: "TEXT",
            text_prompt: transcript,
            current_app_package: currentAppPackage,
            screen_state: compressedScreenState,
            action_history: taskContext.actionHistory,
          },
          requestId
        );

        // Map the current Python planner step to the Android action protocol format
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
        const responsePayload: ResponsePayload = {
          task_id: taskId,
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
          error: aiPlan.error || "",
          is_completed: !prematureCompletion && actionType === "FINISH" && aiPlan.is_completed === true,
        };

        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify(responsePayload));
          logger.info({ requestId, taskId, responsePayload }, "Sent AI planner directive to Android");
          if (responsePayload.is_completed) {
            taskContexts.delete(taskId);
          }
        }
      } catch (err: any) {
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
