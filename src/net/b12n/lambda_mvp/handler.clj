(ns net.b12n.lambda-mvp.handler
  "Demo handler: greet, echo the raw event, and report which invocation this
  warm sandbox is on. Dependency-free JSON emission -- the event payload is
  already JSON, so it embeds verbatim; strings go through pr-str (Clojure
  string literal escaping is JSON-compatible for the ASCII range).")

(def ^:private invocation-count (atom 0))

(defn handle
  "event-json: raw event payload (string). ctx: {:request-id :deadline-ms
  :invoked-arn :trace-id}. Returns the response JSON as a string."
  [event-json {:keys [request-id]}]
  (let [n (swap! invocation-count inc)]
    (str "{\"message\":\"hello from jolt on lambda\""
         ",\"runtime\":\"jolt (Clojure on Chez Scheme)\""
         ",\"request_id\":" (pr-str (or request-id ""))
         ",\"warm_invocation\":" n
         ",\"event\":" (if (seq event-json) event-json "null")
         "}")))
