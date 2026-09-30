"""Minimal local receiver that verifies Webhook Relay signatures."""

import hashlib
import hmac
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SECRET = os.environ["RECEIVER_SIGNING_SECRET"].encode()
FAIL_FIRST = int(os.getenv("RECEIVER_FAIL_FIRST", "0"))
MAX_BODY_BYTES = 1_048_576
attempts = 0
attempts_lock = threading.Lock()


class ReceiverHandler(BaseHTTPRequestHandler):
    server_version = "WebhookRelayExample/1.0"

    def do_GET(self) -> None:  # noqa: N802 - stdlib API
        if self.path == "/health":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status":"UP"}')
            return
        self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802 - stdlib API
        if self.path != "/webhook":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length > MAX_BODY_BYTES:
            self.send_error(413)
            return
        body = self.rfile.read(length)
        timestamp = self.headers.get("X-Webhook-Timestamp", "")
        supplied_signature = self.headers.get("X-Webhook-Signature", "")
        expected_signature = "v1=" + hmac.new(
            SECRET,
            timestamp.encode() + b"." + body,
            hashlib.sha256,
        ).hexdigest()
        try:
            timestamp_is_fresh = abs(time.time() - int(timestamp)) <= 300
        except ValueError:
            timestamp_is_fresh = False
        if not timestamp_is_fresh or not hmac.compare_digest(
            supplied_signature,
            expected_signature,
        ):
            self.send_error(401, "Invalid or stale signature")
            return

        global attempts
        with attempts_lock:
            attempts += 1
            current_attempt = attempts
        print(
            json.dumps(
                {
                    "message": "verified webhook",
                    "deliveryId": self.headers.get("X-Webhook-Id"),
                    "eventId": self.headers.get("X-Webhook-Event-Id"),
                    "attempt": self.headers.get("X-Webhook-Attempt"),
                    "correlationId": self.headers.get("X-Correlation-ID"),
                    "payload": json.loads(body),
                }
            ),
            flush=True,
        )
        if current_attempt <= FAIL_FIRST:
            self.send_error(503, "Configured transient demo failure")
            return
        self.send_response(204)
        self.end_headers()

    def log_message(self, format_string: str, *args: object) -> None:
        print(
            json.dumps(
                {
                    "message": format_string % args,
                    "client": self.client_address[0],
                }
            ),
            flush=True,
        )


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), ReceiverHandler).serve_forever()
