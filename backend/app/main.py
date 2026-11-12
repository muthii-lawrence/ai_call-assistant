import logging
from urllib.parse import parse_qs

from fastapi import FastAPI, HTTPException, Request, WebSocket
from fastapi.responses import Response
from .session_orchestrator import CallSession
from .config import settings
from .twilio_media import TwilioMediaSocket, build_handover_twiml, build_stream_twiml

app = FastAPI(title="AI Call Assistant Backend")
logger = logging.getLogger(__name__)
active_twilio_streams: dict[str, TwilioMediaSocket] = {}
pending_handover_calls: set[str] = set()


def _is_e164(number: str) -> bool:
    return bool(number.startswith("+") and number[1:].isdigit() and 8 <= len(number) <= 16)


def _twilio_ready() -> bool:
    return bool(
        settings.TWILIO_AUTH_TOKEN
        and settings.PUBLIC_BASE_URL.startswith("https://")
        and _is_e164(settings.TWILIO_PHONE_NUMBER)
    )


def _handover_ready() -> bool:
    return bool(
        _twilio_ready()
        and settings.SESSION_AUTH_TOKEN
        and _is_e164(settings.TWILIO_OWNER_PHONE_NUMBER)
    )


def _validate_twilio_signature(url: str, params: dict[str, str], signature: str) -> bool:
    if not settings.TWILIO_AUTH_TOKEN or not signature:
        return False
    from twilio.request_validator import RequestValidator

    return RequestValidator(settings.TWILIO_AUTH_TOKEN).validate(url, params, signature)


def _internal_request_authorized(request: Request) -> bool:
    if not settings.SESSION_AUTH_TOKEN:
        return False
    return request.headers.get("authorization", "") == (
        f"Bearer {settings.SESSION_AUTH_TOKEN}"
    )


async def _form_params(request: Request) -> dict[str, str]:
    body = (await request.body()).decode("utf-8")
    return {key: values[-1] for key, values in parse_qs(body, keep_blank_values=True).items()}


@app.post("/twilio/incoming")
async def twilio_incoming(request: Request):
    if not _twilio_ready():
        raise HTTPException(status_code=503, detail="Twilio webhook is not configured")
    params = await _form_params(request)
    public_url = f"{settings.PUBLIC_BASE_URL}/twilio/incoming"
    if not _validate_twilio_signature(
        public_url,
        params,
        request.headers.get("x-twilio-signature", ""),
    ):
        raise HTTPException(status_code=403, detail="Invalid Twilio signature")
    if settings.TWILIO_PHONE_NUMBER and params.get("To") != settings.TWILIO_PHONE_NUMBER:
        raise HTTPException(status_code=403, detail="Webhook is not for the configured Twilio number")
    if not params.get("From") or not params.get("CallSid"):
        raise HTTPException(status_code=400, detail="Missing caller or call ID")

    twiml = build_stream_twiml(
        public_base_url=settings.PUBLIC_BASE_URL,
        phone_number=params["From"],
        assistant_name=settings.TWILIO_ASSISTANT_NAME,
        user_name=settings.TWILIO_USER_NAME,
        language=settings.TWILIO_LANGUAGE,
        accent=settings.TWILIO_ACCENT,
        disclosure_enabled=settings.TWILIO_RECORDING_DISCLOSURE_ENABLED,
    )
    return Response(content=twiml, media_type="application/xml")


@app.post("/twilio/stream-ended")
async def twilio_stream_ended(request: Request):
    if not _twilio_ready():
        raise HTTPException(status_code=503, detail="Twilio webhook is not configured")
    params = await _form_params(request)
    public_url = f"{settings.PUBLIC_BASE_URL}/twilio/stream-ended"
    if not _validate_twilio_signature(
        public_url,
        params,
        request.headers.get("x-twilio-signature", ""),
    ):
        raise HTTPException(status_code=403, detail="Invalid Twilio signature")
    call_sid = params.get("CallSid", "")
    should_handover = call_sid in pending_handover_calls and _handover_ready()
    pending_handover_calls.discard(call_sid)
    twiml = build_handover_twiml(
        settings.TWILIO_OWNER_PHONE_NUMBER if should_handover else "",
        settings.TWILIO_PHONE_NUMBER if should_handover else "",
    )
    return Response(content=twiml, media_type="application/xml")


@app.get("/twilio/active-calls")
async def twilio_active_calls(request: Request):
    if not _internal_request_authorized(request):
        raise HTTPException(status_code=401, detail="Unauthorized")
    return {
        "calls": [
            {
                "call_sid": call_sid,
                "phone_number": stream.phone_number,
                "can_take_over": _handover_ready(),
            }
            for call_sid, stream in active_twilio_streams.items()
        ]
    }


@app.post("/twilio/calls/{call_sid}/takeover")
async def twilio_takeover(call_sid: str, request: Request):
    if not _internal_request_authorized(request):
        raise HTTPException(status_code=401, detail="Unauthorized")
    stream = active_twilio_streams.get(call_sid)
    if stream is None:
        raise HTTPException(status_code=404, detail="No active AI stream for this call")
    if not _handover_ready():
        raise HTTPException(status_code=503, detail="Human handover is not configured")
    pending_handover_calls.add(call_sid)
    await stream.request_handover()
    return {"status": "handover_requested", "call_sid": call_sid}


@app.get("/health")
async def health():
    config_errors = settings.validate()
    return {
        "status": "ok" if not config_errors else "degraded",
        "configuration_errors": config_errors,
        "session_auth_enabled": bool(settings.SESSION_AUTH_TOKEN),
        "twilio_voice_enabled": _twilio_ready(),
        "twilio_app_controls_enabled": bool(settings.SESSION_AUTH_TOKEN and _twilio_ready()),
        "twilio_handover_enabled": _handover_ready(),
    }


@app.websocket("/call-session")
async def call_session(ws: WebSocket):
    if settings.SESSION_AUTH_TOKEN:
        supplied = ws.headers.get("authorization", "")
        if supplied != f"Bearer {settings.SESSION_AUTH_TOKEN}":
            await ws.close(code=1008, reason="unauthorized")
            return
    await ws.accept()
    session = CallSession(ws)
    await session.run()


@app.websocket("/twilio/media/")
async def twilio_media(ws: WebSocket):
    if not _twilio_ready():
        await ws.close(code=1011, reason="Twilio media stream is not configured")
        return
    media_url = f"wss://{settings.PUBLIC_BASE_URL.removeprefix('https://')}/twilio/media/"
    params = dict(ws.query_params)
    if not _validate_twilio_signature(
        media_url,
        params,
        ws.headers.get("x-twilio-signature", ""),
    ):
        await ws.close(code=1008, reason="Invalid Twilio signature")
        return

    await ws.accept()
    stream = TwilioMediaSocket(
        ws,
        on_start=lambda call_sid, adapter: active_twilio_streams.__setitem__(
            call_sid, adapter
        ),
    )
    try:
        await CallSession(stream).run()
    except Exception:
        logger.exception("Twilio media session failed")
    finally:
        if stream.call_sid and active_twilio_streams.get(stream.call_sid) is stream:
            active_twilio_streams.pop(stream.call_sid, None)
        try:
            await stream.close()
        except Exception:
            pass


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host=settings.HOST, port=settings.PORT, reload=True)
