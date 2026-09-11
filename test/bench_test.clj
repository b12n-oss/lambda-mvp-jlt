(ns bench-test
  (:require [clojure.test :refer [deftest is run-tests]]))

(load-file "script/bench.clj")

(deftest parse-report-line-cold-sample
  (let [text (str "START RequestId: abc Version: $LATEST\n"
                  "REPORT RequestId: 3f6c1234-5678-90ab-cdef-1234567890ab\t"
                  "Duration: 16.10 ms\tBilled Duration: 937 ms\t"
                  "Memory Size: 256 MB\tMax Memory Used: 234 MB\t"
                  "Init Duration: 920.00 ms\n"
                  "END RequestId: abc")]
    (is (= {:duration-ms 16.1
            :billed-duration-ms 937.0
            :memory-size-mb 256.0
            :max-memory-used-mb 234.0
            :init-duration-ms 920.0}
           (script.bench/parse-report-line text)))))

(deftest parse-report-line-warm-sample-has-no-init-duration
  (let [text (str "REPORT RequestId: 3f6c1234-5678-90ab-cdef-1234567890ab "
                  "Duration: 4.60 ms Billed Duration: 5 ms "
                  "Memory Size: 512 MB Max Memory Used: 237 MB")]
    (is (= {:duration-ms 4.6
            :billed-duration-ms 5.0
            :memory-size-mb 512.0
            :max-memory-used-mb 237.0}
           (script.bench/parse-report-line text)))
    (is (nil? (:init-duration-ms (script.bench/parse-report-line text))))))

(deftest parse-report-line-no-report-line-returns-nil
  (is (nil? (script.bench/parse-report-line "some unrelated log line\nanother one"))))

(deftest format-table-renders-markdown-rows
  (let [table (script.bench/format-table
               [{:tier 256 :cold-init-ms 920.0 :cold-duration-ms 16.1
                 :warm-min-ms 3.2 :warm-median-ms 3.8 :warm-max-ms 4.6
                 :max-memory-used-mb 234.0}
                {:tier 512 :cold-init-ms 1339.0 :cold-duration-ms 5.1
                 :warm-min-ms 4.9 :warm-median-ms 5.0 :warm-max-ms 5.2
                 :max-memory-used-mb 237.0}])]
    (is (= (str "| Metric | 256 MB | 512 MB |\n"
                "|---|---|---|\n"
                "| Cold Init Duration | 920.0 ms | 1339.0 ms |\n"
                "| Cold Duration | 16.1 ms | 5.1 ms |\n"
                "| Warm Duration (min/median/max) | 3.2 / 3.8 / 4.6 ms | 4.9 / 5.0 / 5.2 ms |\n"
                "| Max Memory Used | 234 MB | 237 MB |")
           table))))

(deftest format-table-shows-n-a-when-cold-init-missing
  (let [table (script.bench/format-table
               [{:tier 256 :cold-init-ms nil :cold-duration-ms 16.1
                 :warm-min-ms 3.2 :warm-median-ms 3.8 :warm-max-ms 4.6
                 :max-memory-used-mb 234.0}])]
    (is (re-find #"\| Cold Init Duration \| n/a \|" table))))

(let [{:keys [fail error]} (run-tests 'bench-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
