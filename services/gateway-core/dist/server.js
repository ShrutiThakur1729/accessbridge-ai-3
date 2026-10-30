"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = __importDefault(require("express"));
const http_1 = __importDefault(require("http"));
const ws_1 = require("ws");
const cors_1 = __importDefault(require("cors"));
const pino_http_1 = __importDefault(require("pino-http"));
const compression_1 = __importDefault(require("compression"));
const crypto_1 = __importDefault(require("crypto"));
const env_js_1 = require("./config/env.js");
const logger_js_1 = require("./utils/logger.js");
const wsHandler_js_1 = require("./websockets/wsHandler.js");
const telemetryHandler_js_1 = require("./websockets/telemetryHandler.js");
const app = (0, express_1.default)();
const server = http_1.default.createServer(app);
const wss = new ws_1.WebSocketServer({ server });
// Performance & Security Middlewares
app.use((0, compression_1.default)()); // Gzip/Brotli compression for payload optimization
app.use((0, cors_1.default)());
app.use(express_1.default.json());
// Telemetry HTTP Request Logging with Correlation IDs
app.use((0, pino_http_1.default)({
    logger: logger_js_1.logger,
    genReqId: (req) => req.headers["x-request-id"] ||
        `req_${crypto_1.default.randomUUID().slice(0, 8)}`,
}));
// Health Check Endpoint
app.get("/health", (req, res) => {
    res.status(200).json({
        status: "healthy",
        service: "gateway-core",
        environment: env_js_1.ENV.NODE_ENV,
        timestamp: new Date().toISOString(),
    });
});
// Setup WebSocket Routing & Multiplexing
(0, wsHandler_js_1.setupWebSocketServer)(wss);
// Node.js HTTP Server Network Resiliency & Keep-Alive Tuning
server.keepAliveTimeout = 65000; // 65 seconds (exceeds standard 60s load balancer idle timeouts)
server.headersTimeout = 66000; // Must be slightly larger than keepAliveTimeout
server.requestTimeout = 30000; // 30 seconds max to process client headers/payloads
// Start Server
server.listen(env_js_1.ENV.PORT, () => {
    logger_js_1.logger.info(`Gateway-Core (Service 01) running on port ${env_js_1.ENV.PORT}`);
});
// ── Telemetry WebSocket Server (Databricks pipeline) ────────────────────
const telemetryWss = new ws_1.WebSocketServer({ port: env_js_1.ENV.TELEMETRY_WS_PORT });
(0, telemetryHandler_js_1.setupTelemetryWebSocket)(telemetryWss);
logger_js_1.logger.info(`Telemetry WebSocket receiver listening on ws://0.0.0.0:${env_js_1.ENV.TELEMETRY_WS_PORT}`);
