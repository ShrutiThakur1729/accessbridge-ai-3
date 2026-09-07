"""Thread-safe in-memory conversation state for Planner sessions.

The interface is deliberately small so this store can be replaced by Redis without
changing the FastAPI endpoint or planner contract.
"""
from dataclasses import dataclass, field
from enum import Enum
from threading import Lock
from typing import Any, Dict, List, Optional


import time


class PlannerExecutionState(str, Enum):
    IDLE = "IDLE"
    PROCESSING_DOM = "PROCESSING_DOM"
    AWAITING_USER_CONFIRMATION = "AWAITING_USER_CONFIRMATION"
    EXECUTING = "EXECUTING"
    ERROR = "ERROR"


@dataclass
class ConversationSession:
    session_language: str = "en-US"
    state: PlannerExecutionState = PlannerExecutionState.IDLE
    history: List[Dict[str, Any]] = field(default_factory=list)
    session_context: Optional[Any] = None
    last_active_at: float = field(default_factory=time.time)


class ConversationStateManager:
    """Persistent-per-process state and bounded history keyed by session_id."""
    MAX_HISTORY = 50
    DEFAULT_TTL = 300  # 5 minutes inactivity TTL

    def __init__(self) -> None:
        self._sessions: Dict[str, ConversationSession] = {}
        self._lock = Lock()

    def cleanup_stale_sessions(self, ttl_seconds: int = DEFAULT_TTL) -> int:
        now = time.time()
        with self._lock:
            stale_keys = [
                key for key, sess in self._sessions.items()
                if (now - sess.last_active_at) > ttl_seconds
            ]
            for key in stale_keys:
                del self._sessions[key]
            return len(stale_keys)

    def transition(
        self,
        session_id: str,
        state: PlannerExecutionState,
        session_language: Optional[str] = None,
        event: Optional[Dict[str, Any]] = None,
        session_context: Optional[Any] = None,
    ) -> ConversationSession:
        self.cleanup_stale_sessions()
        with self._lock:
            session = self._sessions.setdefault(session_id, ConversationSession())
            session.last_active_at = time.time()
            if session_language:
                session.session_language = session_language
            session.state = state
            if session_context is not None:
                session.session_context = session_context
            if event:
                session.history.append({"state": state.value, **event})
                del session.history[:-self.MAX_HISTORY]
            return session

    def get(self, session_id: str) -> Optional[ConversationSession]:
        self.cleanup_stale_sessions()
        with self._lock:
            session = self._sessions.get(session_id)
            if session:
                session.last_active_at = time.time()
            return session

    def delete(self, session_id: str) -> bool:
        with self._lock:
            return self._sessions.pop(session_id, None) is not None


conversation_state_manager = ConversationStateManager()

