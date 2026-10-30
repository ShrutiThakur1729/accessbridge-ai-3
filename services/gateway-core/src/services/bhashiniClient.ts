import { ENV } from "../config/env.js";
import { logger } from "../utils/logger.js";

export class BhashiniError extends Error {
  constructor(public readonly code: "BHASHINI_UNAVAILABLE" | "CONFIG_ERROR" | "TIMEOUT" | "DECODE_ERROR", message: string) {
    super(message);
    this.name = "BhashiniError";
  }
}

export interface BhashiniAsrResult {
  transcript: string;
  detectedLanguage?: string;
}

export interface BhashiniTtsResult {
  audioBase64: string;
  mimeType: string;
}

export interface BhashiniTranslationResult {
  translatedText: string;
  sourceLanguage: string;
  targetLanguage: string;
}

/** Centralized HTTP client for Bhashini Dhruva Inference Pipeline API */
export class BhashiniClient {
  /** Check if Bhashini API credentials are configured */
  static isConfigured(): boolean {
    return Boolean(ENV.BHASHINI_API_KEY && ENV.BHASHINI_API_KEY.trim().length > 0);
  }

  /**
   * Helper method to send a Dhruva Inference Pipeline request
   */
  private static async executePipeline(pipelinePayload: Record<string, any>): Promise<any> {
    if (!this.isConfigured()) {
      throw new BhashiniError("CONFIG_ERROR", "Bhashini API key is not configured.");
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), ENV.BHASHINI_TIMEOUT_MS);

    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Authorization: ENV.BHASHINI_API_KEY,
    };

    if (ENV.BHASHINI_UDYAT_KEY) {
      headers["ulcaApiKey"] = ENV.BHASHINI_UDYAT_KEY;
    }

    try {
      const response = await fetch(ENV.BHASHINI_URL, {
        method: "POST",
        headers,
        body: JSON.stringify(pipelinePayload),
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        const errorText = await response.text();
        logger.warn({ status: response.status, errorText }, "Bhashini API rejected pipeline request");
        throw new BhashiniError("BHASHINI_UNAVAILABLE", `Bhashini API HTTP error [${response.status}]`);
      }

      const responseData = await response.json();
      return responseData;
    } catch (error: any) {
      clearTimeout(timeoutId);
      if (error?.name === "AbortError") {
        throw new BhashiniError("TIMEOUT", `Bhashini API request timed out after ${ENV.BHASHINI_TIMEOUT_MS}ms`);
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
  static async transcribeAudio(audioBuffer: Buffer, languageCode = "hi"): Promise<BhashiniAsrResult> {
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
    const taskResponse = response?.pipelineResponse?.find((t: any) => t.taskType === "asr");
    const transcript = taskResponse?.output?.[0]?.source?.trim();

    if (!transcript) {
      throw new BhashiniError("DECODE_ERROR", "No speech transcript returned by Bhashini ASR.");
    }

    logger.info({ languageCode: normLang, transcriptLength: transcript.length }, "Bhashini ASR transcript generated");
    return {
      transcript,
      detectedLanguage: normLang,
    };
  }

  /**
   * Text-to-Speech (TTS)
   */
  static async generateSpeech(text: string, languageCode = "hi"): Promise<BhashiniTtsResult> {
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
    const taskResponse = response?.pipelineResponse?.find((t: any) => t.taskType === "tts");
    const audioBase64 = taskResponse?.audio?.[0]?.audioContent;

    if (!audioBase64) {
      throw new BhashiniError("DECODE_ERROR", "No audio output returned by Bhashini TTS.");
    }

    logger.info({ languageCode: normLang, textLength: text.length }, "Bhashini TTS audio generated");
    return {
      audioBase64,
      mimeType: "audio/mp3",
    };
  }

  /**
   * Neural Machine Translation (NMT)
   */
  static async translateText(text: string, sourceLang: string, targetLang: string): Promise<BhashiniTranslationResult> {
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
    const taskResponse = response?.pipelineResponse?.find((t: any) => t.taskType === "translation");
    const translatedText = taskResponse?.output?.[0]?.target?.trim();

    if (!translatedText) {
      throw new BhashiniError("DECODE_ERROR", "No translation output returned by Bhashini NMT.");
    }

    logger.info({ sourceLang: sLang, targetLang: tLang }, "Bhashini NMT translation generated");
    return {
      translatedText,
      sourceLanguage: sLang,
      targetLanguage: tLang,
    };
  }
}
