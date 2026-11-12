"""
Streaming speech-to-text abstraction.

GoogleStreamingSTT below is a real implementation against
google-cloud-speech's streaming_recognize API. That API is a blocking
generator-in/generator-out gRPC call, not natively async, so this bridges
it onto asyncio with a background thread + two queues. This is a standard
pattern for wrapping blocking streaming SDKs, not a stub.

Still needs from you before production:
- GOOGLE_APPLICATION_CREDENTIALS pointing at a real service account key
  (or workload identity in your hosting environment).
- Swahili accuracy validation (doc risk #2/#12.2) -- "sw-KE" is passed
  through, but real-world accuracy needs a direct listening test, not an
  assumption.
"""
import asyncio
import queue
import threading
from abc import ABC, abstractmethod
from typing import AsyncIterator, Optional

from google.cloud import speech


class StreamingSTT(ABC):
    @abstractmethod
    async def start(self, language: str) -> None:
        """Open the vendor streaming session for this call."""

    @abstractmethod
    async def send_audio(self, pcm_chunk: bytes) -> None:
        """Push one frame of caller audio (16-bit PCM, 16kHz recommended)."""

    @abstractmethod
    async def results(self) -> AsyncIterator[dict]:
        """
        Yields {"text": str, "is_final": bool} as the vendor produces them.
        Only final results should be forwarded to the LLM / stored.
        """

    @abstractmethod
    async def close(self) -> None:
        ...


_LANGUAGE_CODES = {"en": "en-US", "sw": "sw-KE"}


class GoogleStreamingSTT(StreamingSTT):
    def __init__(self, api_key: str):
        # google-cloud-speech authenticates via GOOGLE_APPLICATION_CREDENTIALS,
        # not an API key in the request -- api_key kept for interface parity
        # with build_stt() and in case you switch to an API-key-based vendor.
        self._client = speech.SpeechClient()
        self._audio_queue: "queue.Queue[Optional[bytes]]" = queue.Queue()
        self._result_queue: "asyncio.Queue[dict]" = asyncio.Queue()
        self._thread: Optional[threading.Thread] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None

    async def start(self, language: str) -> None:
        self._loop = asyncio.get_event_loop()
        config = speech.RecognitionConfig(
            encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
            sample_rate_hertz=16000,
            language_code=_LANGUAGE_CODES.get(language, "en-US"),
        )
        streaming_config = speech.StreamingRecognitionConfig(
            config=config, interim_results=True
        )

        def request_generator():
            yield speech.StreamingRecognizeRequest(streaming_config=streaming_config)
            while True:
                chunk = self._audio_queue.get()
                if chunk is None:
                    return
                yield speech.StreamingRecognizeRequest(audio_content=chunk)

        def run():
            try:
                responses = self._client.streaming_recognize(requests=request_generator())
                for response in responses:
                    for result in response.results:
                        if not result.alternatives:
                            continue
                        payload = {
                            "text": result.alternatives[0].transcript,
                            "is_final": result.is_final,
                        }
                        asyncio.run_coroutine_threadsafe(
                            self._result_queue.put(payload), self._loop
                        )
            except Exception as exc:  # surfaces vendor errors instead of hanging
                asyncio.run_coroutine_threadsafe(
                    self._result_queue.put({"text": "", "is_final": False, "error": str(exc)}),
                    self._loop,
                )

        self._thread = threading.Thread(target=run, daemon=True)
        self._thread.start()

    async def send_audio(self, pcm_chunk: bytes) -> None:
        self._audio_queue.put(pcm_chunk)

    async def results(self) -> AsyncIterator[dict]:
        while True:
            result = await self._result_queue.get()
            yield result

    async def close(self) -> None:
        self._audio_queue.put(None)


def build_stt(provider: str, api_key: str) -> StreamingSTT:
    if provider == "google":
        return GoogleStreamingSTT(api_key)
    raise ValueError(f"Unsupported STT provider: {provider}")
