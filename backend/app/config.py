import os
from dotenv import load_dotenv

load_dotenv()

class Settings:
    # LLM provider: claude, groq, or gemini
    LLM_PROVIDER: str = os.getenv("LLM_PROVIDER", "claude").lower()
    ANTHROPIC_API_KEY: str = os.getenv("ANTHROPIC_API_KEY", "")
    CLAUDE_MODEL: str = os.getenv("CLAUDE_MODEL", "claude-sonnet-4-6")
    GROQ_API_KEY: str = os.getenv("GROQ_API_KEY", "")
    GROQ_MODEL: str = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")
    GEMINI_API_KEY: str = os.getenv("GEMINI_API_KEY", "")
    GEMINI_MODEL: str = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")

    # STT vendor (plug in Google Speech-to-Text, Deepgram, etc.)
    STT_PROVIDER: str = os.getenv("STT_PROVIDER", "google")
    STT_API_KEY: str = os.getenv("STT_API_KEY", "")

    # TTS vendor (must support Kenyan-accent + American-accent English, plus Swahili)
    TTS_PROVIDER: str = os.getenv("TTS_PROVIDER", "elevenlabs")
    TTS_API_KEY: str = os.getenv("TTS_API_KEY", "")
    TTS_VOICE_KENYAN: str = os.getenv("TTS_VOICE_KENYAN", "")
    TTS_VOICE_AMERICAN: str = os.getenv("TTS_VOICE_AMERICAN", "")
    TTS_VOICE_SWAHILI: str = os.getenv("TTS_VOICE_SWAHILI", "")
    TTS_FALLBACK_PROVIDER: str = os.getenv("TTS_FALLBACK_PROVIDER", "elevenlabs").lower()
    ELEVENLABS_API_KEY: str = os.getenv("ELEVENLABS_API_KEY", "")
    ELEVENLABS_VOICE_ID: str = os.getenv("ELEVENLABS_VOICE_ID", "")
    ELEVENLABS_VOICE_KENYAN: str = os.getenv("ELEVENLABS_VOICE_KENYAN", "")
    ELEVENLABS_VOICE_AMERICAN: str = os.getenv("ELEVENLABS_VOICE_AMERICAN", "")
    ELEVENLABS_VOICE_SWAHILI: str = os.getenv("ELEVENLABS_VOICE_SWAHILI", "")
    TRANSLATION_ENABLED: bool = os.getenv("TRANSLATION_ENABLED", "false").lower() == "true"

    # Server
    HOST: str = os.getenv("HOST", "0.0.0.0")
    PORT: int = int(os.getenv("PORT", "8000"))
    SESSION_AUTH_TOKEN: str = os.getenv("SESSION_AUTH_TOKEN", "")
    TWILIO_ACCOUNT_SID: str = os.getenv("TWILIO_ACCOUNT_SID", "")
    TWILIO_AUTH_TOKEN: str = os.getenv("TWILIO_AUTH_TOKEN", "")
    TWILIO_PHONE_NUMBER: str = os.getenv("TWILIO_PHONE_NUMBER", "")
    TWILIO_OWNER_PHONE_NUMBER: str = os.getenv("TWILIO_OWNER_PHONE_NUMBER", "")
    PUBLIC_BASE_URL: str = os.getenv("PUBLIC_BASE_URL", "").rstrip("/")
    TWILIO_ASSISTANT_NAME: str = os.getenv("TWILIO_ASSISTANT_NAME", "ARIA")
    TWILIO_USER_NAME: str = os.getenv("TWILIO_USER_NAME", "")
    TWILIO_LANGUAGE: str = os.getenv("TWILIO_LANGUAGE", "en")
    TWILIO_ACCENT: str = os.getenv("TWILIO_ACCENT", "kenyan")
    TWILIO_RECORDING_DISCLOSURE_ENABLED: bool = os.getenv(
        "TWILIO_RECORDING_DISCLOSURE_ENABLED", "true"
    ).lower() == "true"

    def validate(self) -> list[str]:
        errors = []
        if self.LLM_PROVIDER not in {"claude", "groq", "gemini"}:
            errors.append("LLM_PROVIDER must be claude, groq, or gemini")
        elif self.LLM_PROVIDER == "claude" and not self.ANTHROPIC_API_KEY:
            errors.append("ANTHROPIC_API_KEY is not configured")
        elif self.LLM_PROVIDER == "groq" and not self.GROQ_API_KEY:
            errors.append("GROQ_API_KEY is not configured")
        elif self.LLM_PROVIDER == "gemini" and not self.GEMINI_API_KEY:
            errors.append("GEMINI_API_KEY is not configured")
        if self.STT_PROVIDER == "google" and not os.getenv("GOOGLE_APPLICATION_CREDENTIALS"):
            errors.append("GOOGLE_APPLICATION_CREDENTIALS is not configured")
        if not self.STT_PROVIDER:
            errors.append("STT_PROVIDER is not configured")
        if not self.TTS_PROVIDER:
            errors.append("TTS_PROVIDER is not configured")
        elevenlabs_key = self.TTS_API_KEY or self.ELEVENLABS_API_KEY
        elevenlabs_voice = self.TTS_VOICE_KENYAN or self.TTS_VOICE_AMERICAN or self.TTS_VOICE_SWAHILI or self.ELEVENLABS_VOICE_ID
        if self.TTS_PROVIDER == "elevenlabs":
            if not elevenlabs_key:
                errors.append("an ElevenLabs API key is not configured")
            if not elevenlabs_voice:
                errors.append("an ElevenLabs voice ID is not configured")
        if self.TTS_FALLBACK_PROVIDER == "elevenlabs" and self.TTS_PROVIDER != "elevenlabs":
            if not self.ELEVENLABS_API_KEY:
                errors.append("ELEVENLABS_API_KEY is not configured for TTS fallback")
            if not (self.ELEVENLABS_VOICE_ID or any((
                self.ELEVENLABS_VOICE_KENYAN,
                self.ELEVENLABS_VOICE_AMERICAN,
                self.ELEVENLABS_VOICE_SWAHILI,
            ))):
                errors.append("an ElevenLabs fallback voice ID is not configured")
        if self.HOST not in {"127.0.0.1", "localhost", "::1"} and not self.SESSION_AUTH_TOKEN:
            errors.append("SESSION_AUTH_TOKEN is required when the backend is publicly bound")
        return errors

settings = Settings()
