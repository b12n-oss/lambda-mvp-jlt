(ns net.b12n.lambda-mvp.main
  "Entry point -- compiled to the `bootstrap` executable by `joltc build`.
  Lambda's provided.al2023 environment execs /var/task/bootstrap directly."
  (:require [net.b12n.lambda-mvp.handler :as handler]
            [net.b12n.lambda-mvp.runtime :as runtime]))

(defn -main [& _args]
  (try
    (runtime/run handler/handle)
    (catch Exception e
      ;; Print first: if post-init-error itself throws (e.g. a malformed
      ;; AWS_LAMBDA_RUNTIME_API), the diagnostic must not be lost with it.
      (println "runtime: fatal:" (ex-message e))
      ;; Startup/loop-level failure: tell Lambda, then let the process die so
      ;; the sandbox recycles.
      (runtime/post-init-error (or (ex-message e) "init failed") "InitError"))))
