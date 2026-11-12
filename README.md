# AI Call Assistant — Project Scaffold

This implements the architecture in your doc, in two halves:

- **`backend/`** — FastAPI + WebSocket session orchestrator + Claude API.
  Fully runnable today (see `backend/README.md`); STT/TTS vendor calls are
  marked `TODO` pending which vendor you validate.
- **`android/`** — Kotlin/Compose project covering Phase 1–7 of the doc's
  build order: Telecom framework (`ConnectionService`/`InCallService`),
  Room DB matching the section 5 schema, DataStore settings, permissions +
  OEM background-battery flows, and the Compose UI (call log, settings,
  in-call screen with take-over/return-to-AI).

## What this is not (yet)

I don't have an Android SDK/emulator or physical Samsung/Vivo hardware in
this environment, so:

1. **This has not been compiled or run.** Open `android/` in Android Studio,
   let Gradle sync, and fix whatever version-skew issues come up (Compose/
   Kotlin/AGP versions drift fast — bump as needed).
2. **Phase 0 from the doc is still outstanding and is the real risk.**
   Before building anything further on top of `AudioRouter.kt`, you need to
   confirm on your actual Samsung and Vivo test units whether raw call-audio
   capture works at all, and how long the app survives backgrounded before
   being killed. `AudioRouter.kt` has TODOs marking exactly where the answer
   to that spike plugs in.
3. **STT/TTS vendor wiring is stubbed**, not implemented — pick vendors,
   validate Swahili/Kenyan-accent quality directly (doc's own stated risk),
   then fill in `backend/app/stt.py` / `tts.py`.
4. **Play Console default-dialer + sensitive-permissions declarations**
   aren't something I can file for you — that's a manual step in your
   Play Console account before launch (doc section 2.4).

## Suggested order to actually pick this up

1. Run the Phase 0 spike on real hardware first — don't skip it, it changes
   how `AudioRouter` needs to work.
2. Get `android/` compiling in Studio, run Phase 1 (default dialer +
   answer/reject + known/unknown detection) on a device.
3. Stand up the backend (`backend/README.md`), get one end-to-end AI-mode
   call working with a real STT/TTS vendor.
4. Work through the remaining phases in the doc's table (handover, call
   log/detail UI, notifications, outbound calling, OEM onboarding, Play
   Console submission) in order.

Happy to go deeper on any one piece next — e.g. wire up a specific STT/TTS
vendor's real SDK calls, or flesh out the notification/reminder module,
whichever you want to tackle first.
