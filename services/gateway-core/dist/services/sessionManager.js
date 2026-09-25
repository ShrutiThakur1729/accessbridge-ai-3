"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.sessionManager = exports.SessionManager = void 0;
const logger_js_1 = require("../utils/logger.js");
class SessionManager {
    sessions = new Map();
    DEFAULT_TTL_MS = 300000; // 5 minutes inactivity
    getOrCreate(sessionId, userId = "default_user", sessionLanguage = "en-US") {
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
        }
        else {
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
    get(sessionId) {
        const session = this.sessions.get(sessionId);
        if (session) {
            session.lastActiveTimestamp = Date.now();
        }
        return session;
    }
    update(session) {
        if (!session.lastActiveTimestamp) {
            session.lastActiveTimestamp = Date.now();
        }
        this.sessions.set(session.sessionId, session);
    }
    touch(sessionId) {
        const session = this.sessions.get(sessionId);
        if (session) {
            session.lastActiveTimestamp = Date.now();
        }
    }
    clear(sessionId) {
        return this.sessions.delete(sessionId);
    }
    cleanupStale(ttlMs = this.DEFAULT_TTL_MS) {
        const now = Date.now();
        let evictedCount = 0;
        for (const [sessionId, session] of this.sessions.entries()) {
            if (now - session.lastActiveTimestamp > ttlMs) {
                this.sessions.delete(sessionId);
                evictedCount++;
            }
        }
        if (evictedCount > 0) {
            logger_js_1.logger.info({ evictedCount }, "Cleaned up inactive gateway session contexts");
        }
        return evictedCount;
    }
    size() {
        return this.sessions.size;
    }
}
exports.SessionManager = SessionManager;
exports.sessionManager = new SessionManager();
