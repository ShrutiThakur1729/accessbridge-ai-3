"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.setupTelemetryWebSocket = setupTelemetryWebSocket;
const ws_1 = require("ws");
const crypto_1 = __importDefault(require("crypto"));
const logger_js_1 = require("../utils/logger.js");
const databricksClient_js_1 = require("../services/databricksClient.js");
/**
 * Sets up a dedicated WebSocket server (or route) for receiving telemetry
 * failure payloads and piping them to Databricks.
 *
 * Expected JSON message shape:
 * {
 *   "sessionId": "abc-123",
 *   "targetAppPackage": "com.example.app",
 *   "failedAction": "TAP",
 *   "failureReason": "Element not found",
 *   "timestamp": "2026-09-06T16:00:00Z"
 * }
 */
function setupTelemetryWebSocket(wss) {
    wss.on("connection", (ws, req) => {
        const clientId = crypto_1.default.randomUUID();
        const clientIp = req.socket.remoteAddress;
        logger_js_1.logger.info({ clientId, clientIp, channel: "telemetry" }, "Telemetry client connected via WebSocket");
        ws.on("message", async (raw) => {
            const requestId = `tel_${crypto_1.default.randomUUID().slice(0, 8)}`;
            try {
                // ── 1. Parse incoming JSON ──────────────────────────────────
                const parsed = JSON.parse(raw.toString());
                // ── 2. Structured log (Pino JSON output) for local debugging ──
                logger_js_1.logger.info({
                    requestId,
                    clientId,
                    event: "TELEMETRY_RECEIVED",
                    sessionId: parsed.sessionId,
                    targetAppPackage: parsed.targetAppPackage,
                    failedAction: parsed.failedAction,
                    failureReason: parsed.failureReason,
                    targetLabel: parsed.targetLabel ?? null,
                    timestamp: parsed.timestamp,
                }, "Telemetry payload received");
                // ── 3. Validate required fields ─────────────────────────────
                const payload = (0, databricksClient_js_1.validateTelemetryPayload)(parsed);
                if (!payload) {
                    const errorMsg = "Invalid telemetry payload: missing or empty required fields " +
                        "(sessionId, targetAppPackage, failedAction, failureReason, timestamp)";
                    logger_js_1.logger.warn({ requestId, clientId, raw: parsed }, errorMsg);
                    if (ws.readyState === ws_1.WebSocket.OPEN) {
                        ws.send(JSON.stringify({ status: "error", requestId, message: errorMsg }));
                    }
                    return;
                }
                // ── 4. Forward to Databricks via parameterized SQL ──────────
                await (0, databricksClient_js_1.sendToDatabricks)(payload, requestId);
                // ── 5. Acknowledge success ──────────────────────────────────
                if (ws.readyState === ws_1.WebSocket.OPEN) {
                    ws.send(JSON.stringify({
                        status: "ok",
                        requestId,
                        message: "Telemetry persisted to Databricks",
                    }));
                }
            }
            catch (err) {
                const errMsg = err instanceof Error ? err.message : "Unknown processing error";
                logger_js_1.logger.error({ requestId, clientId, error: errMsg }, "Failed to process telemetry message");
                if (ws.readyState === ws_1.WebSocket.OPEN) {
                    ws.send(JSON.stringify({
                        status: "error",
                        requestId,
                        message: "Internal error processing telemetry",
                    }));
                }
            }
        });
        ws.on("close", () => {
            logger_js_1.logger.info({ clientId, channel: "telemetry" }, "Telemetry client disconnected");
        });
    });
}
