(ns script.bench-run
  "Orchestrates `bb bench`: for each memory tier, force a fresh execution
  environment (any config update does this), measure one cold sample, then
  BENCH_WARM_SAMPLES back-to-back warm samples, and print a comparison
  table. See docs/guide/cold-warm-boot.md.")

(load-file "script/bench.clj")

(require '[babashka.process :as p]
         '[clojure.string :as str])

(def function-name (or (System/getenv "LAMBDA_MVP_FUNCTION_NAME") "lambda-mvp-jlt"))

(def memory-tiers
  (mapv #(Integer/parseInt (str/trim %))
        (str/split (or (System/getenv "BENCH_MEMORY_TIERS") "2048,3072,4096") #",")))

(def warm-samples
  (Integer/parseInt (or (System/getenv "BENCH_WARM_SAMPLES") "5")))

(defn- sh [& args]
  (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true} args)]
    {:exit exit :out out :err err}))

(defn- die! [& msg]
  (binding [*out* *err*] (apply println "lambda-mvp-jlt:" msg))
  (System/exit 1))

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
            "--query" "LogResult"
            "--output" "text"
            out-file)]
    (when-not (zero? exit) (die! "invoke failed:" err))
    (let [log-text (String. (.decode (java.util.Base64/getDecoder) (str/trim out)))]
      (.delete (java.io.File. out-file))
      (script.bench/parse-report-line log-text))))

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
              "(likely a transient invoke or log-delivery issue) -- rerun bb bench"))
      (let [durations (sort (mapv :duration-ms warm))]
        {:tier tier
         :cold-init-ms (:init-duration-ms cold)
         :cold-duration-ms (:duration-ms cold)
         :warm-min-ms (first durations)
         :warm-median-ms (nth durations (quot (count durations) 2))
         :warm-max-ms (last durations)
         :max-memory-used-mb (:max-memory-used-mb cold)}))))

(defn run-bench! []
  (let [results (mapv bench-tier memory-tiers)]
    (println)
    (println (script.bench/format-table results))))

(run-bench!)
