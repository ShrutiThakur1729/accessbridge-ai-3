"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SttClient = exports.SttError = void 0;
const env_js_1 = require("../config/env.js");
const logger_js_1 = require("../utils/logger.js");
class SttError extends Error {
    code;
    constructor(code, message) {
        super(message);
        this.code = code;
        this.name = "SttError";
    }
}
exports.SttError = SttError;
/** HTTP adapter for a Whisper-compatible STT endpoint. Audio stays in memory. */
class SttClient {
    static async transcribe(audio, mimeType = "audio/webm") {
        if (audio.length < env_js_1.ENV.MIN_AUDIO_BYTES || audio.every((byte) => byte === 0)) {
            throw new SttError("AUDIO_SILENT", "Audio did not contain enough non-silent samples.");
        }
        if (!env_js_1.ENV.STT_URL) {
            throw new SttError("STT_UNAVAILABLE", "Speech-to-text service is not configured.");
        }
        const form = new FormData();
        form.append("file", new Blob([audio], { type: mimeType }), "voice-input.webm");
        form.append("model", env_js_1.ENV.STT_MODEL);
        let response;
        try {
            response = await fetch(env_js_1.ENV.STT_URL, { method: "POST", body: form });
        }
        catch (error) {
            throw new SttError("STT_UNAVAILABLE", `Unable to reach speech-to-text service: ${error?.message || error}`);
        }
        if (!response.ok) {
            const detail = await response.text();
            logger_js_1.logger.warn({ status: response.status, detail }, "STT engine rejected audio");
            throw new SttError("AUDIO_CORRUPT", "Speech-to-text service could not decode the audio.");
        }
        let body;
        try {
            body = (await response.json());
        }
        catch {
            throw new SttError("AUDIO_CORRUPT", "Speech-to-text service returned an invalid response.");
        }
        const transcript = String(body.text ?? body.transcript ?? "").trim();
        if (!transcript) {
            throw new SttError("AUDIO_SILENT", "No speech was detected in the audio.");
        }
        const language = String(body.language_code ?? body.language ?? "").trim();
        return { transcript, language: language || undefined };
    }
}
exports.SttClient = SttClient;
