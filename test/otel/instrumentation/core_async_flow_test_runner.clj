(ns otel.instrumentation.core-async-flow-test-runner
  (:require [clojure.test :as test]
            [otel.instrumentation.core-async-flow-test]))

(defn -main [& _]
  (let [result (test/run-tests 'otel.instrumentation.core-async-flow-test)]
    (flush)
    (System/exit (if (pos? (+ (:fail result) (:error result))) 1 0))))
