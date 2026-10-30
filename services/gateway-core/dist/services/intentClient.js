"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.VisionClient = exports.IntentClient = void 0;
const env_js_1 = require("../config/env.js");
const logger_js_1 = require("../utils/logger.js");
const TaskLockManager_js_1 = require("./TaskLockManager.js");
class IntentClient {
    static async evaluateIntent(payload, requestId) {
        const rawUrl = String(env_js_1.ENV.INTENT_SAFETY_URL || "http://localhost:8000/api/v1").replace(/\/$/, "");
        const url = rawUrl.endsWith("/plan") ? rawUrl : `${rawUrl}/plan`;
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
    static async extractApp(transcript, requestId) {
        const rawUrl = String(env_js_1.ENV.INTENT_SAFETY_URL || "http://localhost:8000/api/v1").replace(/\/$/, "");
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
            return (await response.json());
        }
        catch (error) {
            logger_js_1.logger.warn({ requestId, error: error?.message || String(error) }, "App extraction failed");
            return { ok: false, app_name: null, task: "", reason: `GATEWAY_ERROR: ${error?.message || "unknown"}` };
        }
        finally {
            clearTimeout(timer);
        }
    }
    static async classifyReply(question, reply, requestId) {
        const rawUrl = String(env_js_1.ENV.INTENT_SAFETY_URL || "http://localhost:8000/api/v1").replace(/\/$/, "");
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
            return (await response.json());
        }
        catch (error) {
            logger_js_1.logger.warn({ requestId, error: error?.message || String(error) }, "Reply classification failed");
            return { ok: false, answer: "OTHER", reason: `GATEWAY_ERROR: ${error?.message || "unknown"}` };
        }
        finally {
            clearTimeout(timer);
        }
    }
}
exports.IntentClient = IntentClient;
class VisionClient {
    static async analyze(payload, requestId) {
        const rawUrl = String(env_js_1.ENV.VISION_PERCEPTION_URL || "http://localhost:8010/api/v1").replace(/\/$/, "");
        if (!payload.screenshot_base64) {
            return null;
        }
        const url = rawUrl.endsWith("/analyze") ? rawUrl : `${rawUrl}/api/v1/analyze`;
        logger_js_1.logger.info({ requestId, url, taskId: payload.task_id }, "Routing fallback screenshot to Service 04 Vision Perception");
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
                logger_js_1.logger.warn({ requestId, status: response.status, errorText }, "Service 04 analyze failed");
                return null;
            }
            const data = (await response.json());
            logger_js_1.logger.info({ requestId, taskId: payload.task_id, engine: data?.engine, detections: (data?.elements || []).length }, "Service 04 returned coordinate payload");
            return data;
        }
        catch (error) {
            logger_js_1.logger.warn({ requestId, error: error?.maessage || String(error) }, "Service 04 unreachable");
            return null;
        }
    }
}
exports.VisionClient = VisionClient;
