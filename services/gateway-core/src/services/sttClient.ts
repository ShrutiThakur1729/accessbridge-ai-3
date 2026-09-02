import { ENV } from "../config/env.js";
import { logger } from "../utils/logger.js";

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

/** HTTP adapter for a Whisper-compatible STT endpoint. Audio stays in memory. */
export class SttClient {
  static async transcribe(audio: Buffer, mimeType = "audio/webm"): Promise<SttResult> {
    if (audio.length < ENV.MIN_AUDIO_BYTES || audio.every((byte) => byte === 0)) {
      throw new SttError("AUDIO_SILENT", "Audio did not contain enough non-silent samples.");
    }
    if (!ENV.STT_URL) {
      throw new SttError("STT_UNAVAILABLE", "Speech-to-text service is not configured.");
    }

    const form = new FormData();
    form.append("file", new Blob([audio], { type: mimeType }), "voice-input.webm");
    form.append("model", ENV.STT_MODEL);

    let response: Response;
    try {
      response = await fetch(ENV.STT_URL, { method: "POST", body: form });
    } catch (error: any) {
      throw new SttError("STT_UNAVAILABLE", `Unable to reach speech-to-text service: ${error?.message || error}`);
    }

    if (!response.ok) {
      const detail = await response.text();
      logger.warn({ status: response.status, detail }, "STT engine rejected audio");
      throw new SttError("AUDIO_CORRUPT", "Speech-to-text service could not decode the audio.");
    }

    let body: { text?: unknown; transcript?: unknown; language?: unknown; language_code?: unknown };
    try {
      body = (await response.json()) as { text?: unknown; transcript?: unknown; language?: unknown; language_code?: unknown };
    } catch {
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
