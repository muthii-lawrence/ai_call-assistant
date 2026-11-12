from enum import Enum
from typing import Optional, Literal
from pydantic import BaseModel


class Language(str, Enum):
    en = "en"
    sw = "sw"


class Accent(str, Enum):
    kenyan = "kenyan"
    american = "american"


class SessionInit(BaseModel):
    """First message the app sends when opening the WebSocket for a call."""
    type: Literal["session_init"] = "session_init"
    protocol_version: int = 1
    session_id: str
    assistant_name: str
    user_name: str
    language: Language = Language.en
    accent: Accent = Accent.kenyan
    is_known: bool
    contact_name: Optional[str] = None
    phone_number: str
    recording_disclosure_enabled: bool = True
    translation_enabled: bool = False
    caller_language: Optional[Language] = None


class TranscriptEvent(BaseModel):
    """Server -> app: a finalized transcript line, for live display + storage."""
    type: Literal["transcript"] = "transcript"
    speaker: Literal["ai", "caller"]
    text: str
    timestamp_offset_seconds: float


class ControlMessage(BaseModel):
    """App -> server: out-of-band control events (handover, hangup, etc.)."""
    type: Literal["control"] = "control"
    action: Literal["handover_to_human", "return_to_ai", "call_ended", "take_message"]


class SummaryEvent(BaseModel):
    type: Literal["summary"] = "summary"
    summary: str


class ErrorEvent(BaseModel):
    type: Literal["error"] = "error"
    message: str
