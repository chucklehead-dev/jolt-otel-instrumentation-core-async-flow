(ns jolt.aspect-packs.scenario.core-async-flow
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [otel.exporter.memory :as memory]
            [otel.sdk :as sdk]))

(defn ^{:jolt.aspects/id :core-async-flow/create
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-create [config]
  (flow/create-flow config))

(defn ^{:jolt.aspects/id :core-async-flow/start
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-start [graph]
  (flow/start graph))

(defn ^{:jolt.aspects/id :core-async-flow/pause
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-pause [graph]
  (flow/pause graph))

(defn ^{:jolt.aspects/id :core-async-flow/resume
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-resume [graph]
  (flow/resume graph))

(defn ^{:jolt.aspects/id :core-async-flow/ping
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-ping [graph timeout-ms]
  (flow/ping graph :timeout-ms timeout-ms))

(defn ^{:jolt.aspects/id :core-async-flow/inject
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-inject [graph coord messages]
  (flow/inject graph coord messages))

(defn ^{:jolt.aspects/id :core-async-flow/stop
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-stop [graph]
  (flow/stop graph))

(defn ^{:jolt.aspects/id :core-async-flow/describe
        :jolt.aspects/role :concurrency/flow-step}
  worker-describe []
  {:params {:seen "private fixture state"}
   :ins {:in "private fixture messages"}
   :workload :io})

(defn ^{:jolt.aspects/id :core-async-flow/init
        :jolt.aspects/role :concurrency/flow-step}
  worker-init [{:keys [seen]}]
  {:seen seen :count 0})

(defn ^{:jolt.aspects/id :core-async-flow/transition
        :jolt.aspects/role :concurrency/flow-step}
  worker-transition [state transition]
  (swap! (:seen state) conj [:transition transition])
  state)

(defn ^{:jolt.aspects/id :core-async-flow/transform
        :jolt.aspects/role :concurrency/flow-step}
  worker-transform [state input message]
  (swap! (:seen state) conj [:message input message])
  [(update state :count inc)
   {::flow/report [{:message message}]}])

(def worker-step
  (flow/map->step {:describe worker-describe
                   :init worker-init
                   :transition worker-transition
                   :transform worker-transform}))

(defn- await-future! [future]
  (when (= ::timed-out (deref future 5000 ::timed-out))
    (throw (ex-info "flow injection timed out" {}))))

(defn- take-with-timeout! [channel]
  (let [timeout (async/timeout 5000)
        [value selected] (async/alts!! [channel timeout] :priority true)]
    (when (identical? selected timeout)
      (throw (ex-info "flow report timed out" {})))
    value))

(defn- await-stop! [seen]
  (loop [remaining 500]
    (cond
      (some #(= [:transition ::flow/stop] %) @seen) true
      (zero? remaining) (throw (ex-info "flow stop transition timed out" {}))
      :else (do (async/<!! (async/timeout 10))
                (recur (dec remaining))))))

(defn -main [& args]
  (try
    (let [exporter (memory/multisignal-exporter)
          handle (sdk/init! {:service-name "core-async-flow-preset-smoke"
                             :exporter exporter
                             :processor :simple
                             :runtime-metrics? false
                             :logs? false
                             :bridge-logging? false})
          seen (atom [])]
      (try
        (when (= "fail" (first args))
          (throw (ex-info "intentional smoke failure" {})))
        (let [graph (observed-create
                     {:procs {:private-worker
                              {:proc (flow/process #'worker-step)
                               :args {:seen seen}}}
                      :conns []})
              {:keys [report-chan]} (observed-start graph)]
          (observed-resume graph)
          (await-future!
           (observed-inject graph [:private-worker :in] ["private-message"]))
          (when-not (= "private-message"
                       (:message (take-with-timeout! report-chan)))
            (throw (ex-info "flow changed the message" {})))
          (observed-ping graph 2000)
          (observed-pause graph)
          (observed-resume graph)
          (when-not (true? (observed-stop graph))
            (throw (ex-info "flow stop was not accepted" {})))
          (await-stop! seen)
          (let [spans (memory/spans exporter)
                names (set (map :name spans))
                expected #{"core.async.flow create"
                           "core.async.flow start"
                           "core.async.flow pause"
                           "core.async.flow resume"
                           "core.async.flow ping"
                           "core.async.flow inject"
                           "core.async.flow stop"
                           "core.async.flow step describe"
                           "core.async.flow step init"
                           "core.async.flow step transition"
                           "core.async.flow step transform"}
                serialized (pr-str spans)]
            (when-not (every? names expected)
              (throw (ex-info "flow preset missed operation spans"
                              {:expected expected :actual names})))
            (doseq [private ["private-worker" "private-message"]]
              (when (.contains serialized private)
                (throw (ex-info "flow telemetry retained a private value"
                                {:marker private}))))
            (println "CORE-ASYNC-FLOW-OTEL-PRESET OK" (count spans))))
        (finally
          (sdk/shutdown! handle))))
    (flush)
    (System/exit 0)
    (catch Throwable error
      (binding [*out* *err*]
        (println "CORE-ASYNC-FLOW-OTEL-PRESET FAILED"
                 (or (ex-message error) (str (type error))))
        (flush))
      (System/exit 1))))
