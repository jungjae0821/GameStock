"""Failure/recovery and transport tests; never contact production or Discord."""
import base64
import contextlib
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
import urllib.error
import urllib.parse

import monitor


class TestServer(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    seen = []

    def log_message(self, *_):
        pass

    def do_GET(self):
        self.seen.append(self.path)
        if self.path in ("/ws", "/bad-ws"):
            self.send_response(101)
            self.send_header("Upgrade", "websocket")
            self.send_header("Connection", "Upgrade")
            key = self.headers["Sec-WebSocket-Key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            accept = base64.b64encode(hashlib.sha1(key.encode()).digest()).decode()
            self.send_header("Sec-WebSocket-Accept", accept if self.path == "/ws" else "incorrect")
            self.end_headers()
            return
        status, body = 200, b'{"status":"ready"}'
        if self.path == "/down":
            status, body = 503, b'{"status":"recovering"}'
        elif self.path == "/malformed":
            body = b"not JSON"
        elif self.path == "/wrong":
            body = b'{"status":"recovering"}'
        elif self.path == "/web":
            body = b'<html><div id="root"></div></html>'
        elif self.path == "/health":
            body = b'{"status":"ok","botScheduler":{"started":true}}'
        elif self.path == "/number":
            body = b'{"botScheduler":{"started":1}}'
        elif self.path == "/redirect":
            status = 302
        self.send_response(status)
        if status == 302:
            self.send_header("Location", "/ready")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class ProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), TestServer)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = "http://127.0.0.1:" + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def target(self, path="/ready", **extra):
        return {"name": "DB", "type": "json", "url": self.base + path, "expect": {"status": "ready"}, **extra}

    def test_real_http_readiness_and_frontend(self):
        self.assertIsNone(monitor.check_target(self.target(), 1))
        self.assertEqual(monitor.check_target(self.target("/down"), 1), "HTTP 503")
        self.assertIn("status", monitor.check_target(self.target("/wrong"), 1))
        self.assertIn("JSON", monitor.check_target(self.target("/malformed"), 1))
        self.assertIsNone(monitor.check_target(self.target("/web", type="http", contains='id="root"'), 1))
        self.assertIsNotNone(monitor.check_target(self.target("/web", type="http", contains="missing"), 1))

    def test_nested_scheduler_boolean_must_be_true_not_number(self):
        expected = {"botScheduler.started": True}
        self.assertIsNone(monitor.check_target(self.target("/health", expect=expected), 1))
        self.assertIsNotNone(monitor.check_target(self.target("/number", expect=expected), 1))

    def test_redirect_is_failure_and_not_followed(self):
        TestServer.seen.clear()
        self.assertEqual(monitor.check_target(self.target("/redirect"), 1), "HTTP 302")
        self.assertEqual(TestServer.seen, ["/redirect"])

    def test_real_websocket_accept_key_is_verified(self):
        url = self.base.replace("http://", "ws://")
        self.assertIsNone(monitor.check_target({"type": "websocket", "url": url + "/ws"}, 1))
        self.assertIn("handshake", monitor.check_target({"type": "websocket", "url": url + "/bad-ws"}, 1))

    def test_closed_server_is_not_healthy(self):
        with socket.socket() as unused:
            unused.bind(("127.0.0.1", 0))
            url = "http://127.0.0.1:" + str(unused.getsockname()[1])
        self.assertIsNotNone(monitor.check_target(self.target(url=url), 1))

    def test_transient_failure_is_retried_before_alert(self):
        config = {"targets": [self.target()], "attempts": 2, "timeout_seconds": 1, "retry_delay_seconds": 0}
        with patch.object(monitor, "check_target", side_effect=["HTTP 503", None]) as check:
            self.assertEqual(monitor.probe(config), {})
            self.assertEqual(check.call_count, 2)
        with patch.object(monitor, "check_target", return_value="HTTP 503"):
            self.assertEqual(monitor.probe(config), {"DB": "HTTP 503"})

    def test_check_only_needs_no_webhook_and_writes_no_state(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "targets.json"
            state = Path(directory) / "state.json"
            config.write_text(json.dumps({"targets": [self.target()]}))
            result = subprocess.run([
                sys.executable, "-B", str(Path(monitor.__file__)), "--config", str(config),
                "--state-file", str(state), "--check-only",
            ], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(json.loads(result.stdout)["healthy"])
            self.assertFalse(state.exists())


class IncidentTest(unittest.TestCase):
    def setUp(self):
        self.config = {"targets": [{"name": "DB"}]}
        self.state = monitor.initial_state(self.config)
        self.sender = Mock()

    def update(self, failures, now, sender=None, test=False):
        with contextlib.redirect_stdout(io.StringIO()):
            return monitor.update_notifications(self.state, failures, now, 1800, sender or self.sender, test)

    def test_incident_is_deduplicated_then_reminded_and_recovered(self):
        self.update({}, 100)
        self.sender.assert_not_called()
        self.update({"DB": "HTTP 503"}, 130)
        self.update({"DB": "HTTP 503"}, 160)
        self.assertEqual(self.sender.call_count, 1)
        self.update({"DB": "timeout"}, 1930)
        self.assertEqual(self.sender.call_count, 2)
        self.update({}, 1960)
        self.assertIn("복구 완료", self.sender.call_args.args[0])
        self.assertIn("1830초", self.sender.call_args.args[0])
        self.update({}, 1990)
        self.assertEqual(self.sender.call_count, 3)
        self.assertIsNone(self.state["incident"])

    def test_partial_recovery_and_new_failure_are_reported(self):
        self.update({"DB": "down", "API": "down"}, 100)
        self.update({"DB": "down"}, 130)
        self.update({"DB": "down", "웹": "down"}, 160)
        self.assertEqual(self.sender.call_count, 3)
        self.update({}, 190)
        self.assertIn("API", self.sender.call_args.args[0])
        self.assertIn("웹", self.sender.call_args.args[0])

    def test_failed_delivery_is_retried_and_rate_limit_is_respected(self):
        broken = Mock(side_effect=monitor.DeliveryError("Discord HTTP 429", 90))
        self.assertFalse(self.update({"DB": "down"}, 100, broken))
        self.assertEqual(self.state["incident"]["notified"], [])
        self.assertFalse(self.update({"DB": "down"}, 130))
        self.sender.assert_not_called()
        self.assertTrue(self.update({"DB": "down"}, 190))
        self.sender.assert_called_once()

    def test_failed_recovery_notification_survives_restart(self):
        self.update({"DB": "down"}, 100)
        broken = Mock(side_effect=monitor.DeliveryError("Discord HTTP 500"))
        self.assertFalse(self.update({}, 130, broken))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            monitor.save_state(path, self.state)
            self.state = monitor.load_state(path, self.config)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.update({}, 160)
        self.assertIn("복구 완료", self.sender.call_args.args[0])
        self.assertIsNone(self.state["incident"])

    def test_rate_limit_wait_starts_when_the_response_arrives(self):
        broken = Mock(side_effect=monitor.DeliveryError("Discord HTTP 429", 90, received_at=108))
        self.update({"DB": "down"}, 100, broken)
        self.assertFalse(self.update({"DB": "down"}, 190))
        self.sender.assert_not_called()
        self.assertTrue(self.update({"DB": "down"}, 198))
        self.sender.assert_called_once()

    def test_recovery_is_reported_even_if_initial_alert_could_not_be_delivered(self):
        broken = Mock(side_effect=monitor.DeliveryError("Discord offline"))
        self.update({"DB": "down"}, 100, broken)
        self.update({}, 130)
        self.assertIn("복구 완료", self.sender.call_args.args[0])

    def test_restart_preserves_duplicate_suppression(self):
        self.update({"DB": "down"}, 100)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            monitor.save_state(path, self.state)
            self.state = monitor.load_state(path, self.config)
        self.update({"DB": "down"}, 130)
        self.sender.assert_called_once()

    def test_corrupt_state_cannot_silently_disable_notifications(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            for payload in ("{", "null", '{"version": 1}', json.dumps({**self.state, "next_notification_at": float("inf")})):
                path.write_text(payload)
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(monitor.load_state(path, self.config), monitor.initial_state(self.config))

    def test_test_notification_is_explicit(self):
        self.update({}, 100, test=True)
        self.assertIn("감시 테스트", self.sender.call_args.args[0])


class DiscordTest(unittest.TestCase):
    url = "https://discord.com/api/webhooks/123456/test-token"

    def test_confirmation_and_thread_routing_are_preserved(self):
        url = monitor.webhook_url(self.url + "?wait=false&thread_id=789")
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
        self.assertEqual(query, {"wait": ["true"], "thread_id": ["789"]})
        response = Mock(status=200)
        response.read.return_value = b'{"id":"message-id"}'
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.return_value = response
        with patch.object(monitor.urllib.request, "build_opener", return_value=opener):
            monitor.send_discord(url, "test @everyone")
        request = opener.open.call_args.args[0]
        self.assertEqual(json.loads(request.data)["allowed_mentions"], {"parse": []})
        self.assertIn("wait=true", request.full_url)

    def test_credentials_cannot_be_sent_to_another_host(self):
        for url in (
            "http://discord.com/api/webhooks/123/token",
            "https://discord.com.attacker.example/api/webhooks/123/token",
            "https://user:password@discord.com/api/webhooks/123/token",
            "https://discord.com/api/webhooks/123/token/slack",
        ):
            with self.subTest(url=url), self.assertRaises(monitor.MonitorError):
                monitor.webhook_url(url)

    def test_429_retries_later_without_leaking_token(self):
        error = urllib.error.HTTPError(self.url, 429, self.url, {}, io.BytesIO(b'{"retry_after":90}'))
        opener = Mock()
        opener.open.side_effect = error
        with patch.object(monitor.urllib.request, "build_opener", return_value=opener):
            with self.assertRaises(monitor.DeliveryError) as caught:
                monitor.send_discord(self.url, "alert")
        self.assertEqual(caught.exception.retry_after, 90)
        self.assertNotIn("test-token", str(caught.exception))

    def test_unconfirmed_response_is_not_recorded_as_delivered(self):
        for status, body in ((204, b""), (200, b"{}"), (200, b"not-json")):
            response = Mock(status=status)
            response.read.return_value = body
            response.__enter__ = Mock(return_value=response)
            response.__exit__ = Mock(return_value=False)
            opener = Mock()
            opener.open.return_value = response
            with patch.object(monitor.urllib.request, "build_opener", return_value=opener):
                with self.assertRaises(monitor.DeliveryError):
                    monitor.send_discord(self.url, "alert")


if __name__ == "__main__":
    unittest.main(verbosity=2)
