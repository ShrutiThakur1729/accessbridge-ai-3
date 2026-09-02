"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ENV = void 0;
const dotenv_1 = __importDefault(require("dotenv"));
dotenv_1.default.config();
exports.ENV = {
    PORT: process.env.PORT ? parseInt(process.env.PORT, 10) : 3000,
    NODE_ENV: process.env.NODE_ENV || "development",
    INTENT_SAFETY_URL: process.env.INTENT_SAFETY_URL || "http://127.0.0.1:8000/api/v1",
    VISION_PERCEPTION_URL: process.env.VISION_PERCEPTION_URL || "http://127.0.0.1:8010/api/v1",
    STT_URL: process.env.STT_URL || "",
    STT_MODEL: process.env.STT_MODEL || "whisper-1",
    MIN_AUDIO_BYTES: process.env.MIN_AUDIO_BYTES ? parseInt(process.env.MIN_AUDIO_BYTES, 10) : 256,
    MAX_AUDIO_BYTES: process.env.MAX_AUDIO_BYTES ? parseInt(process.env.MAX_AUDIO_BYTES, 10) : 25 * 1024 * 1024,
};
