"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SttClient = exports.SttError = void 0;
const env_js_1 = require("../config/env.js");
const logger_js_1 = require("../utils/logger.js");
const bhashiniClient_js_1 = require("./bhashiniClient.js");
class SttError extends Error {
    code;
    constructor(code, message) {
        super(message);
        this.code = code;
        this.name = "SttError";
    }
}
exports.SttError = SttError;
function mapLanguageCode(rawLang) {
    if (!rawLang || typeof rawLang !== "string")
        return undefined;
    const normalized = rawLang.trim().toLowerCase();
    if (normalized === "english" || normalized === "en" || normalized === "en-us" || normalized === "en-in") {
        return "en";
    }
    if (normalized === "hindi" || normalized === "hi" || normalized === "hi-in") {
        return "hi";
    }
    if (normalized === "marathi" || normalized === "mr" || normalized === "mr-in") {
        return "mr";
    }
    return undefined;
}
/** HTTP adapter for Speech-to-Text (Whisper primary, Bhashini fallback/multilingual) */
class SttClient {
    static async transcribe(audio, mimeType = "audio/webm", targetLanguage = "hi") {
        if (audio.length < env_js_1.ENV.MIN_AUDIO_BYTES || audio.every((byte) => byte === 0)) {
            throw new SttError("AUDIO_SILENT", "Audio did not contain enough non-silent samples.");
        }
        // Try Whisper primary if configured
        if (env_js_1.ENV.STT_URL && env_js_1.ENV.STT_API_KEY) {
            try {
                const form = new FormData();
                form.append("file", new Blob([audio], { type: mimeType }), "voice-input.webm");
                form.append("model", env_js_1.ENV.STT_MODEL);
                form.append("response_format", "verbose_json");
                const headers = {
                    Authorization: `Bearer ${env_js_1.ENV.STT_API_KEY}`,
                };
                const response = await fetch(env_js_1.ENV.STT_URL, {
                    method: "POST",
                    headers,
                    body: form,
                });
                if (response.ok) {
                    const body = (await response.json());
                    const transcript = String(body.text ?? body.transcript ?? "").trim();
                    if (transcript) {
                        const detectedLang = mapLanguageCode(body.language ?? body.language_code);
                        return { transcript, language: detectedLang };
                    }
                }
            }
            catch (err) {
                logger_js_1.logger.warn({ error: err?.message || err }, "Whisper STT primary failed; trying Bhashini ASR fallback");
            }
        }
        // Fallback: Bhashini ASR if configured
        if (bhashiniClient_js_1.BhashiniClient.isConfigured()) {
            try {
                const bhashiniResult = await bhashiniClient_js_1.BhashiniClient.transcribeAudio(audio, targetLanguage);
                if (bhashiniResult.transcript) {
                    return {
                        transcript: bhashiniResult.transcript,
                        language: bhashiniResult.detectedLanguage,
                    };
                }
            }
            catch (err) {
                logger_js_1.logger.warn({ error: err?.message || err }, "Bhashini ASR fallback failed");
            }
        }
        throw new SttError("STT_UNAVAILABLE", "Speech-to-text service is currently unavailable.");
    }
}
exports.SttClient = SttClient;
