"""Thread-safe in-memory conversation state for Planner sessions.

The interface is deliberately small so this store can be replaced by Redis without
changing the FastAPI endpoint or planner contract.
"""
from dataclasses import dataclass, field
from enum import Enum
from threading import Lock
from typing import Any, Dict, List, Optional


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


class ConversationStateManager:
    """Persistent-per-process state and bounded history keyed by session_id."""
    MAX_HISTORY = 50

    def __init__(self) -> None:
        self._sessions: Dict[str, ConversationSession] = {}
        self._lock = Lock()

    def transition(
        self,
        session_id: str,
        state: PlannerExecutionState,
        session_language: Optional[str] = None,
        event: Optional[Dict[str, Any]] = None,
    ) -> ConversationSession:
        with self._lock:
            session = self._sessions.setdefault(session_id, ConversationSession())
            if session_language:
                session.session_language = session_language
            session.state = state
            if event:
                session.history.append({"state": state.value, **event})
                del session.history[:-self.MAX_HISTORY]
            return session

    def get(self, session_id: str) -> Optional[ConversationSession]:
        with self._lock:
            return self._sessions.get(session_id)


conversation_state_manager = ConversationStateManager()
