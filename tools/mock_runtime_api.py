#!/usr/bin/env python3
"""Mock of the AWS Lambda Runtime API (2018-06-01) for offline testing.

Serves N canned events on GET /runtime/invocation/next (with the same
response headers real Lambda sends), captures the runtime's POSTed
responses, then answers 410 Gone so the runtime loop exits cleanly.
Asserts one response per event and prints PASS/FAIL. Exit code 0/1.
"""
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

HOST = "127.0.0.1"
PORT_FILE = ".mock-runtime-api-port"

EVENTS = [b'{"name":"jolt","n":1}', b'{"name":"chez","n":2}']

state = {"served": 0, "done": False, "responses": []}


class MockRuntimeAPI(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path.endswith("/invocation/next") and state["served"] < len(EVENTS):
            event = EVENTS[state["served"]]
            state["served"] += 1
            self.send_response(200)
            self.send_header("Lambda-Runtime-Aws-Request-Id", "req-%d" % state["served"])
            self.send_header("Lambda-Runtime-Deadline-Ms", "9999999999999")
            self.send_header("Lambda-Runtime-Invoked-Function-Arn",
                             "arn:aws:lambda:local:000000000000:function:mock")
            self.send_header("Lambda-Runtime-Trace-Id", "Root=1-mock")
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(event)))
            self.end_headers()
            self.wfile.write(event)
        else:
            state["done"] = True
            self.send_response(410)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n)
        state["responses"].append((self.path, body.decode("utf-8", "replace")))
        reply = b'{"status":"OK"}'
        self.send_response(202)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)


def check_hello(responses):
    ok = True
    for i, (path, body) in enumerate(responses, 1):
        if "hello from jolt" not in body:
            ok = False
            print("FAIL: response %d body missing greeting: %s" % (i, body))
    return ok


def main():
    httpd = HTTPServer((HOST, 0), MockRuntimeAPI)
    httpd.timeout = 30
    port = httpd.server_port
    with open(PORT_FILE, "w") as f:
        f.write(str(port))
    print("mock-runtime-api: listening on %s:%d, %d events" % (HOST, port, len(EVENTS)))
    try:
        while not state["done"]:
            httpd.handle_request()

        ok = True
        if len(state["responses"]) != len(EVENTS):
            ok = False
            print("FAIL: expected %d responses, got %d" % (len(EVENTS), len(state["responses"])))
        for i, (path, body) in enumerate(state["responses"], 1):
            want = "/runtime/invocation/req-%d/response" % i
            if not path.endswith(want):
                ok = False
                print("FAIL: response %d posted to %s (want suffix %s)" % (i, path, want))
            print("mock-runtime-api: response %d: %s" % (i, body))
        if not check_hello(state["responses"]):
            ok = False
        print("mock-runtime-api: %s" % ("PASS" if ok else "FAIL"))
        sys.exit(0 if ok else 1)
    finally:
        if os.path.exists(PORT_FILE):
            os.remove(PORT_FILE)


if __name__ == "__main__":
    main()
