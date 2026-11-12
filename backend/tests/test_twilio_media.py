import asyncio
import base64
import struct
import unittest
import xml.etree.ElementTree as ET

from app.twilio_media import (
    TwilioMediaSocket,
    build_stream_twiml,
    linear_to_mulaw,
    mulaw_8k_to_pcm16_16k,
    mulaw_to_linear,
    pcm16_16k_to_mulaw_8k,
)


class FakeWebSocket:
    def __init__(self, messages):
        self.messages = iter(messages)
        self.sent = []
        self.closed = False

    async def receive_json(self):
        return next(self.messages)

    async def send_json(self, message):
        self.sent.append(message)

    async def close(self, **kwargs):
        self.closed = True


class TwilioAudioTests(unittest.TestCase):
    def test_mulaw_conversion_is_bounded_and_resamples(self):
        samples = [-30000, -12000, -1000, 0, 1000, 12000, 30000]
        encoded = bytes(linear_to_mulaw(sample) for sample in samples)
        decoded = [mulaw_to_linear(sample) for sample in encoded]

        self.assertEqual(len(decoded), len(samples))
        for original, restored in zip(samples, decoded):
            self.assertLess(abs(original - restored), 3000)

        pcm16k = mulaw_8k_to_pcm16_16k(encoded)
        self.assertEqual(len(pcm16k), len(samples) * 4)
        self.assertEqual(len(pcm16_16k_to_mulaw_8k(pcm16k)), len(samples))

    def test_output_rejects_partial_pcm_sample(self):
        with self.assertRaises(ValueError):
            pcm16_16k_to_mulaw_8k(b"\x00")

    def test_streaming_tts_chunk_boundary_preserves_pcm16_alignment(self):
        websocket = FakeWebSocket([])
        socket = TwilioMediaSocket(websocket)
        socket.stream_sid = "MZ-stream"

        async def exercise():
            await socket.send_bytes(b"\x00")
            self.assertEqual(websocket.sent, [])
            await socket.send_bytes(b"\x00\x00")

        import asyncio
        asyncio.run(exercise())
        self.assertEqual(len(websocket.sent), 1)
        self.assertEqual(len(base64.b64decode(websocket.sent[0]["media"]["payload"])), 1)

    def test_handover_clears_twilio_audio_and_blocks_later_speech(self):
        websocket = FakeWebSocket([])
        socket = TwilioMediaSocket(websocket)
        socket.stream_sid = "MZ-stream"

        async def exercise():
            await socket.send_json({
                "type": "control_ack",
                "action": "handover_to_human",
            })
            await socket.send_bytes(b"\x00\x00\x00\x00")

        import asyncio
        asyncio.run(exercise())
        self.assertEqual(websocket.sent, [{"event": "clear", "streamSid": "MZ-stream"}])

    def test_stream_messages_become_session_init_and_pcm(self):
        media = base64.b64encode(bytes([0xFF, 0x7F])).decode("ascii")
        websocket = FakeWebSocket([
            {"event": "connected"},
            {
                "event": "start",
                "streamSid": "MZ-stream",
                "start": {
                    "callSid": "CA-call",
                    "streamSid": "MZ-stream",
                    "mediaFormat": {
                        "encoding": "audio/x-mulaw",
                        "sampleRate": 8000,
                        "channels": 1,
                    },
                    "customParameters": {
                        "phone_number": "+15551234567",
                        "assistant_name": "ARIA",
                        "recording_disclosure_enabled": "true",
                    },
                },
            },
            {
                "event": "media",
                "media": {"track": "inbound", "payload": media},
            },
        ])
        started = []
        socket = TwilioMediaSocket(websocket, lambda call_sid, stream: started.append(call_sid))

        async def exercise():
            init = await socket.receive_json()
            frame = await socket.receive()
            return init, frame

        init, frame = asyncio.run(exercise())
        self.assertEqual(init["session_id"], "CA-call")
        self.assertEqual(init["phone_number"], "+15551234567")
        self.assertEqual(started, ["CA-call"])
        self.assertEqual(len(frame["bytes"]), 8)

    def test_stream_twiml_uses_secure_bidirectional_endpoint_and_parameters(self):
        xml = build_stream_twiml(
            public_base_url="https://calls.example.test/",
            phone_number="+15551234567",
            assistant_name="ARIA & Co",
            user_name="Alex",
            language="en",
            accent="kenyan",
            disclosure_enabled=True,
        )
        root = ET.fromstring(xml)
        connect = root.find("Connect")
        stream = connect.find("Stream")
        self.assertEqual(stream.attrib["url"], "wss://calls.example.test/twilio/media/")
        self.assertEqual(connect.attrib["action"], "https://calls.example.test/twilio/stream-ended")
        params = {item.attrib["name"]: item.attrib["value"] for item in stream.findall("Parameter")}
        self.assertEqual(params["assistant_name"], "ARIA & Co")
        self.assertEqual(params["recording_disclosure_enabled"], "true")


if __name__ == "__main__":
    unittest.main()
