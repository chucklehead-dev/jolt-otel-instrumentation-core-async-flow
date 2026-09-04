(ns otel.instrumentation.core-async-flow
  "Build-selected OpenTelemetry consumer for application-owned core.async.flow
  lifecycle wrappers and process step functions.

  The target pack keeps Jolt's vendored upstream flow implementation unchanged.
  Advice sees only the stable application boundary: lifecycle calls and the four
  step-function arities. Attributes retain bounded structure, never graph,
  process-state, coordinate, message, output, or exception values."
  (:require [otel.context :as context]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(def fixture-version
  "Version of the semantic flow join-point ABI consumed by this provider."
  "0.1.0")

(def ^:private instrumentation-version "0.1.0")
(def ^:private scope-name
  "io.github.chucklehead-dev/jolt-otel-instrumentation-core-async-flow")

(def ^:private lifecycle-names
  {:core-async-flow/create "core.async.flow create"
   :core-async-flow/start "core.async.flow start"
   :core-async-flow/pause "core.async.flow pause"
   :core-async-flow/resume "core.async.flow resume"
   :core-async-flow/ping "core.async.flow ping"
   :core-async-flow/inject "core.async.flow inject"
   :core-async-flow/stop "core.async.flow stop"})

(def ^:private step-names
  {:core-async-flow/describe "core.async.flow step describe"
   :core-async-flow/init "core.async.flow step init"
   :core-async-flow/transition "core.async.flow step transition"
   :core-async-flow/transform "core.async.flow step transform"})

(def ^:private lifecycle-operation
  {:core-async-flow/create "create"
   :core-async-flow/start "start"
   :core-async-flow/pause "pause"
   :core-async-flow/resume "resume"
   :core-async-flow/ping "ping"
   :core-async-flow/inject "inject"
   :core-async-flow/stop "stop"})

(def ^:private step-operation
  {:core-async-flow/describe "describe"
   :core-async-flow/init "init"
   :core-async-flow/transition "transition"
   :core-async-flow/transform "transform"})

(defn- invalid-operation! [kind join-point]
  (throw (ex-info "unknown core.async.flow instrumentation operation"
                  {:kind :otel.instrumentation.core-async-flow/invalid-operation
                   :boundary kind
                   :operation (:id join-point)})))

(defn- count-if-map [value]
  (when (map? value) (count value)))

(defn- count-if-counted [value]
  (when (counted? value) (count value)))

(defn- present-attributes [pairs]
  (into {} (keep (fn [[k v]] (when (some? v) [k v]))) pairs))

(defn- lifecycle-input-attributes [operation args]
  (case operation
    :core-async-flow/create
    (let [config (first args)]
      (present-attributes
       [[:core.async.flow.process.count (count-if-map (:procs config))]
        [:core.async.flow.connection.count (count-if-counted (:conns config))]
        [:core.async.flow.custom_executor.count
         (when (map? config)
           (count (filter #(some? (get config %))
                          [:mixed-exec :io-exec :compute-exec])))]]))

    :core-async-flow/ping
    (present-attributes
     [[:core.async.flow.timeout_ms
       (let [timeout-ms (second args)]
         (when (and (integer? timeout-ms) (not (neg? timeout-ms))) timeout-ms))]])

    :core-async-flow/inject
    (present-attributes
     [[:core.async.flow.message.count (count-if-counted (nth args 2 nil))]])

    {}))

(defn- lifecycle-result-attributes [operation result]
  (case operation
    :core-async-flow/start
    (when (map? result)
      {:core.async.flow.already_running (boolean (:already-running result))
       :core.async.flow.channel.count (count (dissoc result :already-running))})

    :core-async-flow/ping
    (present-attributes
     [[:core.async.flow.responding_process.count (count-if-map result)]])

    (:core-async-flow/pause :core-async-flow/resume :core-async-flow/stop)
    {:core.async.flow.accepted (boolean result)}

    {}))

(defn- safe-transition [value]
  (case value
    :clojure.core.async.flow/resume "resume"
    :clojure.core.async.flow/pause "pause"
    :clojure.core.async.flow/stop "stop"
    "_OTHER"))

(defn- step-input-attributes [operation args]
  (case operation
    :core-async-flow/init
    (let [arg-map (first args)]
      (when (map? arg-map)
        {:core.async.flow.pid.present
         (contains? arg-map :clojure.core.async.flow/pid)
         :core.async.flow.parameter.count
         (count (dissoc arg-map :clojure.core.async.flow/pid))}))

    :core-async-flow/transition
    {:core.async.flow.transition (safe-transition (second args))}

    {}))

(defn- step-result-attributes [operation result]
  (case operation
    :core-async-flow/describe
    (when (map? result)
      (present-attributes
       [[:core.async.flow.parameter.count (count-if-map (:params result))]
        [:core.async.flow.input.count (count-if-map (:ins result))]
        [:core.async.flow.output.count (count-if-map (:outs result))]
        [:core.async.flow.workload
         (case (:workload result)
           :io "io" :mixed "mixed" :compute "compute" nil)]]))

    :core-async-flow/transform
    (let [outputs (when (and (vector? result) (= 2 (count result)))
                    (second result))]
      (present-attributes
       [[:core.async.flow.output_destination.count (count-if-map outputs)]]))

    {}))

(defn- error-type [error]
  (try
    (str (type error))
    (catch :default _ "unknown")))

(defn- traced [span-name attributes result-attributes proceed]
  (let [span (trace/start-span
              (sdk/tracer scope-name {:version instrumentation-version})
              span-name
              {:kind :internal :attributes attributes})]
    (try
      (trace/with-current-span span
        (let [result (proceed)]
          (when (trace/recording? span)
            (trace/set-attributes! span (or (result-attributes result) {})))
          result))
      (catch :default error
        ;; Exception messages and objects can contain message/state values. Keep
        ;; only the host type and a constant status description.
        (trace/set-attribute! span :error.type (error-type error))
        (trace/set-status! span :error "core.async.flow operation failed")
        (throw error))
      (finally
        (trace/end! span)))))

(defn around-lifecycle
  [join-point args proceed]
  (if (context/instrumentation-suppressed?)
    (proceed)
    (let [operation (:id join-point)
          span-name (or (get lifecycle-names operation)
                        (invalid-operation! :lifecycle join-point))
          attributes (assoc (lifecycle-input-attributes operation args)
                            :core.async.flow.boundary "lifecycle"
                            :core.async.flow.operation
                            (get lifecycle-operation operation))]
      (traced span-name attributes
              #(lifecycle-result-attributes operation %)
              proceed))))

(defn around-step
  [join-point args proceed]
  (if (context/instrumentation-suppressed?)
    (proceed)
    (let [operation (:id join-point)
          span-name (or (get step-names operation)
                        (invalid-operation! :step join-point))
          attributes (assoc (step-input-attributes operation args)
                            :core.async.flow.boundary "step"
                            :core.async.flow.operation
                            (get step-operation operation))]
      (traced span-name attributes
              #(step-result-attributes operation %)
              proceed))))

(def aspect-provider
  {:schema 1
   :libraries
   {'io.github.chucklehead-dev/jolt-aspect-packs-flow-fixture fixture-version}
   :roles
   {:concurrency/flow-lifecycle
    {:fn 'otel.instrumentation.core-async-flow/around-lifecycle
     :contract :args-v1}
    :concurrency/flow-step
    {:fn 'otel.instrumentation.core-async-flow/around-step
     :contract :args-v1}}})
