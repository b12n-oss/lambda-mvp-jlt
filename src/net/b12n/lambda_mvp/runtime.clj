(ns net.b12n.lambda-mvp.runtime
  "An AWS Lambda custom runtime written in Jolt.

  Implements the Lambda Runtime API contract (version 2018-06-01) -- the same
  poll/execute/respond loop that awslabs/aws-lambda-cpp wraps in C++:

    GET  /runtime/invocation/next            long-poll the next event
    POST /runtime/invocation/{id}/response   report the handler's result
    POST /runtime/invocation/{id}/error      report a handler failure
    POST /runtime/init/error                 report a startup failure

  The API host:port arrives in the AWS_LAMBDA_RUNTIME_API env var; the
  request id and invocation metadata arrive as response headers on /next.
  Plain HTTP on a loopback address -- no TLS involved."
  (:require [jolt.http-client :as http]))

(def ^:private api-version "2018-06-01")

(defn- base-url []
  (str "http://" (System/getenv "AWS_LAMBDA_RUNTIME_API")
       "/" api-version "/runtime"))

(defn- next-invocation
  "Long-poll the next event. Blocks until Lambda has work for us.
  :throw-exceptions false so non-2xx statuses surface as data, not throws."
  [base]
  (http/get (str base "/invocation/next") {:throw-exceptions false}))

(defn- post-response [base req-id body]
  (http/post (str base "/invocation/" req-id "/response")
             {:body body :throw-exceptions false}))

(defn- error-json [msg type]
  (str "{\"errorMessage\":" (pr-str (str msg))
       ",\"errorType\":" (pr-str (str type)) "}"))

(defn- post-error [base req-id msg type]
  (http/post (str base "/invocation/" req-id "/error")
             {:body (error-json msg type)
              :headers {"Lambda-Runtime-Function-Error-Type" (str type)}
              :throw-exceptions false}))

(defn post-init-error
  "Report a failure during startup (before the loop begins)."
  [msg type]
  (http/post (str (base-url) "/init/error")
             {:body (error-json msg type)
              :headers {"Lambda-Runtime-Function-Error-Type" (str type)}
              :throw-exceptions false}))

(defn- invocation-context
  "The per-invocation metadata Lambda passes as response headers on /next.
  Header keys are lowercase strings in jolt's http client."
  [headers]
  {:request-id  (get headers "lambda-runtime-aws-request-id")
   :deadline-ms (get headers "lambda-runtime-deadline-ms")
   :invoked-arn (get headers "lambda-runtime-invoked-function-arn")
   :trace-id    (get headers "lambda-runtime-trace-id")})

(defn run
  "Run the custom-runtime loop. `handler` is (fn [event-json ctx]) -> json
  string, where event-json is the raw event payload and ctx is the map from
  `invocation-context`. Loops until /next returns a non-200 status (never, on
  real Lambda -- the environment freezes between events and kills the process
  by recycling the sandbox; locally the mock ends a test run with 410 Gone)."
  [handler]
  (let [base (base-url)]
    (loop []
      (let [{:keys [status headers body]} (next-invocation base)]
        (if (= 200 status)
          (let [{:keys [request-id] :as ctx} (invocation-context headers)]
            (try
              (post-response base request-id (handler body ctx))
              (catch Exception e
                (post-error base request-id
                            (or (ex-message e) "handler failed")
                            "HandlerError")))
            (recur))
          (println "runtime: /invocation/next returned" status "-- exiting"))))))
