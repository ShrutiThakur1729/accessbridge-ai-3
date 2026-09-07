import express from "express";
import http from "http";
import { WebSocketServer } from "ws";
import cors from "cors";
import pinoHttp from "pino-http";
import compression from "compression";
import crypto from "crypto";

import { ENV } from "./config/env.js";
import { logger } from "./utils/logger.js";
import { setupWebSocketServer } from "./websockets/wsHandler.js";
import { setupTelemetryWebSocket } from "./websockets/telemetryHandler.js";

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// Performance & Security Middlewares
app.use(compression() as any); // Gzip/Brotli compression for payload optimization
app.use(cors());
app.use(express.json());

// Telemetry HTTP Request Logging with Correlation IDs
app.use(
  pinoHttp({
    logger,
    genReqId: (req) =>
      (req.headers["x-request-id"] as string) ||
      `req_${crypto.randomUUID().slice(0, 8)}`,
  })
);

// Health Check Endpoint
app.get("/health", (req, res) => {
  res.status(200).json({
    status: "healthy",
    service: "gateway-core",
    environment: ENV.NODE_ENV,
    timestamp: new Date().toISOString(),
  });
});

// Setup WebSocket Routing & Multiplexing
setupWebSocketServer(wss);

// Node.js HTTP Server Network Resiliency & Keep-Alive Tuning
server.keepAliveTimeout = 65000; // 65 seconds (exceeds standard 60s load balancer idle timeouts)
server.headersTimeout = 66000;   // Must be slightly larger than keepAliveTimeout
server.requestTimeout = 30000;   // 30 seconds max to process client headers/payloads

// Start Server
server.listen(ENV.PORT, () => {
  logger.info(`Gateway-Core (Service 01) running on port ${ENV.PORT}`);
});

// ── Telemetry WebSocket Server (Databricks pipeline) ────────────────────
const telemetryWss = new WebSocketServer({ port: ENV.TELEMETRY_WS_PORT });
setupTelemetryWebSocket(telemetryWss);
logger.info(
  `Telemetry WebSocket receiver listening on ws://0.0.0.0:${ENV.TELEMETRY_WS_PORT}`
);