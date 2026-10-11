import axios, { AxiosError } from "axios";
import { ENV } from "../config/env.js";
import { logger } from "../utils/logger.js";

/**
 * Telemetry payload shape received from WebSocket clients.
 *
 * `targetLabel` is optional/nullable — some failure types (e.g.
 * BACKEND_DIRECTIVE) have no specific UI target element.
 */
export interface TelemetryPayload {
  sessionId: string;
  targetAppPackage: string;
  failedAction: string;
  failureReason: string;
  timestamp: string;
  targetLabel?: string | null;
}

/**
 * Validates that all required telemetry fields are present and non-empty strings.
 * `targetLabel` is optional: if present it must be a string, but it is allowed
 * to be absent, null, or empty.
 * Returns a normalized TelemetryPayload or null if validation fails.
 */
function sanitizePii(text: string | null | undefined): string | null {
  if (!text) return null;
  let clean = text
    .replace(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g, "[REDACTED_EMAIL]")
    .replace(/(?:\+?\d{1,3}[\s-]?)?\(?\d{3,5}\)?[\s-]?\d{3,5}[\s-]?\d{3,5}/g, "[REDACTED_PHONE]")
    .replace(/\b(?:\d[ -]*?){13,19}\b/g, "[REDACTED_CARD]");
  return clean.trim() || null;
}

import crypto from "crypto";

function anonymizeSessionId(id: string): string {
  return crypto.createHash("sha256").update(id).digest("hex").slice(0, 16);
}

export function validateTelemetryPayload(
  data: Record<string, unknown>
): TelemetryPayload | null {
  const required = [
    "sessionId",
    "targetAppPackage",
    "failedAction",
    "failureReason",
    "timestamp",
  ] as const;

  for (const field of required) {
    if (typeof data[field] !== "string" || data[field].trim() === "") {
      return null;
    }
  }

  if (
    data.targetLabel !== undefined &&
    data.targetLabel !== null &&
    typeof data.targetLabel !== "string"
  ) {
    return null;
  }

  const rawLabel = typeof data.targetLabel === "string" ? data.targetLabel.trim() : null;
  const rawReason = data.failureReason as string;

  return {
    sessionId: anonymizeSessionId((data.sessionId as string).trim()),
    targetAppPackage: (data.targetAppPackage as string).trim(),
    failedAction: (data.failedAction as string).trim(),
    failureReason: sanitizePii(rawReason) || "UNKNOWN_FAILURE",
    timestamp: (data.timestamp as string).trim(),
    targetLabel: sanitizePii(rawLabel),
  };
}

/**
 * Sends a validated telemetry payload to Databricks SQL Statement API
 * using parameterized queries to prevent SQL injection.
 *
 * Databricks SQL Statement API docs:
 * POST https://{host}/api/2.0/sql/statements
 *
 * The `parameters` array binds named placeholders in the SQL statement,
 * ensuring user-supplied values are never interpolated into the query string.
 */
export async function sendToDatabricks(
  payload: TelemetryPayload,
  requestId: string
): Promise<void> {
  const { DATABRICKS_HOST, DATABRICKS_TOKEN, DATABRICKS_HTTP_PATH } = ENV;

  if (!DATABRICKS_HOST || !DATABRICKS_TOKEN || !DATABRICKS_HTTP_PATH) {
    logger.warn(
      { requestId },
      "Databricks credentials not configured — skipping telemetry export"
    );
    return;
  }

  const url = `https://${DATABRICKS_HOST}/api/2.0/sql/statements`;

  // Parameterized query: named placeholders are resolved server-side by Databricks.
  // No string interpolation of user data touches the SQL statement itself.
  const requestBody = {
    warehouse_id: DATABRICKS_HTTP_PATH,
    statement:
      "INSERT INTO accessbridge_telemetry.failure_logs " +
      "(session_id, target_app_package, failed_action, failure_reason, event_timestamp, target_label) " +
      "VALUES (:sessionId, :targetAppPackage, :failedAction, :failureReason, :timestamp, :targetLabel)",
    parameters: [
      { name: "sessionId", value: payload.sessionId, type: "STRING" },
      {
        name: "targetAppPackage",
        value: payload.targetAppPackage,
        type: "STRING",
      },
      { name: "failedAction", value: payload.failedAction, type: "STRING" },
      { name: "failureReason", value: payload.failureReason, type: "STRING" },
      { name: "timestamp", value: payload.timestamp, type: "STRING" },
      {
        name: "targetLabel",
        value: payload.targetLabel ?? null,
        type: "STRING",
      },
    ],
    wait_timeout: "10s",
  };

  try {
    const response = await axios.post(url, requestBody, {
      headers: {
        Authorization: `Bearer ${DATABRICKS_TOKEN}`,
        "Content-Type": "application/json",
      },
      timeout: 15_000,
    });

    logger.info(
      {
        requestId,
        databricksStatus: response.data?.status?.state ?? "UNKNOWN",
        statementId: response.data?.statement_id,
      },
      "Telemetry payload delivered to Databricks"
    );
  } catch (err) {
    const axiosErr = err as AxiosError;
    logger.error(
      {
        requestId,
        status: axiosErr.response?.status,
        data: axiosErr.response?.data,
        message: axiosErr.message,
      },
      "Failed to deliver telemetry payload to Databricks"
    );
    throw err;
  }
}

