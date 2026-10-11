import fs from "fs";
import path from "path";
import { SttClient } from "../src/services/sttClient.js";

async function main() {
  const filePath = process.argv[2];
  if (!filePath) {
    console.error("Usage: npx tsx scripts/test-stt.ts <path-to-audio-file>");
    process.exit(1);
  }

  const absolutePath = path.resolve(filePath);
  if (!fs.existsSync(absolutePath)) {
    console.error(`Error: File not found at ${absolutePath}`);
    process.exit(1);
  }

  const audioBuffer = fs.readFileSync(absolutePath);
  let mimeType = "audio/webm";
  if (absolutePath.endsWith(".mp3")) mimeType = "audio/mp3";
  else if (absolutePath.endsWith(".wav")) mimeType = "audio/wav";
  else if (absolutePath.endsWith(".m4a")) mimeType = "audio/m4a";

  console.log(`Testing SttClient.transcribe with file: ${path.basename(absolutePath)} (${audioBuffer.length} bytes, mime: ${mimeType})...`);

  try {
    const result = await SttClient.transcribe(audioBuffer, mimeType);
    console.log("STT Result:", JSON.stringify(result, null, 2));
  } catch (err: any) {
    console.error("STT Failed:", err.message || err);
    process.exit(1);
  }
}

main();
