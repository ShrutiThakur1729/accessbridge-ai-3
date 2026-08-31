import pino from "pino";
import { ENV } from "../config/env.js";

export const logger = pino({
  level: ENV.NODE_ENV === "production" ? "info" : "debug",
  transport: ENV.NODE_ENV !== "production" ? { target: "pino-pretty" } : undefined,
});