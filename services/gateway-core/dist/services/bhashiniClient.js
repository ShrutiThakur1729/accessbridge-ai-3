"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.BhashiniClient = exports.BhashiniError = void 0;
const env_js_1 = require("../config/env.js");
const logger_js_1 = require("../utils/logger.js");
class BhashiniError extends Error {
    code;
    constructor(code, message) {
        super(message);
        this.code = code;
        this.name = "BhashiniError";
    }
}
exports.BhashiniError = BhashiniError;
/** Centralized HTTP client for Bhashini Dhruva Inference Pipeline API */
class BhashiniClient {
    /** Check if Bhashini API credentials are configured */
    static isConfigured() {
        return Boolean(env_js_1.ENV.BHASHINI_API_KEY && env_js_1.ENV.BHASHINI_API_KEY.trim().length > 0);
    }
    /**
     * Helper method to send a Dhruva Inference Pipeline request
     */
    static async executePipeline(pipelinePayload) {
        if (!this.isConfigured()) {
            throw new BhashiniError("CONFIG_ERROR", "Bhashini API key is not configured.");
        }
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), env_js_1.ENV.BHASHINI_TIMEOUT_MS);
        const headers = {
            "Content-Type": "application/json",
            Authorization: env_js_1.ENV.BHASHINI_API_KEY,
        };
        if (env_js_1.ENV.BHASHINI_UDYAT_KEY) {
            headers["ulcaApiKey"] = env_js_1.ENV.BHASHINI_UDYAT_KEY;
        }
        try {
            const response = await fetch(env_js_1.ENV.BHASHINI_URL, {
                method: "POST",
                headers,
                body: JSON.stringify(pipelinePayload),
                signal: controller.signal,
            });
            clearTimeout(timeoutId);
            if (!response.ok) {
                const errorText = await response.text();
                logger_js_1.logger.warn({ status: response.status, errorText }, "Bhashini API rejected pipeline request");
                throw new BhashiniError("BHASHINI_UNAVAILABLE", `Bhashini API HTTP error [${response.status}]`);
            }
            const responseData = await response.json();
            return responseData;
        }
        catch (error) {
            clearTimeout(timeoutId);
            if (error?.name === "AbortError") {
                throw new BhashiniError("TIMEOUT", `Bhashini API request timed out after ${env_js_1.ENV.BHASHINI_TIMEOUT_MS}ms`);
            }
            if (error instanceof BhashiniError) {
                throw error;
            }
            throw new BhashiniError("BHASHINI_UNAVAILABLE", `Unable to reach Bhashini API: ${error?.message || error}`);
        }
    }
    /**
     * Automatic Speech Recognition (ASR)
     */
    static async transcribeAudio(audioBuffer, languageCode = "hi") {
        const normLang = (languageCode || "hi").toLowerCase().slice(0, 2);
        const audioBase64 = audioBuffer.toString("base64");
        const payload = {
            pipelineTasks: [
                {
                    taskType: "asr",
                    config: {
                        language: {
                            sourceLanguage: normLang,
                        },
                    },
                },
            ],
            inputData: {
                audio: [
                    {
                        audioContent: audioBase64,
                    },
                ],
            },
        };
        const response = await this.executePipeline(payload);
        const taskResponse = response?.pipelineResponse?.find((t) => t.taskType === "asr");
        const transcript = taskResponse?.output?.[0]?.source?.trim();
        if (!transcript) {
            throw new BhashiniError("DECODE_ERROR", "No speech transcript returned by Bhashini ASR.");
        }
        logger_js_1.logger.info({ languageCode: normLang, transcriptLength: transcript.length }, "Bhashini ASR transcript generated");
        return {
            transcript,
            detectedLanguage: normLang,
        };
    }
    /**
     * Text-to-Speech (TTS)
     */
    static async generateSpeech(text, languageCode = "hi") {
        if (!text || !text.trim()) {
            throw new BhashiniError("CONFIG_ERROR", "Text parameter is empty for TTS generation.");
        }
        const normLang = (languageCode || "hi").toLowerCase().slice(0, 2);
        const payload = {
            pipelineTasks: [
                {
                    taskType: "tts",
                    config: {
                        language: {
                            sourceLanguage: normLang,
                        },
                        gender: "female",
                    },
                },
            ],
            inputData: {
                input: [
                    {
                        source: text.trim(),
                    },
                ],
            },
        };
        const response = await this.executePipeline(payload);
        const taskResponse = response?.pipelineResponse?.find((t) => t.taskType === "tts");
        const audioBase64 = taskResponse?.audio?.[0]?.audioContent;
        if (!audioBase64) {
            throw new BhashiniError("DECODE_ERROR", "No audio output returned by Bhashini TTS.");
        }
        logger_js_1.logger.info({ languageCode: normLang, textLength: text.length }, "Bhashini TTS audio generated");
        return {
            audioBase64,
            mimeType: "audio/mp3",
        };
    }
    /**
     * Neural Machine Translation (NMT)
     */
    static async translateText(text, sourceLang, targetLang) {
        if (!text || !text.trim()) {
            throw new BhashiniError("CONFIG_ERROR", "Text parameter is empty for NMT translation.");
        }
        const sLang = sourceLang.toLowerCase().slice(0, 2);
        const tLang = targetLang.toLowerCase().slice(0, 2);
        const payload = {
            pipelineTasks: [
                {
                    taskType: "translation",
                    config: {
                        language: {
                            sourceLanguage: sLang,
                            targetLanguage: tLang,
                        },
                    },
                },
            ],
            inputData: {
                input: [
                    {
                        source: text.trim(),
                    },
                ],
            },
        };
        const response = await this.executePipeline(payload);
        const taskResponse = response?.pipelineResponse?.find((t) => t.taskType === "translation");
        const translatedText = taskResponse?.output?.[0]?.target?.trim();
        if (!translatedText) {
            throw new BhashiniError("DECODE_ERROR", "No translation output returned by Bhashini NMT.");
        }
        logger_js_1.logger.info({ sourceLang: sLang, targetLang: tLang }, "Bhashini NMT translation generated");
        return {
            translatedText,
            sourceLanguage: sLang,
            targetLanguage: tLang,
        };
    }
}
exports.BhashiniClient = BhashiniClient;
