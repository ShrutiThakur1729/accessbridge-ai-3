import { ENV } from "../config/env.js";
import { logger } from "../utils/logger.js";
import { BhashiniClient } from "./bhashiniClient.js";

export class SttError extends Error {
  constructor(public readonly code: "AUDIO_CORRUPT" | "AUDIO_SILENT" | "STT_UNAVAILABLE", message: string) {
    super(message);
    this.name = "SttError";
  }
}

export interface SttResult {
  transcript: string;
  language?: string;
}

function mapLanguageCode(rawLang: unknown): string | undefined {
  if (!rawLang || typeof rawLang !== "string") return undefined;
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
export class SttClient {
  static async transcribe(audio: Buffer, mimeType = "audio/webm", targetLanguage = "hi"): Promise<SttResult> {
    if (audio.length < ENV.MIN_AUDIO_BYTES || audio.every((byte) => byte === 0)) {
      throw new SttError("AUDIO_SILENT", "Audio did not contain enough non-silent samples.");
    }

    // Try Whisper primary if configured
    if (ENV.STT_URL && ENV.STT_API_KEY) {
      try {
        const form = new FormData();
        form.append("file", new Blob([audio], { type: mimeType }), "voice-input.webm");
        form.append("model", ENV.STT_MODEL);
        form.append("response_format", "verbose_json");

        const headers: Record<string, string> = {
          Authorization: `Bearer ${ENV.STT_API_KEY}`,
        };

        const response = await fetch(ENV.STT_URL, {
          method: "POST",
          headers,
          body: form,
        });

        if (response.ok) {
          const body = (await response.json()) as { text?: unknown; transcript?: unknown; language?: unknown; language_code?: unknown };
          const transcript = String(body.text ?? body.transcript ?? "").trim();
          if (transcript) {
            const detectedLang = mapLanguageCode(body.language ?? body.language_code);
            return { transcript, language: detectedLang };
          }
        }
      } catch (err: any) {
        logger.warn({ error: err?.message || err }, "Whisper STT primary failed; trying Bhashini ASR fallback");
      }
    }

    // Fallback: Bhashini ASR if configured
    if (BhashiniClient.isConfigured()) {
      try {
        const bhashiniResult = await BhashiniClient.transcribeAudio(audio, targetLanguage);
        if (bhashiniResult.transcript) {
          return {
            transcript: bhashiniResult.transcript,
            language: bhashiniResult.detectedLanguage,
          };
        }
      } catch (err: any) {
        logger.warn({ error: err?.message || err }, "Bhashini ASR fallback failed");
      }
    }

    throw new SttError("STT_UNAVAILABLE", "Speech-to-text service is currently unavailable.");
  }
}
