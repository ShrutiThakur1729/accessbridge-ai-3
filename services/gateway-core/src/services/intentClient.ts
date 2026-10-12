import { ENV } from "../config/env.js";
import { logger } from "../utils/logger.js";
import { taskLockManager } from "./TaskLockManager.js";

export type PlannerActionType =
  | "OPEN_APP"
  | "CLICK"
  | "TYPE_TEXT"
  | "SCROLL"
  | "SWIPE_UP"
  | "SWIPE_HORIZONTAL"
  | "CONFIRM_BIOMETRIC"
  | "SYSTEM_BACK"
  | "GO_HOME_AND_SEARCH"
  | "PAUSE_FOR_USER_INPUT"
  | "USER_TAKE_OVER"
  | "FINISH"
  | "HARD_STOP";

export interface PlannerDirective {
  action: PlannerActionType;
  target_node?: string;
  action_type?: PlannerActionType;
  package_name?: string;
  fallback_package_name?: string;
  target_text?: string;
  execution_mode?: "SYSTEM_INTENT" | "ACCESSIBILITY" | "COORDINATE";
  input_data?: string;
  scroll_direction?: string;
  tap_x?: number;
  tap_y?: number;
}

export interface ExtractAppResult {
  ok: boolean;
  app_name: string | null;
  task: string;
  reason?: string | null;
}

export interface ClassifyReplyResult {
  ok: boolean;
  answer: "YES" | "NO" | "OTHER";
  reason?: string | null;
}

export interface ActionHistoryEntry {
  action_type: string;
  target_node?: string;
  target_text?: string;
  package_name?: string;
  status: string;
}

export interface IntentRequestPayload {
  task_id?: string;
  session_id?: string;
  session_language?: string;
  message_type?: "USER_INTENT" | "USER_INTENT_FEEDBACK";
  action_status?: string;
  status?: string;
  target_action?: string;
  target_node?: string;
  failure_reason?: string;
  execution_attempt?: number;
  user_id: string;
  input_type: "TEXT" | "AUDIO";
  user_prompt?: string;
  text_prompt?: string;
  audio_base64?: string;
  current_app_package?: string;
  current_package_name?: string;
  active_input_focused?: boolean;
  focused_hint?: string;
  screen_state?: any[];
  screenshot_base64?: string;
  vision_elements?: any[];
  action_history?: ActionHistoryEntry[];
  blacklist_memory?: string[];
}

export interface IntentPlanResponse {
  current_step_index?: number;
  speech_response?: string;
  is_completed?: boolean | null;
  error?: string;
  session_language?: string;
  execution_state?: string;
  requires_confirmation?: boolean;
  approach?: string;
  thought_process?: string;
  updated_session_state?: Record<string, any>;
  steps?: PlannerDirective[];
}

export class IntentClient {
  static async evaluateIntent(payload: IntentRequestPayload, requestId: string): Promise<IntentPlanResponse> {
    const rawUrl = String(ENV.INTENT_SAFETY_URL || "http://localhost:8000/api/v1").replace(/\/$/, "");
    const url = rawUrl.endsWith("/plan") ? rawUrl : `${rawUrl}/plan`;
    const taskId = payload.task_id || requestId;
    const controller = taskLockManager.acquireLock(taskId);

    logger.info({ requestId, url }, "Forwarding intent payload to Service 02");

    try {
      const response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Request-ID": requestId,
        },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Intent service error [${response.status}]: ${errorText}`);
      }

      const data = (await response.json()) as IntentPlanResponse;
      return data;
    } catch (error) {
      if (controller.signal.aborted) {
        logger.info({ requestId, taskId }, "Cancelled stale request to Service 02");
      } else {
        logger.error({ requestId, url, error: (error as any)?.message || String(error), cause: (error as any)?.cause }, "Failed to communicate with Service 02");
      }
      throw error;
    } finally {
      taskLockManager.releaseLock(taskId, controller);
    }
  }
  static async extractApp(transcript: string, requestId: string): Promise<ExtractAppResult> {
    const rawUrl = String(ENV.INTENT_SAFETY_URL || "http://localhost:8000/api/v1").replace(/\/$/, "");
    const base = rawUrl.endsWith("/plan") ? rawUrl.slice(0, -"/plan".length) : rawUrl;
    const url = `${base}/extract-app`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 35000);
    try {
      const response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Request-ID": requestId },
        body: JSON.stringify({ transcript }),
        signal: controller.signal,
      });
      if (!response.ok) {
        throw new Error(`extract-app returned ${response.status}`);
      }
      return (await response.json()) as ExtractAppResult;
    } catch (error) {
      logger.warn({ requestId, error: (error as any)?.message || String(error) }, "App extraction failed");
      return { ok: false, app_name: null, task: "", reason: `GATEWAY_ERROR: ${(error as any)?.message || "unknown"}` };
    } finally {
      clearTimeout(timer);
    }
  }
  static async classifyReply(question: string, reply: string, requestId: string): Promise<ClassifyReplyResult> {
    const rawUrl = String(ENV.INTENT_SAFETY_URL || "http://localhost:8000/api/v1").replace(/\/$/, "");
    const base = rawUrl.endsWith("/plan") ? rawUrl.slice(0, -"/plan".length) : rawUrl;
    const url = `${base}/classify-reply`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 35000);
    try {
      const response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Request-ID": requestId },
        body: JSON.stringify({ question, reply }),
        signal: controller.signal,
      });
      if (!response.ok) {
        throw new Error(`classify-reply returned ${response.status}`);
      }
      return (await response.json()) as ClassifyReplyResult;
    } catch (error) {
      logger.warn({ requestId, error: (error as any)?.message || String(error) }, "Reply classification failed");
      return { ok: false, answer: "OTHER", reason: `GATEWAY_ERROR: ${(error as any)?.message || "unknown"}` };
    } finally {
      clearTimeout(timer);
    }
  }
}

export class VisionClient {
  static async analyze(
    payload: {
      screenshot_base64: string;
      task_id?: string;
      query_text?: string;
    },
    requestId: string
  ): Promise<any | null> {
    const rawUrl = String(ENV.VISION_PERCEPTION_URL || "http://localhost:8010/api/v1").replace(/\/$/, "");
    if (!payload.screenshot_base64) {
      return null;
    }

    const url = rawUrl.endsWith("/analyze") ? rawUrl : `${rawUrl}/api/v1/analyze`;
    logger.info({ requestId, url, taskId: payload.task_id }, "Routing fallback screenshot to Service 04 Vision Perception");

    try {
      const response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Request-ID": requestId },
        body: JSON.stringify({
          task_id: payload.task_id,
          query_text: payload.query_text,
          screenshot_base64: payload.screenshot_base64,
          image_base64: payload.screenshot_base64,
        }),
      });

      if (!response.ok) {
        const errorText = await response.text();
        logger.warn({ requestId, status: response.status, errorText }, "Service 04 analyze failed");
        return null;
      }

      const data = (await response.json()) as {
        engine?: string;
        elements?: any[];
      };
      logger.info(
        { requestId, taskId: payload.task_id, engine: data?.engine, detections: (data?.elements || []).length },
        "Service 04 returned coordinate payload"
      );
      return data;
    } catch (error) {
      logger.warn({ requestId, error: (error as any)?.maessage || String(error) }, "Service 04 unreachable");
      return null;
    }
  }
}