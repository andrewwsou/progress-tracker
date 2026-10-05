"""Invokes each scheduled Lambda's handler against a local HTTP server that stands in for the API.

Checks what the handler sends (path, method, token header), that it returns the API's answer,
and that a refused call raises, so the invocation fails and the error alarm sees it.
Stdlib-only, like the handlers:

    python3 -m unittest discover -s infra/lambda -p "test_*.py"
"""
import contextlib
import http.server
import importlib.util
import io
import json
import os
import socketserver
import sys
import threading
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

LAMBDA_DIR = Path(__file__).resolve().parent
TOKEN = "test-automation-token"

# Terraform zips each handler folder as it is, so loading a handler must not leave a __pycache__ in it.
sys.dont_write_bytecode = True


def load_handler(folder):
    # Both files are named handler.py, so each is loaded under its folder's name.
    spec = importlib.util.spec_from_file_location(f"{folder}_handler", LAMBDA_DIR / folder / "handler.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeApi(socketserver.ThreadingTCPServer):
    """Records every request and answers each with reply_status and a small JSON body.

    Not an http.server.HTTPServer: that one looks up the host's name on startup, which can take
    seconds on a laptop.
    """

    daemon_threads = True

    def __init__(self):
        super().__init__(("127.0.0.1", 0), RecordingRequestHandler)
        self.requests = []
        self.reply_status = 200


class RecordingRequestHandler(http.server.BaseHTTPRequestHandler):
    def record_and_reply(self):
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        self.server.requests.append({
            "method": self.command,
            "path": self.path,
            "token": self.headers.get("X-Internal-Token"),
        })
        status = self.server.reply_status
        body = json.dumps({"status": status}).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_PUT = do_DELETE = record_and_reply

    def log_message(self, format, *args):
        pass


class HandlerContract:
    """The checks both handlers share. Subclasses set the folder and the endpoint path."""

    folder = None
    expected_path = None

    def setUp(self):
        self.api = FakeApi()
        thread = threading.Thread(target=self.api.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.api.server_close)
        self.addCleanup(self.api.shutdown)

        # A trailing slash, as an operator might write it; the handler must not double it.
        environment = {
            "API_BASE_URL": f"http://127.0.0.1:{self.api.server_address[1]}/",
            "AUTOMATION_TOKEN": TOKEN,
            "NO_PROXY": "127.0.0.1",
            "no_proxy": "127.0.0.1",
        }
        patcher = mock.patch.dict(os.environ, environment)
        patcher.start()
        self.addCleanup(patcher.stop)

        self.module = load_handler(self.folder)

    def invoke(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return self.module.handler({}, None)

    def test_posts_to_the_endpoint_with_the_token(self):
        result = self.invoke()

        self.assertEqual(200, result["statusCode"])
        self.assertEqual({"status": 200}, json.loads(result["body"]))
        self.assertEqual(
            [{"method": "POST", "path": self.expected_path, "token": TOKEN}],
            self.api.requests,
        )

    def test_a_refused_call_fails_the_invocation(self):
        self.api.reply_status = 401

        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.invoke()

        self.assertEqual(401, raised.exception.code)
        self.assertEqual(1, len(self.api.requests))


class DailyStreakResetTest(HandlerContract, unittest.TestCase):
    folder = "daily_streak_reset"
    expected_path = "/api/internal/automations/reset-streaks"


class WeeklySummaryTest(HandlerContract, unittest.TestCase):
    folder = "weekly_summary"
    expected_path = "/api/internal/automations/weekly-summary"


if __name__ == "__main__":
    unittest.main()
