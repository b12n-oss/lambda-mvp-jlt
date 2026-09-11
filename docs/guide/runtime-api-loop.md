# The Runtime API loop

## The contract

A Lambda **custom runtime** is any Linux executable named `bootstrap` that
speaks the Runtime API — plain HTTP on a loopback address, no TLS:

| Step | Call |
|---|---|
| Where | `$AWS_LAMBDA_RUNTIME_API` env var (`host:port`) |
| Next event | `GET /2018-06-01/runtime/invocation/next` — long-polls until Lambda has work |
| Event data | response **body** = event JSON; response **headers** carry `Lambda-Runtime-Aws-Request-Id`, `Lambda-Runtime-Deadline-Ms`, `Lambda-Runtime-Invoked-Function-Arn`, `Lambda-Runtime-Trace-Id` |
| Success | `POST /2018-06-01/runtime/invocation/{request-id}/response` |
| Handler error | `POST /2018-06-01/runtime/invocation/{request-id}/error` |
| Startup error | `POST /2018-06-01/runtime/init/error` |

The load-bearing subtlety: **the request id arrives as a response header on
`/next`**, so the HTTP client must expose headers. Jolt's
`jolt-lang/http-client` (clj-http-lite on jolt host shims) returns
`{:status :headers :body}` with lowercase string header keys — exactly enough.

## The jolt implementation

`src/net/b12n/lambda_mvp/runtime.clj` is the whole runtime:

```clojure
(loop []
  (let [{:keys [status headers body]} (http/get (str base "/invocation/next")
                                                {:throw-exceptions false})]
    (if (= 200 status)
      (let [{:keys [request-id] :as ctx} (invocation-context headers)]
        (try
          (post-response base request-id (handler body ctx))
          (catch Exception e
            (post-error base request-id (ex-message e) "HandlerError")))
        (recur))
      (println "runtime: /invocation/next returned" status "— exiting"))))
```

Design notes:

- **`:throw-exceptions false` everywhere.** clj-http-lite throws on non-2xx by
  default; the loop wants statuses as data (the mock ends a local run with 410
  Gone, and error-path POSTs must never take down the loop).
- **No JSON dependency in the loop.** The event passes through as a raw string;
  the demo handler embeds it verbatim in its response (`",\"event\":" event-json`)
  — legal because the event is already JSON.
- **No timeout on `/next`.** The GET blocks until the next invocation; Lambda
  freezes the sandbox between events, so wall-clock time there is free.
- **Handler contract**: `(fn [event-json ctx]) -> response-json-string`, with
  `ctx` = `{:request-id :deadline-ms :invoked-arn :trace-id}`.

## Testing without AWS: the mock Runtime API

`tools/mock_runtime_api.py` binds an OS-assigned ephemeral port (published to
`.mock-runtime-api-port`, which `bb.edn`'s `probe` task reads back) and mimics
the contract:
canned events on `/invocation/next` with the real header set, captured POSTs,
then **410 Gone** so the loop exits — and asserts one well-formed response per
event (exit 0/1). `bb probe` wires it to the real loop under `joltc run`:

```
mock-runtime-api: response 1: {"message":"hello from jolt on lambda",...,"request_id":"req-1",...}
mock-runtime-api: response 2: {"message":"hello from jolt on lambda",...,"request_id":"req-2",...}
mock-runtime-api: PASS
runtime: /invocation/next returned 410 — exiting
```

This makes runtime-loop changes a ~2-second local iteration, with the identical
code path that runs on Lambda (only `AWS_LAMBDA_RUNTIME_API` differs).

## What the loop deliberately skips

- `_X_AMZN_TRACE_ID` env propagation (X-Ray) — the trace id is captured into
  `ctx` but not exported.
- Response streaming and the Extensions API — separate contracts.
- JSON parsing — a real handler wanting parsed events pulls a pure-Clojure JSON
  lib from git, the way jolt examples pull hiccup/malli.
