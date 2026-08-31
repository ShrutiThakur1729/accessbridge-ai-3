import dotenv from "dotenv";
dotenv.config();

export const ENV = {
  PORT: process.env.PORT ? parseInt(process.env.PORT, 10) : 3000,
  NODE_ENV: process.env.NODE_ENV || "development",
  INTENT_SAFETY_URL: process.env.INTENT_SAFETY_URL || "http://localhost:8000/api/v1",
};