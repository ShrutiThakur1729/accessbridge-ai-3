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
  | "FINISH";

export interface PlannerDirective {
  action: PlannerActionType;
  target_node?: string;
  action_type?: PlannerActionType;
  package_name?: string;
  fallback_package_name?: string;
  target_text?: string;
  execution_mode?: "SYSTEM_INTENT" | "ACCESSIBILITY";
  input_data?: string;
  scroll_direction?: string;
}

export interface ActionHistoryEntry {
  action_type: string;
  package_name?: string;
  status: string;
}

export interface IntentRequestPayload {
  task_id?: string;
  message_type?: "USER_INTENT" | "USER_INTENT_FEEDBACK";
  action_status?: string;
  status?: string;
  target_action?: string;
  target_node?: string;
  failure_reason?: string;
  execution_attempt?: number;
  user_id: string;
  input_type: "TEXT" | "AUDIO";
  text_prompt?: string;
  audio_base64?: string;
  current_app_package?: string;
  screen_state?: any[];
  action_history?: ActionHistoryEntry[];
}

export interface IntentPlanResponse {
  current_step_index?: number;
  speech_response?: string;
  is_completed?: boolean | null;
  error?: string;
  steps?: PlannerDirective[];
}

export class IntentClient {
  static async evaluateIntent(payload: IntentRequestPayload, requestId: string): Promise<IntentPlanResponse> {
    const url = `${ENV.INTENT_SAFETY_URL}/plan`;
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
}
