(ns otel.instrumentation.core-async-flow-test
  (:require [clojure.test :refer [deftest is testing]]
            [otel.context :as context]
            [otel.exporter.memory :as memory]
            [otel.instrumentation.core-async-flow :as instrumentation]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(defn- point [id role]
  {:id id :advice-role role :contract :args-v1
   :library {:id 'io.github.chucklehead-dev/jolt-aspect-packs-flow-fixture
             :version instrumentation/fixture-version}})

(defn- with-memory-sdk [f]
  (let [exporter (memory/multisignal-exporter)
        handle (sdk/init! {:service-name "core-async-flow-instrumentation-test"
                           :exporter exporter
                           :processor :simple
                           :runtime-metrics? false
                           :logs? false
                           :bridge-logging? false})]
    (try (f exporter)
         (finally (sdk/shutdown! handle)))))

(deftest lifecycle-span-retains-only-bounded-structure
  (with-memory-sdk
    (fn [exporter]
      (let [result (Object.)
            config {:procs {:private-source {:token "process-secret"}
                            :private-sink {}}
                    :conns [[[:private-source :out] [:private-sink :in]]]
                    :io-exec (Object.)}
            observed (instrumentation/around-lifecycle
                      (point :core-async-flow/create
                             :concurrency/flow-lifecycle)
                      [config]
                      (fn [] result))
            [span] (memory/spans exporter)
            attrs (:attributes span)
            serialized (pr-str span)]
        (is (identical? result observed))
        (is (= "core.async.flow create" (:name span)))
        (is (= "lifecycle" (get attrs "core.async.flow.boundary")))
        (is (= "create" (get attrs "core.async.flow.operation")))
        (is (= 2 (get attrs "core.async.flow.process.count")))
        (is (= 1 (get attrs "core.async.flow.connection.count")))
        (is (= 1 (get attrs "core.async.flow.custom_executor.count")))
        (doseq [private ["private-source" "private-sink" "process-secret"]]
          (is (not (.contains serialized private))))))))

(deftest step-span-does-not-retain-state-message-or-output-values
  (with-memory-sdk
    (fn [exporter]
      (let [state (Object.)
            message (Object.)
            next-state (Object.)
            result [next-state {:private-output ["output-secret"]}]
            observed (instrumentation/around-step
                      (point :core-async-flow/transform
                             :concurrency/flow-step)
                      [state :private-input message]
                      (fn [] result))
            [span] (memory/spans exporter)
            serialized (pr-str span)]
        (is (identical? result observed))
        (is (= "step" (get (:attributes span)
                            "core.async.flow.boundary")))
        (is (= 1 (get (:attributes span)
                      "core.async.flow.output_destination.count")))
        (doseq [private [state message next-state
                         "private-input" "private-output" "output-secret"]]
          (is (not (.contains serialized (str private)))))))))

(deftest lazy-inject-source-is-not-realized
  (with-memory-sdk
    (fn [exporter]
      (let [realized (atom 0)
            messages (map (fn [x] (swap! realized inc) x) [:a :b])]
        (is (= :submitted
               (instrumentation/around-lifecycle
                (point :core-async-flow/inject :concurrency/flow-lifecycle)
                [(Object.) [:private :coord] messages]
                (constantly :submitted))))
        (is (zero? @realized))
        (is (nil? (get-in (first (memory/spans exporter))
                          [:attributes "core.async.flow.message.count"])))))))

(deftest counted-inject-source-retains-count-not-values
  (with-memory-sdk
    (fn [exporter]
      (instrumentation/around-lifecycle
       (point :core-async-flow/inject :concurrency/flow-lifecycle)
       [(Object.) [:private :coordinate] ["private-one" "private-two"]]
       (constantly :submitted))
      (let [[span] (memory/spans exporter)
            serialized (pr-str span)]
        (is (= 2 (get (:attributes span) "core.async.flow.message.count")))
        (is (not (.contains serialized "private-one")))
        (is (not (.contains serialized "private-two")))
        (is (not (.contains serialized "coordinate")))))))

(deftest remaining-lifecycle-attributes-are-bounded
  (with-memory-sdk
    (fn [exporter]
      (instrumentation/around-lifecycle
       (point :core-async-flow/start :concurrency/flow-lifecycle)
       [(Object.)]
       (constantly {:report-chan :private-report
                    :error-chan :private-error
                    :already-running true}))
      (instrumentation/around-lifecycle
       (point :core-async-flow/ping :concurrency/flow-lifecycle)
       [(Object.) 250]
       (constantly {:private-worker {:private-state "secret"}}))
      (instrumentation/around-lifecycle
       (point :core-async-flow/pause :concurrency/flow-lifecycle)
       [(Object.)]
       (constantly false))
      (instrumentation/around-lifecycle
       (point :core-async-flow/resume :concurrency/flow-lifecycle)
       [(Object.)]
       (constantly true))
      (let [by-name (into {} (map (juxt :name identity))
                          (memory/spans exporter))]
        (is (= true (get-in by-name ["core.async.flow start" :attributes
                                    "core.async.flow.already_running"])))
        (is (= 2 (get-in by-name ["core.async.flow start" :attributes
                                 "core.async.flow.channel.count"])))
        (is (= 250 (get-in by-name ["core.async.flow ping" :attributes
                                   "core.async.flow.timeout_ms"])))
        (is (= 1 (get-in by-name ["core.async.flow ping" :attributes
                                 "core.async.flow.responding_process.count"])))
        (is (= false (get-in by-name ["core.async.flow pause" :attributes
                                     "core.async.flow.accepted"])))
        (is (= true (get-in by-name ["core.async.flow resume" :attributes
                                    "core.async.flow.accepted"])))
        (is (not (.contains (pr-str by-name) "private-worker")))
        (is (not (.contains (pr-str by-name) "secret")))))))

(deftest describe-and-init-attributes-are-bounded
  (with-memory-sdk
    (fn [exporter]
      (instrumentation/around-step
       (point :core-async-flow/describe :concurrency/flow-step)
       []
       (constantly {:params {:private-param "secret"}
                    :ins {:private-input "secret"}
                    :outs {:private-output "secret"}
                    :workload :compute}))
      (instrumentation/around-step
       (point :core-async-flow/init :concurrency/flow-step)
       [{:clojure.core.async.flow/pid :private-worker
         :private-param "secret"}]
       (constantly (Object.)))
      (let [by-name (into {} (map (juxt :name identity))
                          (memory/spans exporter))
            describe (get-in by-name ["core.async.flow step describe"
                                      :attributes])
            init (get-in by-name ["core.async.flow step init" :attributes])]
        (is (= 1 (get describe "core.async.flow.parameter.count")))
        (is (= 1 (get describe "core.async.flow.input.count")))
        (is (= 1 (get describe "core.async.flow.output.count")))
        (is (= "compute" (get describe "core.async.flow.workload")))
        (is (= true (get init "core.async.flow.pid.present")))
        (is (= 1 (get init "core.async.flow.parameter.count")))
        (is (not (.contains (pr-str by-name) "private-worker")))
        (is (not (.contains (pr-str by-name) "secret")))))))

(deftest unknown-unsuppressed-operation-fails-before-target
  (let [called? (atom false)
        caught (try
                 (instrumentation/around-step
                  (point :unknown :concurrency/flow-step)
                  []
                  (fn [] (reset! called? true)))
                 (catch Throwable error error))]
    (is (false? @called?))
    (is (= :otel.instrumentation.core-async-flow/invalid-operation
           (:kind (ex-data caught))))))

(deftest transition-values-are-closed
  (with-memory-sdk
    (fn [exporter]
      (doseq [transition [:clojure.core.async.flow/pause
                          :clojure.core.async.flow/stop
                          :private/custom]]
        (instrumentation/around-step
         (point :core-async-flow/transition :concurrency/flow-step)
         [(Object.) transition]
         (constantly (Object.))))
      (is (= ["pause" "stop" "_OTHER"]
             (mapv #(get (:attributes %) "core.async.flow.transition")
                   (memory/spans exporter))))
      (is (not (.contains (pr-str (memory/spans exporter))
                          "private/custom"))))))

(deftest thrown-value-identity-is-preserved-without-message-capture
  (with-memory-sdk
    (fn [exporter]
      (let [error (ex-info "private exception message" {:secret "private-data"})
            caught (try
                     (instrumentation/around-step
                      (point :core-async-flow/transition
                             :concurrency/flow-step)
                      [(Object.) :clojure.core.async.flow/resume]
                      (fn [] (throw error)))
                     (catch Throwable thrown thrown))
            [span] (memory/spans exporter)
            serialized (pr-str span)]
        (is (identical? error caught))
        (is (= :error (get-in span [:status :code])))
        (is (= "resume" (get (:attributes span)
                             "core.async.flow.transition")))
        (is (some? (get (:attributes span) "error.type")))
        (is (not (.contains serialized "private exception message")))
        (is (not (.contains serialized "private-data")))))))

(deftest suppression-is-inert
  (with-memory-sdk
    (fn [exporter]
      (let [value (Object.)
            calls (atom 0)
            observed
            (context/with-instrumentation-suppressed
              (instrumentation/around-step
               (point :unknown :concurrency/flow-step)
               [:private]
               (fn [] (swap! calls inc) value)))]
        (is (identical? value observed))
        (is (= 1 @calls))
        (is (empty? (memory/spans exporter)))))))

(deftest wrapper-contract-scenarios-are-non-vacuous
  (with-memory-sdk
    (fn [exporter]
      (testing "suppressed unknown operations delegate once without a span"
        (let [value (Object.)
              calls (atom 0)
              observed
              (context/with-instrumentation-suppressed
                (instrumentation/around-step
                 (point :unknown :concurrency/flow-step)
                 []
                 (fn [] (swap! calls inc) value)))]
          (is (identical? value observed))
          (is (= 1 @calls))
          (is (empty? (memory/spans exporter)))))

      (testing "known returning operations delegate once and end one span"
        (let [before (count (memory/spans exporter))
              value (Object.)
              calls (atom 0)
              observed (instrumentation/around-lifecycle
                        (point :core-async-flow/stop
                               :concurrency/flow-lifecycle)
                        []
                        (fn [] (swap! calls inc) value))]
          (is (identical? value observed))
          (is (= 1 @calls))
          (is (= (inc before) (count (memory/spans exporter))))))

      (testing "known throwing operations preserve identity and end an error span"
        (let [before (count (memory/spans exporter))
              error (ex-info "private" {})
              calls (atom 0)
              caught (try
                       (instrumentation/around-lifecycle
                        (point :core-async-flow/stop
                               :concurrency/flow-lifecycle)
                        []
                        (fn [] (swap! calls inc) (throw error)))
                       (catch Throwable thrown thrown))
              spans (memory/spans exporter)
              error-span (last spans)]
          (is (identical? error caught))
          (is (= 1 @calls))
          (is (= (inc before) (count spans)))
          (is (= :error (get-in error-span [:status :code])))))

      (testing "unsuppressed unknown operations reject before target or span"
        (let [before (count (memory/spans exporter))
              calls (atom 0)
              caught (try
                       (instrumentation/around-lifecycle
                        (point :unknown :concurrency/flow-lifecycle)
                        []
                        (fn [] (swap! calls inc)))
                       (catch Throwable thrown thrown))]
          (is (= :otel.instrumentation.core-async-flow/invalid-operation
                 (:kind (ex-data caught))))
          (is (zero? @calls))
          (is (= before (count (memory/spans exporter)))))))))

(deftest suppressed-throw-preserves-identity-without-a-span
  (with-memory-sdk
    (fn [exporter]
      (let [error (ex-info "private" {})
            calls (atom 0)
            caught
            (try
              (context/with-instrumentation-suppressed
                (instrumentation/around-lifecycle
                 (point :unknown :concurrency/flow-lifecycle)
                 []
                 (fn [] (swap! calls inc) (throw error))))
              (catch Throwable thrown thrown))]
        (is (identical? error caught))
        (is (= 1 @calls))
        (is (empty? (memory/spans exporter)))))))

(deftest synchronous-parent-context-is-preserved
  (with-memory-sdk
    (fn [exporter]
      (trace/with-span [_ (sdk/tracer "flow-test-parent") "parent"]
        (instrumentation/around-lifecycle
         (point :core-async-flow/stop :concurrency/flow-lifecycle)
         [(Object.)]
         (constantly true)))
      (let [spans (memory/spans exporter)
            parent (first (filter #(= "parent" (:name %)) spans))
            child (first (filter #(= "core.async.flow stop" (:name %)) spans))]
        (is (= (get-in parent [:span-context :span-id])
               (:parent-span-id child)))))))

(deftest provider-contract-matches-flow-pack
  (is (= instrumentation/fixture-version
         (get-in instrumentation/aspect-provider
                 [:libraries
                  'io.github.chucklehead-dev/jolt-aspect-packs-flow-fixture])))
  (is (= {:concurrency/flow-lifecycle :args-v1
          :concurrency/flow-step :args-v1}
         (into {} (map (fn [[role contract]] [role (:contract contract)]))
               (:roles instrumentation/aspect-provider)))))
