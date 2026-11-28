"""Loopback-only model fixture for the isolated Stage 3 Gateway.

Never record request text, results, or credentials. Select a mode through the
upstream model name so each scenario can use its own approved model mapping.
"""
import hashlib
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FIRST_OUTPUT_DELAYS = {59, 60, 61, 90, 120, 180}
REQUEST_LIMIT = 16 * 1024 * 1024
HEARTBEAT_INTERVAL = 2


class Fixture(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def reply(self, status, document):
        body = json.dumps(document).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def emit(self, line):
        self.wfile.write(line)
        self.wfile.flush()

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.reply(404, {"error": {"code": "fixture_route"}})
            return
        try:
            count = int(self.headers.get("Content-Length", "0"))
            if not 0 < count <= REQUEST_LIMIT:
                self.close_connection = True
                self.reply(413, {"error": {"code": "fixture_body_limit"}})
                return
            body = self.rfile.read(count)
            mode = json.loads(body).get("model")
        except (ValueError, AttributeError):
            self.reply(400, {"error": {"code": "fixture_bad_request"}})
            return
        allowed = {"reject-413", "heartbeat-only", "reject-429", "partial-tool-call"}
        allowed.update(f"{style}-{delay}" for style in ("first", "headers-first", "heartbeat-first")
                       for delay in FIRST_OUTPUT_DELAYS)
        if not isinstance(mode, str) or mode not in allowed:
            self.reply(400, {"error": {"code": "unknown_fixture_mode"}})
            return
        accepted = time.monotonic()
        fingerprint = hashlib.sha256(body).hexdigest()
        print(json.dumps({"event": "accepted", "mode": mode, "bytes": count,
                          "requestHash": fingerprint, "unixTime": time.time()}), flush=True)
        try:
            self.respond(mode)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            print(json.dumps({"event": "disconnected", "mode": mode, "requestHash": fingerprint,
                              "ms": round((time.monotonic() - accepted) * 1000)}), flush=True)

    def respond(self, mode):
        if mode.startswith("reject-"):
            self.reply(int(mode.split("-")[1]), {"error": {"code": "fixture_" + mode}})
            return
        if mode.startswith("first-"):
            time.sleep(int(mode.split("-")[1]))
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        if mode == "heartbeat-only":
            self.heartbeat_until(time.monotonic() + 210)
            return
        if mode.startswith("headers-first-"):
            time.sleep(int(mode.rsplit("-", 1)[1]))
        if mode.startswith("heartbeat-first-"):
            self.heartbeat_until(time.monotonic() + int(mode.rsplit("-", 1)[1]))
        if mode == "partial-tool-call":
            self.delta({"tool_calls": [{"index": 0, "id": "fixture-call",
                                       "type": "function",
                                       "function": {"name": "fixture_tool", "arguments": '{"filter":'}}]})
            return
        self.delta({"content": "Fixture ready."})
        self.delta({}, finish_reason="stop")
        self.emit(b"data: [DONE]\n\n")

    def delta(self, value, finish_reason=None):
        event = {"object": "chat.completion.chunk",
                 "choices": [{"index": 0, "finish_reason": finish_reason, "delta": value}]}
        self.emit(b"data: " + json.dumps(event).encode() + b"\n\n")

    def heartbeat_until(self, end):
        while (remaining := end - time.monotonic()) > 0:
            self.emit(b": fixture-heartbeat\n\n")
            time.sleep(min(HEARTBEAT_INTERVAL, remaining))


if __name__ == "__main__":
    with ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Fixture) as server:
        server.serve_forever()
