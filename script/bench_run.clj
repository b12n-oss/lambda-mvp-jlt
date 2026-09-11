(ns script.bench-run
  "Orchestrates `jolt bench`: for each memory tier, force a fresh execution
  environment (any config update does this), measure one cold sample, then
  BENCH_WARM_SAMPLES back-to-back warm samples, and print a comparison
  table. See docs/guide/cold-warm-boot.md.")

(load-file "script/bench.clj")

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def function-name (or (System/getenv "LAMBDA_MVP_FUNCTION_NAME") "lambda-mvp-jlt"))

(def memory-tiers
  (mapv #(Integer/parseInt (str/trim %))
        (str/split (or (System/getenv "BENCH_MEMORY_TIERS") "2048,3008") #",")))

(def warm-samples
  (Integer/parseInt (or (System/getenv "BENCH_WARM_SAMPLES") "5")))

(defn- sh [& args]
  (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true} args)]
    {:exit exit :out out :err err}))

(defn- die! [& msg]
  (binding [*out* *err*] (apply println "lambda-mvp-jlt:" msg))
  (System/exit 1))

(defn- require-aws-identity!
  "Fail fast with a clear message if the aws CLI has no usable
  credentials/region, rather than letting a later call fail obscurely."
  []
  (let [{:keys [exit err]} (sh "aws" "sts" "get-caller-identity" "--output" "json")]
    (when-not (zero? exit)
      (die! "aws CLI has no usable credentials/region."
            "Set AWS_PROFILE/AWS_REGION or run `aws configure`, then retry.\n"
            (str/trim (or err ""))))))

(defn- set-memory! [tier]
  (let [{:keys [exit err]} (sh "aws" "lambda" "update-function-configuration"
                               "--function-name" function-name
                               "--memory-size" (str tier))]
    (when-not (zero? exit) (die! "update-function-configuration failed for" tier "MB:" err))
    (let [{:keys [exit err]} (sh "aws" "lambda" "wait" "function-updated" "--function-name" function-name)]
      (when-not (zero? exit) (die! "function did not reach Active state after resizing to" tier "MB:" err)))))

(defn- invoke-sample! []
  (let [out-file (str (System/getProperty "java.io.tmpdir")
                      "/lambda-mvp-jlt-bench-" (System/nanoTime) ".json")
        {:keys [exit out err]}
        (sh "aws" "lambda" "invoke"
            "--function-name" function-name
            "--payload" "{}"
            "--cli-binary-format" "raw-in-base64-out"
            "--log-type" "Tail"
            "--output" "json"
            out-file)]
    (when-not (zero? exit) (die! "invoke failed:" err))
    (let [response (json/parse-string out true)
          body (slurp out-file)]
      (.delete (java.io.File. out-file))
      (when (:FunctionError response)
        (die! "function invocation failed (FunctionError:" (:FunctionError response) "):" body))
      (script.bench/parse-report-line
       (String. (.decode (java.util.Base64/getDecoder) (:LogResult response)))))))

(defn- bench-tier [tier]
  (println "lambda-mvp-jlt: benchmarking" tier "MB...")
  (set-memory! tier)
  (let [cold (invoke-sample!)]
    (when-not cold
      (die! "no REPORT line parsed for the" tier "MB cold sample"))
    (when-not (:init-duration-ms cold)
      (println "lambda-mvp-jlt: WARNING -- cold sample for" tier
               "MB has no Init Duration; the execution environment may not"
               "have been fresh (see docs/guide/cold-warm-boot.md)"))
    (let [warm (mapv (fn [_] (invoke-sample!)) (range warm-samples))]
      (when (some nil? warm)
        (die! "a warm sample for" tier "MB produced no parseable REPORT line"
              "(likely a transient invoke or log-delivery issue) -- rerun jolt bench"))
      (when (some :init-duration-ms warm)
        (println "lambda-mvp-jlt: WARNING -- a 'warm' sample for" tier
                 "MB unexpectedly showed Init Duration; the execution"
                 "environment may have been recycled mid-run (see"
                 "docs/guide/cold-warm-boot.md)"))
      (let [durations (sort (mapv :duration-ms warm))]
        {:tier tier
         :cold-init-ms (:init-duration-ms cold)
         :cold-duration-ms (:duration-ms cold)
         :warm-min-ms (first durations)
         :warm-median-ms (nth durations (quot (count durations) 2))
         :warm-max-ms (last durations)
         :max-memory-used-mb (:max-memory-used-mb cold)}))))

(defn run-bench! []
  (require-aws-identity!)
  (let [results (mapv bench-tier memory-tiers)]
    (println)
    (println (script.bench/format-table results))))

(run-bench!)
