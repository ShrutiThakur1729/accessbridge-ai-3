import { ActionHistoryEntry } from "./intentClient.js";
import { logger } from "../utils/logger.js";

export interface SessionContext {
  sessionId: string;
  userId: string;
  transcript: string;
  sessionLanguage: string;
  actionHistory: ActionHistoryEntry[];
  blacklistMemory: string[];
  lastActiveTimestamp: number;
  activePackage: string;
  zeroElementAttempts?: number;
}

export class SessionManager {
  private sessions = new Map<string, SessionContext>();
  private readonly DEFAULT_TTL_MS = 300000; // 5 minutes inactivity

  public getOrCreate(
    sessionId: string,
    userId: string = "default_user",
    sessionLanguage: string = "en-US"
  ): SessionContext {
    let session = this.sessions.get(sessionId);
    if (!session) {
      session = {
        sessionId,
        userId,
        transcript: "",
        sessionLanguage,
        actionHistory: [],
        blacklistMemory: [],
        lastActiveTimestamp: Date.now(),
        activePackage: "",
        zeroElementAttempts: 0,
      };
      this.sessions.set(sessionId, session);
    } else {
      session.lastActiveTimestamp = Date.now();
      if (userId && userId !== "default_user") {
        session.userId = userId;
      }
      if (sessionLanguage) {
        session.sessionLanguage = sessionLanguage;
      }
    }
    return session;
  }

  public get(sessionId: string): SessionContext | undefined {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.lastActiveTimestamp = Date.now();
    }
    return session;
  }

  public update(session: SessionContext): void {
    if (!session.lastActiveTimestamp) {
      session.lastActiveTimestamp = Date.now();
    }
    this.sessions.set(session.sessionId, session);
  }

  public touch(sessionId: string): void {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.lastActiveTimestamp = Date.now();
    }
  }

  public clear(sessionId: string): boolean {
    return this.sessions.delete(sessionId);
  }

  public cleanupStale(ttlMs: number = this.DEFAULT_TTL_MS): number {
    const now = Date.now();
    let evictedCount = 0;
    for (const [sessionId, session] of this.sessions.entries()) {
      if (now - session.lastActiveTimestamp > ttlMs) {
        this.sessions.delete(sessionId);
        evictedCount++;
      }
    }
    if (evictedCount > 0) {
      logger.info({ evictedCount }, "Cleaned up inactive gateway session contexts");
    }
    return evictedCount;
  }

  public size(): number {
    return this.sessions.size;
  }
}

export const sessionManager = new SessionManager();
