"""
Streaming text-to-speech abstraction.

ElevenLabsStreamingTTS below makes a real streaming HTTP call to
ElevenLabs -- this is a working implementation, not a stub. It still needs
your real API key and voice IDs (ELEVENLABS dashboard) before it will
actually speak.

Per the doc's open risk #2: validate that your chosen vendor actually
offers a genuine Kenyan-accent English voice (not just generic
"African-adjacent" or South African) and Swahili -- listen to samples
yourself before picking TTS_VOICE_KENYAN / TTS_VOICE_SWAHILI.
"""
from abc import ABC, abstractmethod
from typing import AsyncIterator

import httpx


class StreamingTTS(ABC):
    @abstractmethod
    async def synthesize(self, text: str, voice_id: str) -> AsyncIterator[bytes]:
        """Yields audio frames (e.g. 16-bit PCM) for the given text."""


class ElevenLabsStreamingTTS(StreamingTTS):
    _BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech"

    def __init__(self, api_key: str):
        self.api_key = api_key

    async def synthesize(self, text: str, voice_id: str) -> AsyncIterator[bytes]:
        if not voice_id:
            raise ValueError(
                "No voice_id configured -- set TTS_VOICE_KENYAN / "
                "TTS_VOICE_AMERICAN / TTS_VOICE_SWAHILI in your .env"
            )
        url = f"{self._BASE_URL}/{voice_id}/stream"
        headers = {
            "xi-api-key": self.api_key,
            "Content-Type": "application/json",
            "Accept": "audio/pcm",
        }
        payload = {
            "text": text,
            "model_id": "eleven_turbo_v2_5",  # low-latency model; revisit per quality needs
            "output_format": "pcm_16000",
            "voice_settings": {"stability": 0.5, "similarity_boost": 0.75},
        }
        async with httpx.AsyncClient(timeout=30.0) as client:
            async with client.stream("POST", url, headers=headers, json=payload) as response:
                response.raise_for_status()
                async for chunk in response.aiter_bytes():
                    if chunk:
                        yield chunk


class FallbackTTS(StreamingTTS):
    def __init__(self, primary: StreamingTTS, fallback: StreamingTTS):
        self.primary = primary
        self.fallback = fallback

    async def synthesize(self, text: str, voice_id: str) -> AsyncIterator[bytes]:
        try:
            emitted = False
            async for chunk in self.primary.synthesize(text, voice_id):
                emitted = True
                yield chunk
            if emitted:
                return
        except Exception:
            pass
        async for chunk in self.fallback.synthesize(text, voice_id):
            yield chunk


def build_tts(provider: str, api_key: str, fallback_provider: str = "", fallback_api_key: str = "") -> StreamingTTS:
    if provider == "elevenlabs":
        primary = ElevenLabsStreamingTTS(api_key or fallback_api_key)
    else:
        raise ValueError(f"Unsupported TTS provider: {provider}")
    if fallback_provider == "elevenlabs" and fallback_api_key:
        return FallbackTTS(primary, ElevenLabsStreamingTTS(fallback_api_key))
    return primary


def voice_for(accent: str, language: str, cfg) -> str:
    """Pick the configured voice id based on accent/language settings."""
    if language == "sw":
        return cfg.TTS_VOICE_SWAHILI or cfg.ELEVENLABS_VOICE_SWAHILI or cfg.ELEVENLABS_VOICE_ID
    return (
        (cfg.TTS_VOICE_KENYAN if accent == "kenyan" else cfg.TTS_VOICE_AMERICAN)
        or (cfg.ELEVENLABS_VOICE_KENYAN if accent == "kenyan" else cfg.ELEVENLABS_VOICE_AMERICAN)
        or cfg.ELEVENLABS_VOICE_ID
    )
