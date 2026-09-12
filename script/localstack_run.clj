(ns script.localstack-run
  "`jolt localstack:start|stop|status|demo`: run the whole lambda-mvp-jlt
   lifecycle against a LocalStack container instead of a real AWS account.
   The lifecycle itself is not re-implemented here -- start brings up
   LocalStack, then everything else runs this repo's own tasks with
   LAMBDA_ENDPOINT_URL exported, so deploy/invoke/teardown/bench all hit the
   emulator through the same code path they use for real AWS."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(load-file "script/localstack.clj")

;; bb script/x.clj does not put the project root on the classpath, so the
;; helper ns is loaded via load-file and referenced fully qualified (same
;; pattern as script/bench_run.clj).

(defn- sh [& args]
  (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true} args)]
    {:exit exit :out out :err err}))

(defn- die! [& msg]
  (binding [*out* *err*] (apply println "lambda-mvp-jlt:" msg))
  (System/exit 1))

(defn container-running? []
  (let [{:keys [exit out]}
        (sh "docker" "inspect" "-f" "{{.State.Running}}" script.localstack/container-name)]
    (and (zero? exit)
         (= "true" (str/trim out)))))

(defn container-exists? []
  (zero? (:exit (sh "docker" "inspect" script.localstack/container-name))))

(defn ready?
  "LocalStack answers once its edge service is up (Service endpoint healthy).
   Dummy credentials are required -- the aws CLI refuses to even send an
   unsigned request without some credentials configured."
  []
  (let [{:keys [exit out]}
        (p/shell {:out :string :err :string :continue true
                  :extra-env (script.localstack/credentials-env
                              {} script.localstack/default-endpoint)}
                 "aws" "--endpoint-url" script.localstack/default-endpoint
                 "sts" "get-caller-identity" "--output" "json"
                 "--no-cli-pager")]
    (and (zero? exit)
         (boolean (seq out)))))

(defn wait-until-ready!
  "Poll the edge endpoint until it answers or n tries elapse."
  ([] (wait-until-ready! 30))
  ([tries]
   (loop [n 0]
     (cond (ready?) :ready
           (>= n tries) (die! "LocalStack not ready after" tries "polls -- see"
                              (str "docker logs " script.localstack/container-name))
           :else (do (print ".") (flush) (Thread/sleep 1000) (recur (inc n)))))))

(defn start! []
  (if (container-running?)
    (do (println "lambda-mvp-jlt:" script.localstack/container-name "already running")
        (print "lambda-mvp-jlt: waiting for the edge service")
        (wait-until-ready!)
        (println)
        (println "lambda-mvp-jlt: LocalStack ready at" script.localstack/default-endpoint))
    (do
      (when (container-exists?)
        (println "lambda-mvp-jlt: removing stopped container" script.localstack/container-name)
        (sh "docker" "rm" script.localstack/container-name))
      (println "lambda-mvp-jlt: starting LocalStack"
               (str "(" script.localstack/image ", http://localhost:4566)"))
      (let [{:keys [exit err]} (apply p/shell {:continue true} (script.localstack/docker-run-argv))]
        (when-not (zero? exit) (die! "docker run failed:" err)))
      (print "lambda-mvp-jlt: waiting for the edge service")
      (wait-until-ready!)
      (println)
      (println "lambda-mvp-jlt: LocalStack ready at" script.localstack/default-endpoint
               "-- LAMBDA_ENDPOINT_URL=" script.localstack/default-endpoint
               "redirects all aws calls in this repo to it"))))

(defn stop! []
  (cond
    (container-running?)
    (do (println "lambda-mvp-jlt: stopping" script.localstack/container-name)
        (let [{:keys [exit err]} (sh "docker" "stop" script.localstack/container-name)]
          (when-not (zero? exit) (die! "docker stop failed:" err)))
        (sh "docker" "rm" script.localstack/container-name)
        (println "lambda-mvp-jlt: stopped and removed"))

    (container-exists?)
    (do (sh "docker" "rm" script.localstack/container-name)
        (println "lambda-mvp-jlt: removed stopped container"))

    :else (println "lambda-mvp-jlt: nothing to stop")))

(defn status! []
  (cond
    (container-running?)
    (println "lambda-mvp-jlt:" script.localstack/container-name "running at"
             script.localstack/default-endpoint
             (if (ready?) "(edge healthy)" "(edge NOT answering yet)"))

    (container-exists?)
    (println "lambda-mvp-jlt:" script.localstack/container-name "exists but stopped")

    :else (println "lambda-mvp-jlt: no" script.localstack/container-name "container")))

(defn- run-task! [task & args]
  (apply p/shell {:extra-env (script.localstack/demo-env) :continue true}
         "bb" task (remove nil? args)))

(defn demo! []
  (start!)
  (let [{:keys [exit]} (run-task! "deploy")]
    (when-not (zero? exit) (die! "deploy failed")))
  (run-task! "invoke")
  (println "\nlambda-mvp-jlt: done. `jolt localstack:stop` removes the container."))

(defn -main [& args]
  (case (first args)
    "start" (start!)
    "stop" (stop!)
    "status" (status!)
    "demo" (demo!)
    "invoke" (run-task! "invoke")
    "deploy" (run-task! "deploy")
    "teardown" (run-task! "teardown")
    (die! "usage: localstack_run.clj start|stop|status|demo|deploy|invoke|teardown")))

(apply -main *command-line-args*)
