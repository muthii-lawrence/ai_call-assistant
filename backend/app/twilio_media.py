import asyncio
import base64
import json
import struct
import xml.etree.ElementTree as ET
from collections.abc import Callable
from typing import Any

from starlette.websockets import WebSocketDisconnect


SAMPLE_RATE_TWILIO = 8000
SAMPLE_RATE_SESSION = 16000


def mulaw_to_linear(sample: int) -> int:
    value = (~sample) & 0xFF
    magnitude = ((value & 0x0F) << 3) + 0x84
    magnitude <<= (value & 0x70) >> 4
    return 0x84 - magnitude if value & 0x80 else magnitude - 0x84


def linear_to_mulaw(sample: int) -> int:
    sample = max(-32635, min(32635, sample))
    sign = 0x80 if sample < 0 else 0
    magnitude = abs(sample) + 0x84
    exponent = 7
    for mask in (0x4000, 0x2000, 0x1000, 0x0800, 0x0400, 0x0200, 0x0100):
        if magnitude & mask:
            break
        exponent -= 1
    mantissa = (magnitude >> (exponent + 3)) & 0x0F
    return (~(sign | (exponent << 4) | mantissa)) & 0xFF


def mulaw_8k_to_pcm16_16k(payload: bytes) -> bytes:
    samples = [mulaw_to_linear(sample) for sample in payload]
    output = bytearray()
    for index, sample in enumerate(samples):
        following = samples[index + 1] if index + 1 < len(samples) else sample
        midpoint = (sample + following) // 2
        output.extend(struct.pack("<hh", sample, midpoint))
    return bytes(output)


def pcm16_16k_to_mulaw_8k(payload: bytes) -> bytes:
    if len(payload) % 2:
        raise ValueError("PCM16 audio must contain whole 16-bit samples")
    samples = [sample[0] for sample in struct.iter_unpack("<h", payload)]
    output = bytearray()
    for index in range(0, len(samples), 2):
        pair = samples[index:index + 2]
        averaged = sum(pair) // len(pair)
        output.append(linear_to_mulaw(averaged))
    return bytes(output)


def build_stream_twiml(
    *,
    public_base_url: str,
    phone_number: str,
    assistant_name: str,
    user_name: str,
    language: str,
    accent: str,
    disclosure_enabled: bool,
) -> str:
    base_url = public_base_url.rstrip("/")
    websocket_url = "wss://" + base_url.removeprefix("https://").removeprefix("http://")
    response = ET.Element("Response")
    connect = ET.SubElement(
        response,
        "Connect",
        {"action": f"{base_url}/twilio/stream-ended", "method": "POST"},
    )
    stream = ET.SubElement(connect, "Stream", {"url": f"{websocket_url}/twilio/media/"})
    parameters = {
        "phone_number": phone_number,
        "assistant_name": assistant_name,
        "user_name": user_name,
        "language": language,
        "accent": accent,
        "recording_disclosure_enabled": str(disclosure_enabled).lower(),
    }
    for name, value in parameters.items():
        ET.SubElement(stream, "Parameter", {"name": name, "value": value})
    return ET.tostring(response, encoding="unicode", short_empty_elements=True)


def build_handover_twiml(owner_phone_number: str, caller_id: str = "") -> str:
    response = ET.Element("Response")
    if not owner_phone_number:
        ET.SubElement(response, "Say").text = (
            "The call assistant has ended this call. Goodbye."
        )
        ET.SubElement(response, "Hangup")
    else:
        attributes = {"timeout": "25", "answerOnBridge": "true"}
        if caller_id:
            attributes["callerId"] = caller_id
        dial = ET.SubElement(response, "Dial", attributes)
        ET.SubElement(dial, "Number").text = owner_phone_number
    return ET.tostring(response, encoding="unicode", short_empty_elements=True)


