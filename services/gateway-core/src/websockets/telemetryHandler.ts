import { WebSocket, WebSocketServer } from "ws";
import { IncomingMessage } from "http";
import crypto from "crypto";
import { logger } from "../utils/logger.js";
import {
  sendToDatabricks,
  validateTelemetryPayload,
} from "../services/databricksClient.js";

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
export function setupTelemetryWebSocket(wss: WebSocketServer): void {
  wss.on("connection", (ws: WebSocket, req: IncomingMessage) => {
    const clientId = crypto.randomUUID();
    const clientIp = req.socket.remoteAddress;

    logger.info(
      { clientId, clientIp, channel: "telemetry" },
      "Telemetry client connected via WebSocket"
    );

    ws.on("message", async (raw: Buffer) => {
      const requestId = `tel_${crypto.randomUUID().slice(0, 8)}`;

      try {
        // ── 1. Parse incoming JSON ──────────────────────────────────
        const parsed: Record<string, unknown> = JSON.parse(raw.toString());

        // ── 2. Structured log (Pino JSON output) for local debugging ──
        logger.info(
          {
            requestId,
            clientId,
            event: "TELEMETRY_RECEIVED",
            sessionId: parsed.sessionId,
            targetAppPackage: parsed.targetAppPackage,
            failedAction: parsed.failedAction,
            failureReason: parsed.failureReason,
            targetLabel: parsed.targetLabel ?? null,
            timestamp: parsed.timestamp,
          },
          "Telemetry payload received"
        );

        // ── 3. Validate required fields ─────────────────────────────
        const payload = validateTelemetryPayload(parsed);
        if (!payload) {
          const errorMsg =
            "Invalid telemetry payload: missing or empty required fields " +
            "(sessionId, targetAppPackage, failedAction, failureReason, timestamp)";

          logger.warn({ requestId, clientId, raw: parsed }, errorMsg);

          if (ws.readyState === WebSocket.OPEN) {
            ws.send(
              JSON.stringify({ status: "error", requestId, message: errorMsg })
            );
          }
          return;
        }

        // ── 4. Forward to Databricks via parameterized SQL ──────────
        await sendToDatabricks(payload, requestId);

        // ── 5. Acknowledge success ──────────────────────────────────
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(
            JSON.stringify({
              status: "ok",
              requestId,
              message: "Telemetry persisted to Databricks",
            })
          );
        }
      } catch (err: unknown) {
        const errMsg =
          err instanceof Error ? err.message : "Unknown processing error";

        logger.error(
          { requestId, clientId, error: errMsg },
          "Failed to process telemetry message"
        );

        if (ws.readyState === WebSocket.OPEN) {
          ws.send(
            JSON.stringify({
              status: "error",
              requestId,
              message: "Internal error processing telemetry",
            })
          );
        }
      }
    });

    ws.on("close", () => {
      logger.info(
        { clientId, channel: "telemetry" },
        "Telemetry client disconnected"
      );
    });
  });
}
