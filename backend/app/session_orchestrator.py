import asyncio
import time
from fastapi import WebSocket, WebSocketDisconnect

from .models import SessionInit, ControlMessage
from . import llm
from .stt import build_stt
from .tts import build_tts, voice_for
from .config import settings


class CallSession:
    """
    Owns the full lifecycle for ONE active call's WebSocket connection:
    caller audio in -> STT -> LLM -> TTS -> audio out, plus handover
    control and end-of-call summary generation.

    Mirrors the state machine in section 4 of the architecture doc, but
    only the AI_MODE <-> HANDOVER transitions -- ringing/answer logic
    lives on-device in the Telecom layer, not here.
    """

    def __init__(self, ws: WebSocket):
        self.ws = ws
        self.stt = None
        self.tts = build_tts(
            settings.TTS_PROVIDER,
            settings.TTS_API_KEY,
            settings.TTS_FALLBACK_PROVIDER,
            settings.ELEVENLABS_API_KEY,
        )
        self.history: list[dict] = []
        self.transcript_lines: list[str] = []
        self.ai_active = True  # False while user has taken over the call
        self.start_time = time.time()
        self.system_prompt = ""
        self.voice_id = ""
        self.language = "en"
        self.translation_enabled = False
        self.caller_language = None
        self._ended = False
        self._stt_task = None

    async def run(self):
        try:
            init_raw = await self.ws.receive_json()
            init = SessionInit(**init_raw)
            if init.protocol_version != 1:
                raise ValueError(f"unsupported protocol_version: {init.protocol_version}")
        except Exception as exc:
            # Malformed/incomplete session_init from the app -- tell it why
            # and close cleanly instead of a raw ASGI 500.
            await self._try_send_error(f"invalid_session_init: {exc}")
            await self.ws.close(code=1003)
            return

        self.system_prompt = llm.build_system_prompt(init)
        self.voice_id = voice_for(init.accent.value, init.language.value, settings)
        self.language = init.language.value
        self.translation_enabled = init.translation_enabled or settings.TRANSLATION_ENABLED
        self.caller_language = init.caller_language.value if init.caller_language else None

        try:
            self.stt = build_stt(settings.STT_PROVIDER, settings.STT_API_KEY)
            stt_language = (
                init.caller_language.value
                if self.translation_enabled and init.caller_language
                else init.language.value
            )
            await self.stt.start(stt_language)
            self._stt_task = asyncio.create_task(self._consume_stt_results())

            if init.recording_disclosure_enabled:
                disclosure = (
                    f"This call may be assisted by AI. {init.assistant_name} "
                    f"speaking on behalf of {init.user_name}."
                )
                await self._speak(disclosure, is_disclosure=True)

            greeting = await llm.next_ai_turn(
                self.system_prompt, self.history, "(call just started, greet the caller)"
            )
            await self._speak(greeting)

            while True:
                msg = await self.ws.receive()
                if "bytes" in msg and msg["bytes"] is not None:
                    if self.ai_active:
                        await self._handle_audio_frame(msg["bytes"])
                elif "text" in msg and msg["text"] is not None:
                    should_stop = await self._handle_control(msg["text"])
                    if should_stop:
                        break
        except WebSocketDisconnect:
            pass
        except Exception as exc:
            # Never let a vendor/LLM error take the whole call down silently --
            # tell the app so it can fall back (e.g. let the human take over)
            # instead of the caller hearing dead air.
            await self._try_send_error(str(exc))
        finally:
            await self._end_call()
            if self._stt_task is not None:
                self._stt_task.cancel()

    async def _try_send_error(self, message: str):
        try:
            await self.ws.send_json({"type": "error", "message": message})
        except Exception:
            pass  # socket already gone; nothing more we can do

    async def _handle_audio_frame(self, frame: bytes):
        await self.stt.send_audio(frame)
    async def _consume_stt_results(self):
        while not self._ended:
            current_stt = self.stt
            async for result in current_stt.results():
                if result.get("error"):
                    await self._try_send_error(f"stt_failed: {result['error']}")
                    continue
                if not self.ai_active or not result.get("is_final"):
                    continue
                caller_text = result["text"].strip()
                if not caller_text:
                    continue
                self._record_transcript("caller", caller_text)
                await self._send_transcript("caller", caller_text)
                prompt_text = caller_text
                if self.translation_enabled and self.caller_language:
                    try:
                        prompt_text = await llm.translate_text(
                            caller_text, self.caller_language, self.language
                        )
                        await self._send_transcript("translation", prompt_text)
                    except Exception as exc:
                        await self._try_send_error(f"translation_failed: {exc}")
                try:
                    reply = await llm.next_ai_turn(self.system_prompt, self.history, prompt_text)
                except Exception as exc:
                    await self._try_send_error(f"ai_turn_failed: {exc}")
                    continue
                self.history.append({"role": "user", "content": prompt_text})
                self.history.append({"role": "assistant", "content": reply})
                await self._speak(reply)
            if self.stt is current_stt:
                return

    async def _handle_control(self, raw_text: str) -> bool:
        import json
        try:
            data = json.loads(raw_text)
            control = ControlMessage(**data)
        except (ValueError, TypeError) as exc:
            await self._try_send_error(f"invalid_control: {exc}")
            return False
        if control.action == "handover_to_human":
            self.ai_active = False
            await self.stt.close()
            await self.ws.send_json({"type": "control_ack", "action": control.action})
        elif control.action == "return_to_ai":
            self.ai_active = True
            try:
                self.stt = build_stt(settings.STT_PROVIDER, settings.STT_API_KEY)
                await self.stt.start(self.language)
            except Exception as exc:
                await self._try_send_error(f"stt_restart_failed: {exc}")
            await self.ws.send_json({"type": "control_ack", "action": control.action})
        elif control.action == "call_ended":
            await self._end_call()
            return True
        return False

    def _record_transcript(self, speaker: str, text: str):
        # Human-mode audio is never sent to this service in the first place
        # (the app only streams frames while ai_active), so this list is
        # naturally AI-mode-only, satisfying the doc's transcript rule.
        self.transcript_lines.append(f"{speaker}: {text}")

    async def _send_transcript(self, speaker: str, text: str):
        await self.ws.send_json({
            "type": "transcript",
            "speaker": speaker,
            "text": text,
            "timestamp_offset_seconds": time.time() - self.start_time,
        })

    async def _speak(self, text: str, is_disclosure: bool = False):
        if not is_disclosure and not self.ai_active:
            return
        if not is_disclosure:
            self._record_transcript("ai", text)
            await self._send_transcript("ai", text)
        async for audio_chunk in self.tts.synthesize(text, self.voice_id):
            if not self.ai_active and not is_disclosure:
                break
            await self.ws.send_bytes(audio_chunk)

    async def _end_call(self):
        if self._ended:
            return
        self._ended = True
        if self.stt is not None:
            await self.stt.close()
        if self.transcript_lines:
            try:
                summary = await llm.generate_summary(self.transcript_lines)
                await self.ws.send_json({"type": "summary", "summary": summary})
            except Exception:
                pass  # best-effort: socket may be closed, or summary call may fail
