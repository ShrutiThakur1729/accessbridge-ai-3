"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.IntentClient = void 0;
const env_js_1 = require("../config/env.js");
const logger_js_1 = require("../utils/logger.js");
const TaskLockManager_js_1 = require("./TaskLockManager.js");
class IntentClient {
    static async evaluateIntent(payload, requestId) {
        const url = `${env_js_1.ENV.INTENT_SAFETY_URL}/plan`;
        const taskId = payload.task_id || requestId;
        const controller = TaskLockManager_js_1.taskLockManager.acquireLock(taskId);
        logger_js_1.logger.info({ requestId, url }, "Forwarding intent payload to Service 02");
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
            const data = (await response.json());
            return data;
        }
        catch (error) {
            if (controller.signal.aborted) {
                logger_js_1.logger.info({ requestId, taskId }, "Cancelled stale request to Service 02");
            }
            else {
                logger_js_1.logger.error({ requestId, url, error: error?.message || String(error), cause: error?.cause }, "Failed to communicate with Service 02");
            }
            throw error;
        }
        finally {
            TaskLockManager_js_1.taskLockManager.releaseLock(taskId, controller);
        }
    }
}
exports.IntentClient = IntentClient;