class TwilioMediaSocket:
    """Adapt a Twilio bidirectional Media Stream to the CallSession socket API."""

    def __init__(self, websocket: Any, on_start: Callable[[str, "TwilioMediaSocket"], None] | None = None):
        self.websocket = websocket
        self.on_start = on_start
        self.call_sid: str | None = None
        self.stream_sid: str | None = None
        self.phone_number = "unknown"
        self._send_lock = asyncio.Lock()
        self._controls: asyncio.Queue[dict[str, str]] = asyncio.Queue()
        self._control_event = asyncio.Event()
        self._pcm_remainder = b""
        self._handover_acknowledged = False

    async def receive_json(self) -> dict[str, Any]:
        connected = await self.websocket.receive_json()
        if connected.get("event") != "connected":
            raise ValueError("Twilio Media Streams must begin with a connected event")
        started = await self.websocket.receive_json()
        if started.get("event") != "start":
            raise ValueError("Twilio Media Streams must follow connected with start")

        start = started.get("start") or {}
        media_format = start.get("mediaFormat") or {}
        if (
            media_format.get("encoding") != "audio/x-mulaw"
            or media_format.get("sampleRate") != SAMPLE_RATE_TWILIO
            or media_format.get("channels") != 1
        ):
            raise ValueError("Unsupported Twilio media format")

        self.stream_sid = start.get("streamSid") or started.get("streamSid")
        self.call_sid = start.get("callSid")
        if not self.stream_sid or not self.call_sid:
            raise ValueError("Twilio start event is missing call or stream SID")
        params = start.get("customParameters") or {}
        self.phone_number = params.get("phone_number", "unknown")
        if self.on_start:
            self.on_start(self.call_sid, self)
        language = params.get("language", "en")
        accent = params.get("accent", "kenyan")
        return {
            "type": "session_init",
            "protocol_version": 1,
            "session_id": self.call_sid,
            "assistant_name": params.get("assistant_name", "ARIA"),
            "user_name": params.get("user_name", ""),
            "language": language if language in {"en", "sw"} else "en",
            "accent": accent if accent in {"kenyan", "american"} else "kenyan",
            "is_known": False,
            "phone_number": self.phone_number,
            "recording_disclosure_enabled": params.get(
                "recording_disclosure_enabled", "true"
            ).lower() == "true",
        }

    async def receive(self) -> dict[str, Any]:
        while True:
            if self._handover_acknowledged:
                raise WebSocketDisconnect(code=1000)
            if not self._controls.empty():
                return {"text": json.dumps(await self._controls.get())}

            receive_task = asyncio.create_task(self.websocket.receive_json())
            control_task = asyncio.create_task(self._control_event.wait())
            done, pending = await asyncio.wait(
                {receive_task, control_task}, return_when=asyncio.FIRST_COMPLETED
            )
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)

            if control_task in done and control_task.result():
                self._control_event.clear()
                return {"text": json.dumps(await self._controls.get())}

            message = receive_task.result()
            event = message.get("event")
            if event == "stop":
                raise WebSocketDisconnect(code=1000)
            if event == "media" and (message.get("media") or {}).get("track") == "inbound":
                encoded = message["media"].get("payload", "")
                try:
                    mulaw_audio = base64.b64decode(encoded, validate=True)
                except (ValueError, TypeError) as exc:
                    raise ValueError("Invalid base64 audio in Twilio media event") from exc
                return {"bytes": mulaw_8k_to_pcm16_16k(mulaw_audio)}

    async def send_json(self, data: dict[str, Any]) -> None:
        if data.get("type") == "control_ack" and data.get("action") == "handover_to_human":
            self._handover_acknowledged = True
            if self.stream_sid:
                async with self._send_lock:
                    await self.websocket.send_json({
                        "event": "clear",
                        "streamSid": self.stream_sid,
                    })

    async def send_bytes(self, pcm_audio: bytes) -> None:
        if self._handover_acknowledged:
            return
        if not self.stream_sid:
            raise RuntimeError("Twilio stream is not initialized")
        aligned_audio = self._pcm_remainder + pcm_audio
        if len(aligned_audio) % 2:
            self._pcm_remainder = aligned_audio[-1:]
            aligned_audio = aligned_audio[:-1]
        else:
            self._pcm_remainder = b""
        if not aligned_audio:
            return
        mulaw_audio = pcm16_16k_to_mulaw_8k(aligned_audio)
        payload = base64.b64encode(mulaw_audio).decode("ascii")
        async with self._send_lock:
            await self.websocket.send_json({
                "event": "media",
                "streamSid": self.stream_sid,
                "media": {"payload": payload},
            })

    async def request_handover(self) -> None:
        await self._controls.put({"type": "control", "action": "handover_to_human"})
        self._control_event.set()

    async def close(self, code: int = 1000, reason: str | None = None) -> None:
        await self.websocket.close(code=code, reason=reason)
