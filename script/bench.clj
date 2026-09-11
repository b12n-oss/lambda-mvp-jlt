(ns script.bench
  "Pure parsing/formatting helpers for the jolt bench task. No AWS I/O here --
  see script/bench_run.clj for the orchestration that calls these."
  (:require [clojure.string :as str]))

(def ^:private report-line-pattern
  (re-pattern (str "REPORT RequestId: \\S+\\s+"
                   "Duration: ([0-9.]+) ms\\s+"
                   "Billed Duration: ([0-9.]+) ms\\s+"
                   "Memory Size: ([0-9.]+) MB\\s+"
                   "Max Memory Used: ([0-9.]+) MB"
                   "(?:\\s+Init Duration: ([0-9.]+) ms)?")))

(defn parse-report-line
  "Parse a block of CloudWatch log text for its REPORT line, returning a map
  of numeric fields (ms/MB). Returns nil if no REPORT line is present.
  :init-duration-ms is present only on a cold-start sample. The pattern is a
  single ordered regex over the REPORT line's fixed field order (RequestId,
  Duration, Billed Duration, Memory Size, Max Memory Used, [Init Duration])
  rather than one regex per label -- searching for the bare label \"Duration\"
  on its own would also match inside \"Billed Duration\"/\"Init Duration\"."
  [text]
  (when-let [line (->> (str/split-lines text)
                       (filter #(str/starts-with? % "REPORT "))
                       first)]
    (when-let [[_ duration billed memsize maxused init] (re-find report-line-pattern line)]
      (cond-> {:duration-ms (Double/parseDouble duration)
               :billed-duration-ms (Double/parseDouble billed)
               :memory-size-mb (Double/parseDouble memsize)
               :max-memory-used-mb (Double/parseDouble maxused)}
        init (assoc :init-duration-ms (Double/parseDouble init))))))

(defn format-table
  "Render per-memory-tier bench results as a markdown table, one column per
  tier in `results` (a vector of maps shaped like parse-report-line's output
  plus :tier and the aggregated :warm-min-ms/:warm-median-ms/:warm-max-ms
  keys -- see script/bench_run.clj)."
  [results]
  (let [header (str "| Metric | " (str/join " | " (map #(str (:tier %) " MB") results)) " |")
        sep (str "|---|" (str/join "" (repeat (count results) "---|")))
        row (fn [label f] (str "| " label " | " (str/join " | " (map f results)) " |"))]
    (str/join "\n"
              [header sep
               (row "Cold Init Duration" #(if (:cold-init-ms %) (format "%.1f ms" (:cold-init-ms %)) "n/a"))
               (row "Cold Duration" #(format "%.1f ms" (:cold-duration-ms %)))
               (row "Warm Duration (min/median/max)"
                    #(format "%.1f / %.1f / %.1f ms" (:warm-min-ms %) (:warm-median-ms %) (:warm-max-ms %)))
               (row "Max Memory Used" #(format "%.0f MB" (:max-memory-used-mb %)))])))
