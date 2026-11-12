import unittest
import xml.etree.ElementTree as ET
from unittest.mock import AsyncMock

from fastapi.testclient import TestClient
from twilio.request_validator import RequestValidator

from app.config import settings
from app import main as main_module
from app.main import app


class TwilioWebhookTests(unittest.TestCase):
    def setUp(self):
        self.original = {
            name: getattr(settings, name)
            for name in (
                "TWILIO_AUTH_TOKEN",
                "PUBLIC_BASE_URL",
                "TWILIO_ASSISTANT_NAME",
                "TWILIO_USER_NAME",
                "TWILIO_LANGUAGE",
                "TWILIO_ACCENT",
                "TWILIO_RECORDING_DISCLOSURE_ENABLED",
                "TWILIO_OWNER_PHONE_NUMBER",
                "TWILIO_PHONE_NUMBER",
                "SESSION_AUTH_TOKEN",
            )
        }
        settings.TWILIO_AUTH_TOKEN = "unit-test-auth-token"
        settings.PUBLIC_BASE_URL = "https://calls.example.test"
        settings.TWILIO_ASSISTANT_NAME = "ARIA"
        settings.TWILIO_USER_NAME = "Alex"
        settings.TWILIO_LANGUAGE = "en"
        settings.TWILIO_ACCENT = "kenyan"
        settings.TWILIO_RECORDING_DISCLOSURE_ENABLED = True
        settings.TWILIO_OWNER_PHONE_NUMBER = "+15550001111"
        settings.TWILIO_PHONE_NUMBER = "+15552223333"
        settings.SESSION_AUTH_TOKEN = "unit-test-session-token"
        self.client = TestClient(app)

    def tearDown(self):
        for name, value in self.original.items():
            setattr(settings, name, value)

    def test_signed_inbound_webhook_returns_secure_stream_twiml(self):
        params = {
            "CallSid": "CA-unit-test",
            "From": "+15551234567",
            "To": "+15552223333",
        }
        url = f"{settings.PUBLIC_BASE_URL}/twilio/incoming"
        signature = RequestValidator(settings.TWILIO_AUTH_TOKEN).compute_signature(url, params)

        response = self.client.post(
            "/twilio/incoming",
            data=params,
            headers={"X-Twilio-Signature": signature},
        )

        self.assertEqual(response.status_code, 200)
        root = ET.fromstring(response.text)
        self.assertEqual(
            root.find("Connect/Stream").attrib["url"],
            "wss://calls.example.test/twilio/media/",
        )

    def test_invalid_inbound_signature_is_rejected(self):
        response = self.client.post(
            "/twilio/incoming",
            data={"CallSid": "CA-unit-test", "From": "+15551234567"},
            headers={"X-Twilio-Signature": "invalid"},
        )
        self.assertEqual(response.status_code, 403)

    def test_inbound_webhook_rejects_another_twilio_number(self):
        params = {
            "CallSid": "CA-wrong-number",
            "From": "+15551234567",
            "To": "+15550000000",
        }
        url = f"{settings.PUBLIC_BASE_URL}/twilio/incoming"
        signature = RequestValidator(settings.TWILIO_AUTH_TOKEN).compute_signature(url, params)
        response = self.client.post(
            "/twilio/incoming",
            data=params,
            headers={"X-Twilio-Signature": signature},
        )
        self.assertEqual(response.status_code, 403)

    def test_stream_handover_twiml_dials_owner(self):
        url = f"{settings.PUBLIC_BASE_URL}/twilio/stream-ended"
        params = {"CallSid": "CA-handover"}
        main_module.pending_handover_calls.add("CA-handover")
        signature = RequestValidator(settings.TWILIO_AUTH_TOKEN).compute_signature(url, params)
        response = self.client.post(
            "/twilio/stream-ended",
            data=params,
            headers={"X-Twilio-Signature": signature},
        )
        self.assertEqual(response.status_code, 200)
        dial = ET.fromstring(response.text).find("Dial")
        self.assertEqual(dial.attrib["callerId"], "+15552223333")
        self.assertEqual(dial.find("Number").text, "+15550001111")

    def test_unrequested_stream_end_does_not_dial_owner(self):
        params = {"CallSid": "CA-unrequested"}
        url = f"{settings.PUBLIC_BASE_URL}/twilio/stream-ended"
        signature = RequestValidator(settings.TWILIO_AUTH_TOKEN).compute_signature(url, params)
        response = self.client.post(
            "/twilio/stream-ended",
            data=params,
            headers={"X-Twilio-Signature": signature},
        )
        self.assertEqual(response.status_code, 200)
        root = ET.fromstring(response.text)
        self.assertIsNone(root.find("Dial"))
        self.assertIsNotNone(root.find("Hangup"))

    def test_active_call_and_takeover_api_require_app_auth(self):
        stream = AsyncMock()
        stream.phone_number = "+15551234567"
        main_module.active_twilio_streams["CA-active"] = stream
        try:
            denied = self.client.get("/twilio/active-calls")
            self.assertEqual(denied.status_code, 401)

            headers = {"Authorization": "Bearer unit-test-session-token"}
            active = self.client.get("/twilio/active-calls", headers=headers)
            self.assertEqual(active.status_code, 200)
            self.assertEqual(active.json()["calls"][0]["call_sid"], "CA-active")

            takeover = self.client.post(
                "/twilio/calls/CA-active/takeover",
                headers=headers,
            )
            self.assertEqual(takeover.status_code, 200)
            stream.request_handover.assert_awaited_once()
        finally:
            main_module.active_twilio_streams.pop("CA-active", None)

    def test_takeover_is_unavailable_without_owner_number(self):
        stream = AsyncMock()
        main_module.active_twilio_streams["CA-active"] = stream
        settings.TWILIO_OWNER_PHONE_NUMBER = ""
        try:
            response = self.client.post(
                "/twilio/calls/CA-active/takeover",
                headers={"Authorization": "Bearer unit-test-session-token"},
            )
            self.assertEqual(response.status_code, 503)
            stream.request_handover.assert_not_awaited()
        finally:
            main_module.active_twilio_streams.pop("CA-active", None)


if __name__ == "__main__":
    unittest.main()
