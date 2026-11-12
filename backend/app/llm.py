"""Provider-neutral low-latency LLM and translation calls."""
from anthropic import AsyncAnthropic
import httpx
from .config import settings
from .models import SessionInit

_client = AsyncAnthropic(api_key=settings.ANTHROPIC_API_KEY)


def build_system_prompt(init: SessionInit) -> str:
    caller_desc = (
        f"a known contact named {init.contact_name}"
        if init.is_known
        else "an unknown caller"
    )
    return (
        f"You are {init.assistant_name}, an AI phone assistant answering calls "
        f"on behalf of {init.user_name}.\n"
        f"The caller is {caller_desc}.\n"
        f"Speak {init.language.value}, in a {init.accent.value} accent style "
        f"(this only affects word choice/formality, not audio -- TTS handles the voice).\n"
        "Keep responses short -- 1-2 sentences per turn. Get to the point.\n"
        "Your goals, in order: (1) greet caller, (2) find out why they're calling, "
        f"(3) take a message if {init.user_name} can't be reached, (4) end the call "
        "politely once purpose is captured. Do not ramble or ask unnecessary "
        "follow-up questions."
    )


async def next_ai_turn(system_prompt: str, history: list[dict], caller_utterance: str) -> str:
    """
    history: list of {"role": "user"|"assistant", "content": str}
    caller_utterance: latest finalized STT text from the caller
    Returns the AI's reply text (to be sent to TTS).
    """
    return await _complete(
        system_prompt,
        history + [{"role": "user", "content": caller_utterance}],
        max_tokens=200,
    )


async def _complete(system_prompt: str, messages: list[dict], max_tokens: int) -> str:
    if settings.LLM_PROVIDER == "claude":
        response = await _client.messages.create(
            model=settings.CLAUDE_MODEL,
            max_tokens=max_tokens,
            system=system_prompt,
            messages=messages,
        )
        return "".join(block.text for block in response.content if block.type == "text").strip()

    if settings.LLM_PROVIDER == "groq":
        payload = {
            "model": settings.GROQ_MODEL,
            "messages": [{"role": "system", "content": system_prompt}, *messages],
            "temperature": 0.2,
            "max_tokens": max_tokens,
        }
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(
                "https://api.groq.com/openai/v1/chat/completions",
                headers={"Authorization": f"Bearer {settings.GROQ_API_KEY}"},
                json=payload,
            )
            response.raise_for_status()
            return response.json()["choices"][0]["message"]["content"].strip()

    if settings.LLM_PROVIDER == "gemini":
        contents = [
            {
                "role": "user" if item["role"] == "user" else "model",
                "parts": [{"text": item["content"]}],
            }
            for item in messages
        ]
        payload = {
            "system_instruction": {"parts": [{"text": system_prompt}]},
            "contents": contents,
            "generationConfig": {"temperature": 0.2, "maxOutputTokens": max_tokens},
        }
        url = (
            f"https://generativelanguage.googleapis.com/v1beta/models/"
            f"{settings.GEMINI_MODEL}:generateContent?key={settings.GEMINI_API_KEY}"
        )
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(url, json=payload)
            response.raise_for_status()
            return response.json()["candidates"][0]["content"]["parts"][0]["text"].strip()

    raise ValueError(f"Unsupported LLM provider: {settings.LLM_PROVIDER}")


async def translate_text(text: str, source_language: str, target_language: str) -> str:
    if source_language == target_language:
        return text
    return await _complete(
        "You are a precise real-time phone translator. Return only the translation.",
        [{
            "role": "user",
            "content": (
                f"Translate this phone utterance from {source_language} to "
                f"{target_language}, preserving names, intent, and urgency:\n{text}"
            ),
        }],
        max_tokens=160,
    )


async def generate_summary(transcript_lines: list[str]) -> str:
    """Runs once at call end on the AI-mode transcript only."""
    transcript_text = "\n".join(transcript_lines)
    return await _complete(
        (
            "Summarize this phone call transcript in 2-3 sentences for the "
            "user to read later. Focus on who called, why, and any message "
            "or action needed. Be factual, no speculation."
        ),
        [{"role": "user", "content": transcript_text}],
        max_tokens=150,
    )