/**
 * Queries Databricks for recent failure rows matching the given
 * `targetAppPackage` and formats them into the "actiontype:targetlabel"
 * blacklist format expected by planner_service.py.
 *
 * - Returns at most 20 most-recent rows from the last 7 days
 * - Skips rows where `target_label` is NULL or empty
 * - Deduplicates the resulting list
 * - Returns `[]` (never throws) if Databricks is unconfigured or unreachable,
 *   so a lookup failure never blocks the planning flow
 */
export async function getBlacklistMemory(
  userId: string,
  targetAppPackage: string
): Promise<string[]> {
  const { DATABRICKS_HOST, DATABRICKS_TOKEN, DATABRICKS_HTTP_PATH } = ENV;

  if (!DATABRICKS_HOST || !DATABRICKS_TOKEN || !DATABRICKS_HTTP_PATH) {
    return [];
  }

  const url = `https://${DATABRICKS_HOST}/api/2.0/sql/statements`;

  const requestBody = {
    warehouse_id: DATABRICKS_HTTP_PATH,
    statement:
      "SELECT failed_action, target_label " +
      "FROM accessbridge_telemetry.failure_logs " +
      "WHERE target_app_package = :targetAppPackage " +
      "AND target_label IS NOT NULL " +
      "AND target_label != '' " +
      "AND event_timestamp >= DATE_SUB(CURRENT_DATE(), 7) " +
      "ORDER BY event_timestamp DESC " +
      "LIMIT 20",
    parameters: [
      {
        name: "targetAppPackage",
        value: targetAppPackage,
        type: "STRING",
      },
    ],
    wait_timeout: "15s",
  };

  try {
    const response = await axios.post(url, requestBody, {
      headers: {
        Authorization: `Bearer ${DATABRICKS_TOKEN}`,
        "Content-Type": "application/json",
      },
      timeout: 20_000,
    });

    const status = response.data?.status?.state;
    if (status !== "SUCCEEDED") {
      logger.warn(
        { status, statementId: response.data?.statement_id },
        "Databricks blacklist query did not succeed — returning empty blacklist"
      );
      return [];
    }

    // Databricks SQL Statement API returns results in:
    //   result.data_array: string[][] (each row is an array of column values)
    // Column order matches SELECT: [0] = failed_action, [1] = target_label
    const rows: string[][] = response.data?.result?.data_array ?? [];
    const seen = new Set<string>();
    const blacklist: string[] = [];

    for (const row of rows) {
      const failedAction = (row[0] ?? "").trim().toLowerCase();
      const targetLabel = (row[1] ?? "").trim().toLowerCase();

      if (!targetLabel) continue;

      // Format: "actiontype:targetlabel" (e.g. "click:samsung news")
      // matching planner_service.py's blacklist matching logic
      const entry = failedAction
        ? `${failedAction}:${targetLabel}`
        : targetLabel;

      if (!seen.has(entry)) {
        seen.add(entry);
        blacklist.push(entry);
      }

      // Also add bare label for direct label matching
      if (failedAction && !seen.has(targetLabel)) {
        seen.add(targetLabel);
        blacklist.push(targetLabel);
      }
    }

    logger.info(
      { targetAppPackage, entriesReturned: blacklist.length },
      "Retrieved Databricks blacklist memory for planning"
    );

    return blacklist;
  } catch (err) {
    const axiosErr = err as AxiosError;
    logger.warn(
      {
        status: axiosErr.response?.status,
        message: axiosErr.message,
      },
      "Databricks blacklist lookup failed — returning empty blacklist"
    );
    return [];
  }
}
