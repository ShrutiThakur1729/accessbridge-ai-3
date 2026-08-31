"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.IntentClient = void 0;
const env_js_1 = require("../config/env.js");
const logger_js_1 = require("../utils/logger.js");
class IntentClient {
    static async evaluateIntent(payload, requestId) {
        const url = `${env_js_1.ENV.INTENT_SAFETY_URL}/plan`;
        // --- COMPRESSION LOGIC START ---
        // Create a copy of payload so we don't mutate original parameters unexpectedly
        const processedPayload = { ...payload };
        if (Array.isArray(processedPayload.screen_state) && processedPayload.screen_state.length > 0) {
            const originalCount = processedPayload.screen_state.length;
            processedPayload.screen_state = processedPayload.screen_state
                .filter((node) => {
                const hasText = node.text && String(node.text).trim().length > 0;
                const hasDesc = node.content_description && String(node.content_description).trim().length > 0;
                const isClickable = Boolean(node.is_clickable || node.clickable);
                // Only keep nodes that have visible text/description OR are explicitly interactive
                return hasText || hasDesc || isClickable;
            })
                .map((node) => ({
                text: node.text || node.content_description || "",
                id: node.view_id || node.id || node.resource_id || "",
                type: node.class_name || node.type || "",
                clickable: Boolean(node.is_clickable || node.clickable),
            }));
            logger_js_1.logger.info({ requestId, originalCount, compressedCount: processedPayload.screen_state.length }, "Compressed screen_state nodes before calling Python planner");
        }
        // --- COMPRESSION LOGIC END ---
        logger_js_1.logger.info({ requestId, url }, "Forwarding intent payload to Service 02");
        try {
            const response = await fetch(url, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "X-Request-ID": requestId,
                },
                body: JSON.stringify(processedPayload),
            });
            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`Intent service error [${response.status}]: ${errorText}`);
            }
            const data = (await response.json());
            return data;
        }
        catch (error) {
            logger_js_1.logger.error({ requestId, error }, "Failed to communicate with Service 02");
            throw error;
        }
    }
}
exports.IntentClient = IntentClient;
