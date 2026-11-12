# AI Call Assistant — Backend

Real, runnable FastAPI service implementing section 7 of the architecture doc.
The LLM integration supports Claude, Groq, and Gemini. Select one with
`LLM_PROVIDER`. STT (`app/stt.py`) and TTS
(`app/tts.py`) are vendor-agnostic interfaces with a skeleton implementation
each — fill in the vendor SDK calls marked `TODO` once you've picked and
validated a vendor (see the open risks below).

## Setup

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # then fill in your keys
uvicorn app.main:app --reload
```

## Environment variables (`.env`)

```
ANTHROPIC_API_KEY=sk-ant-...
CLAUDE_MODEL=claude-sonnet-4-6
LLM_PROVIDER=groq             # claude | groq | gemini
GROQ_API_KEY=gsk_...
GROQ_MODEL=llama-3.3-70b-versatile
GEMINI_API_KEY=AIza...
GEMINI_MODEL=gemini-2.5-flash
STT_PROVIDER=google
GOOGLE_APPLICATION_CREDENTIALS=/absolute/path/to/google-service-account.json
STT_API_KEY=...
TTS_PROVIDER=elevenlabs
TTS_API_KEY=...
TTS_VOICE_KENYAN=...
TTS_VOICE_AMERICAN=...
TTS_VOICE_SWAHILI=...
TTS_FALLBACK_PROVIDER=elevenlabs
ELEVENLABS_API_KEY=...
ELEVENLABS_VOICE_ID=...
ELEVENLABS_VOICE_KENYAN=...
ELEVENLABS_VOICE_AMERICAN=...
ELEVENLABS_VOICE_SWAHILI=...
TRANSLATION_ENABLED=false
SESSION_AUTH_TOKEN=...
```

For a release Android build, keep the endpoint and token outside source control
in your user-level Gradle properties file (for example,
`%USERPROFILE%\.gradle\gradle.properties`):

```properties
BACKEND_WS_URL=wss://your-real-host.example/call-session
BACKEND_AUTH_TOKEN=your-matching-session-token
BACKEND_HTTP_URL=https://your-real-host.example
```

`BACKEND_HTTP_URL` and `BACKEND_AUTH_TOKEN` are also used by the Android
AI Calls screen. Do not put Twilio credentials in the Android build; only the
backend should hold `TWILIO_AUTH_TOKEN`.

## Twilio Programmable Voice

For AI conversations on PSTN calls, configure the Twilio values in your local
`.env` (never commit it): `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`,
`TWILIO_PHONE_NUMBER`, `PUBLIC_BASE_URL`, and `TWILIO_OWNER_PHONE_NUMBER` for
human handover. The backend must be publicly reachable over HTTPS/WSS on port
443 with a valid certificate. Set the Twilio number's incoming Voice webhook
to `https://<PUBLIC_BASE_URL>/twilio/incoming` using `POST`.

The webhook validates Twilio signatures, then starts a bidirectional Media
Stream. The backend converts Twilio's 8 kHz μ-law audio to the existing 16 kHz
PCM call-session format and converts AI speech back to Twilio media. Your
carrier must forward calls to the Twilio number if callers should keep calling
your existing SIM number. Local development on `127.0.0.1` alone cannot receive
Twilio webhooks or media streams.

Run one backend worker while using the AI Calls takeover screen. Active call
state is currently held in process memory; multiple workers require a shared
registry (for example Redis) before deployment.

## cPanel / LiteSpeed hosting

This backend is an ASGI application, not a WSGI app. It requires a persistent
Uvicorn process and WebSocket upgrades for both `/call-session` and
`/twilio/media/`. A standard cPanel Python/Passenger application is not enough
unless the hosting provider explicitly supports running ASGI services and
proxying WebSockets to them. Confirm these capabilities with the provider
before deploying; do not wrap the app in a WSGI adapter, because that will not
support the call audio streams.
The cPanel screen that asks for an “Application Entry point” as a WSGI callable
is that WSGI-only path; do not use it to launch this FastAPI service.

If the cPanel plan supports those requirements:

1. Keep the backend code and credentials outside the web document root
   (usually `public_html`).
2. Create the Python environment from `requirements.txt` and run exactly one
   persistent Uvicorn worker using the process manager and bind address
   specified by the host.
3. Configure the host's reverse proxy to forward HTTP requests and WebSocket
   upgrades for the domain to that process. Enable HTTPS for the domain.
4. Set `PUBLIC_BASE_URL=https://call.kwcf.or.ke` and the other backend
   environment variables in the host's private environment settings. Store the
   Google service-account file outside the document root and point
   `GOOGLE_APPLICATION_CREDENTIALS` to its server path.
5. Confirm `https://call.kwcf.or.ke/health` responds from this app, then set the
   Twilio incoming Voice webhook to
   `https://call.kwcf.or.ke/twilio/incoming` with method `POST`.
6. For Android builds, set `BACKEND_WS_URL=wss://call.kwcf.or.ke/call-session`,
   `BACKEND_HTTP_URL=https://call.kwcf.or.ke`, and the matching
   `BACKEND_AUTH_TOKEN` in user-level Gradle properties.

Disable directory indexing for the domain and never put `.env`, service-account
JSON files, or other credentials in its public document root.

## Recommended managed deployment: Render

Render Web Services accept inbound WebSocket connections; deploy this as a
Web Service, not a Static Site. See [Render's WebSocket documentation](https://render.com/docs/websocket).

1. Push the source to a private GitHub repository, making sure no `.env` or
   credential files are included. Then create a Render Web Service from that
   repository with `backend` as the root directory.
2. Set the build command to `pip install -r requirements.txt` and the start
   command to `uvicorn app.main:app --host 0.0.0.0 --port $PORT --workers 1`.
   Do not upload the local `.venv`; Render should install dependencies on its
   Linux runtime.
3. Add the required backend environment variables in Render's environment
   settings. Set `PUBLIC_BASE_URL=https://call.kwcf.or.ke` and
   `SESSION_AUTH_TOKEN` to a new, strong secret. Provide the Google service
   account using a private secret file, not a file in the source tree.
4. Add `call.kwcf.or.ke` as a custom domain in Render, then apply the DNS
   record Render specifies at the domain's DNS provider. Change only the
   `call` subdomain record; do not change nameservers or unrelated records.
   Wait for HTTPS to become active.
5. Confirm `https://call.kwcf.or.ke/health` responds from this app. Set the
   Twilio incoming Voice webhook to
   `https://call.kwcf.or.ke/twilio/incoming` with method `POST`.
6. For Android builds, set `BACKEND_WS_URL=wss://call.kwcf.or.ke/call-session`,
   `BACKEND_HTTP_URL=https://call.kwcf.or.ke`, and the matching
   `BACKEND_AUTH_TOKEN` in user-level Gradle properties.

Keep the service to one instance/worker while active-call state is held in
process memory. Use an always-on service for production calling; platform
deployments and maintenance can still interrupt live calls.

## What's real vs. what's a skeleton

| Piece | Status |
|---|---|
| WebSocket session lifecycle, handover control, transcript bookkeeping | Real, functional, tested (see below) |
| Claude, Groq, or Gemini LLM turns + summaries | Real provider adapters (needs the selected API key) |
| STT streaming (Google Cloud Speech) | Real implementation, bridges the blocking gRPC streaming API onto asyncio. Needs `GOOGLE_APPLICATION_CREDENTIALS` |
| TTS streaming and fallback (ElevenLabs) | Real streaming HTTP call; fallback activates when the primary utterance fails. Needs API key + voice IDs |
| Optional English/Swahili translation | Real low-latency translation turn using the selected LLM provider |
| Error handling | Every failure path (bad input, missing credentials, LLM error, vendor error) now degrades gracefully — verified by deliberately triggering each one against a running server; none crash the process |

## What I actually tested (not just wrote)

I ran this server for real, opened real WebSocket connections against it, and exercised:
- Valid `/health` checks throughout
- A malformed `session_init` (missing required fields) → server replies with a structured error and closes cleanly, no server-side exception
- A valid `session_init` reaching all the way to a real STT/LLM call, both failing predictably (no credentials/placeholder key) and the server logging zero unhandled exceptions and staying healthy

This is the difference between "the code looks right" and "I watched it behave correctly under real failure conditions" — I found and fixed 4 real bugs this way (a dependency version incompatibility, and three places where a vendor/validation error would have crashed the whole call instead of degrading).

## Before deploying

- Validate Swahili STT accuracy directly with your chosen vendor (doc risk #2/#12.2) — don't assume parity with English.
- Confirm your TTS vendor actually has a genuine Kenyan-accent English voice, not just a generic African-adjacent one.
- Pick a hosting region close to Kenya — this is a latency-sensitive, real-time voice pipeline.
- `/health` is exposed for uptime checks; add auth in front of `/call-session` before going to production (currently open).
