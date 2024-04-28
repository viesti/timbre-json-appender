(ns timbre-json-appender.core
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [taoensso.timbre :as timbre])
  (:import (com.fasterxml.jackson.databind SerializationFeature)
           (clojure.lang ExceptionInfo)))

(set! *warn-on-reflection* true)

(def default-key-names
  "Default key names in the log map"
  {:timestamp :timestamp
   :level :level
   :thread :thread
   :hostname :hostname
   :msg :msg
   :ns :ns
   :err :err
   :file :file
   :line :line})

(defn object-mapper [opts]
  (doto (json/object-mapper opts)
    (.configure SerializationFeature/FAIL_ON_EMPTY_BEANS false)))

(defn count-format-specifiers [^String format-string]
  (let [len (.length format-string)]
    (loop [placeholders 0
           idx 0]
      (if (>= idx len)
        placeholders
        (let [placeholders (if (= \% (.charAt format-string idx))
                             (if (and (not (zero? idx))
                                      (= \% (.charAt format-string (dec idx))))
                               (dec placeholders)
                               (inc placeholders))
                             placeholders)]
          (recur placeholders (inc idx)))))))

(defn collect-vargs [vargs]
  (cond
    ;; if only two vargs are provided with types [string, map], take the map as args
    (and (= 2 (count vargs))
         (string? (first vargs))
         (map? (second vargs))) {:message (first vargs)
                                 :args (second vargs)}
    ;; if only a map is provided, take it as args
    (and (= 1 (count vargs))
         (map? (first vargs))) {:message nil
                                :args (first vargs)}
    ;; assume a  message precedes keyword-style args
    (odd? (count vargs)) {:message (first vargs)
                          :args (apply hash-map (rest vargs))}
    ;; else take the vargs as keyword-style args
    :else  {:message nil
            :args (apply hash-map vargs)}))

(defn- merge-log-map [inline-args? log-map args]
  (if inline-args?
    (merge log-map args)
    (update log-map :args merge args)))

(defn handle-vargs
  "Handles varg parsing, adding the msg and the given context to the given log map.

   If inline-args is true, then the remaining vargs are added to :args,
   otherwise they're inlined into the log-map."
  [log-map ?msg-fmt vargs inline-args? msg-key]
  (cond
    ?msg-fmt (let [format-specifiers (count-format-specifiers ?msg-fmt)
                   [message vargs] (split-at format-specifiers vargs)
                   message (String/format ?msg-fmt (to-array message))
                   args (if (and (= 1 (count vargs))
                                 (map? (first vargs)))
                          (first vargs)
                          (apply hash-map vargs))
                   log-map (assoc log-map msg-key message)]
               (merge-log-map inline-args? log-map args))
    :else (let [{:keys [message args]} (collect-vargs vargs)
                log-map (if message
                          (assoc log-map msg-key message)
                          log-map)]
            (merge-log-map inline-args? log-map args))))

(defn default-should-log-field-fn
  "Default function to determine whether to log fields.

   Logs all fields except :file :line and :ns which are only logged on error."
  [field-name {:keys [?err] :as _data}]
  (if (contains? #{:file :line :ns} field-name)
    ?err
    true))

(defn default-ex-data-field-fn
  "Default function to pre-process fields in ex-info data map. This default implementation simply passes the
  field value through. A common use case might be to strip non-Serializable values from the ex-info data map.
  While exceptions with non-Serializable fields won't prevent logging, they will prevent successful
  JSON parsing and will use the fallback logger.

  An `:ex-data-field-fn` of
  ```
  (fn [f] (when (instance? java.io.Serializable f) f))
  ```
  would replace the non-Serializable values with nils."
  [f]
  f)

(def system-newline (System/getProperty "line.separator"))

;; Taken from timbre: https://github.com/ptaoussanis/timbre/commit/057b5a4c871752957e50c3eaf667c0517d56ca9a
(defn- atomic-println
  "println that prints the string and a newline atomically"
  [x]
  (print (str x system-newline)) (flush))

(defn- process-ex-data-map [ex-data-field-fn ex]
  (if (and ex (instance? ExceptionInfo ex))
    (let [cause (process-ex-data-map ex-data-field-fn (ex-cause ex))
          new-ex (ex-info (ex-message ex)
                          (into {} (map (fn [[k v]] {k (ex-data-field-fn v)}) (ex-data ex)))
                          cause)]
      (.setStackTrace ^ExceptionInfo new-ex (.getStackTrace ^ExceptionInfo ex))
      new-ex)
    ex))

(defn make-pretty-printer
  "Creates a PrettyPrinter that produces compact output for nested arrays,
  which makes stack traces represented by Throwable->map easier to read"
  []
  (let [nesting (atom 0)
        array-nesting (atom 0)]
    (reify com.fasterxml.jackson.core.PrettyPrinter
      (writeStartObject [_ g]
        (reset! array-nesting 0)
        (.writeRaw g "{")
        (swap! nesting inc))
      (writeEndObject [_ g nrOfEntries]
        (if (zero? nrOfEntries)
          (do
            (.writeRaw g "}")
            (swap! nesting dec))
          (do
            (.writeRaw g "\n")
            (swap! nesting dec)
            (.writeRaw g (str/join (repeat @nesting " ")))
            (.writeRaw g "}"))))
      (beforeObjectEntries [_ g]
        (.writeRaw g "\n")
        (.writeRaw g (str/join (repeat @nesting " "))))
      (writeObjectFieldValueSeparator [_ g]
        (.writeRaw g ": "))
      (writeObjectEntrySeparator [_ g]
        (.writeRaw g ",\n")
        (.writeRaw g (str/join (repeat @nesting " "))))
      (writeStartArray [_ g]
        (if (pos? @array-nesting)
          (do
            (.writeRaw g "\n")
            (.writeRaw g (str/join (repeat @nesting " ")))
            (.writeRaw g "["))
          (.writeRaw g "["))
        (swap! nesting inc)
        (swap! array-nesting inc))
      (writeEndArray [_ g _nrOfValues]
        (.writeRaw g "]")
        (swap! array-nesting (fn [v]
                               (max 0 (dec v))))
        (swap! nesting dec))
      (beforeArrayValues [_ _g]
        )
      (writeArrayValueSeparator [_ g]
        (.writeRaw g ","))
      (writeRootValueSeparator [_ _g]
        ))))

(defn make-json-output-fn
  "Creates a Timbre output-fn that prints JSON strings.
  Takes the following options:

  `level-key`:           The key to use for log-level
  `msg-key`:             The key to use for the message (default :msg)
  `pretty`:              Pretty-print JSON
  `pretty-array:`        Use a PrettyPrinter that makes more compact nested arrays
  `inline-args?`:        Place arguments on top level, instead of placing behind `args` field
  `should-log-field-fn`: A function which determines whether to log the given top-level field.  Defaults to `default-should-log-field-fn`
  `ex-data-field-fn`:    A function which pre-processes fields in the ex-info data map. Useful when ex-info data map includes non-Serializable values. Defaults to `default-ex-data-field-fn`
  `key-names`:           Map of log key names. Can be used to override the default key names in `default-key-names`
  `json-error-fn:`       Function to call if JSON serialization fails. Takes a single argument, a Throwable."
  ([]
   (make-json-output-fn {}))
  ([{:keys [pretty inline-args? level-key msg-key should-log-field-fn ex-data-field-fn key-names json-error-fn pretty-array]
     :or {pretty              false
          pretty-array        false
          inline-args?        true
          should-log-field-fn default-should-log-field-fn
          ex-data-field-fn    default-ex-data-field-fn
          key-names           default-key-names
          json-error-fn       (fn [_t])}}]
   (let [key-names (merge default-key-names key-names)
         msg-key (or msg-key
                     (get key-names :msg))
         level-key (or level-key
                       (get key-names :level))
         object-mapper (let [^com.fasterxml.jackson.databind.ObjectMapper mapper (object-mapper {:pretty pretty})]
                         (when pretty
                           (if pretty-array
                             (.setDefaultPrettyPrinter mapper (make-pretty-printer))
                             (.setDefaultPrettyPrinter mapper
                                                     (doto (com.fasterxml.jackson.core.util.DefaultPrettyPrinter.)
                                                       (.indentArraysWith com.fasterxml.jackson.core.util.DefaultIndenter/SYSTEM_LINEFEED_INSTANCE)))))
                         mapper)
         data-field-processor (partial #'process-ex-data-map ex-data-field-fn)]
     (fn [{:keys [level ?ns-str ?file ?line ?err vargs ?msg-fmt hostname_ context timestamp_] :as data}]
       (let [;; apply context prior to resolving vargs so specific log values override context values
             ?err (data-field-processor ?err)
             base-log-map (cond
                            (and (not inline-args?) (seq context)) {:args context}
                            (and inline-args? (seq context)) context
                            :else {})
             log-map (-> (handle-vargs base-log-map
                                       ?msg-fmt
                                       vargs
                                       inline-args?
                                       msg-key)
                         ;; apply base fields last to ensure they have precedent over context and vargs
                         (assoc (get key-names :timestamp) (force timestamp_))
                         (assoc level-key level)
                         (cond->
                             (should-log-field-fn :thread data) (assoc (get key-names :thread) (.getName (Thread/currentThread)))
                             (should-log-field-fn :file data) (assoc (get key-names :file) ?file)
                             (should-log-field-fn :line data) (assoc (get key-names :line) ?line)
                             (should-log-field-fn :ns data) (assoc (get key-names :ns) ?ns-str)
                             (should-log-field-fn :hostname data) (assoc (get key-names :hostname) (force hostname_))
                             ?err (assoc (get key-names :err) (Throwable->map ?err))))]
         (try
           (json/write-value-as-string log-map object-mapper)
           (catch Throwable t
             (json-error-fn t)
             (timbre/default-output-fn data))))))))

(defn json-appender
  "Creates Timbre configuration map for JSON appender that prints to STDOUT"
  ([]
   (json-appender {}))
  ([{:keys [inline-args?]
     :or {inline-args? false}
     :as config}]
   {:enabled? true
    :async? false
    :min-level nil
    :output-fn (make-json-output-fn (assoc config
                                           ;; Legacy support, inline-args? defaulted to `true` in `install`, but false in `json-appender`
                                           :inline-args? inline-args?))
    :fn (fn [{:keys [output_]}]
          (atomic-println (force output_)))}))

(defn install
  "Installs json-appender as the sole appender for Timbre, options

  `level`:               Timbre log level (deprecated, prefer min-level)
  `min-level`:           Timbre log level
  `level-key`:           The key to use for log-level
  `msg-key`:             The key to use for the message (default :msg)
  `pretty`:              Pretty-print JSON
  `pretty-array:`        Use a PrettyPrinter that makes more compact nested arrays
  `inline-args?`:        Place arguments on top level, instead of placing behind `args` field
  `should-log-field-fn`: A function which determines whether to log the given top-level field.  Defaults to `default-should-log-field-fn`
  `ex-data-field-fn`:    A function which pre-processes fields in the ex-info data map. Useful when ex-info data map includes non-Serializable values. Defaults to `default-ex-data-field-fn`
  `key-names`:           Map of log key names. Can be used to override the default key names in `default-key-names`
  `json-error-fn:`       Function to call if JSON serialization fails. Takes a single argument, a Throwable."
  ([]
   (install nil))
  ([{:keys [level min-level pretty inline-args? level-key msg-key should-log-field-fn ex-data-field-fn key-names json-error-fn pretty-array]
     :or {level-key           :level
          pretty              false
          pretty-array        false
          inline-args?        true
          msg-key             :msg
          should-log-field-fn default-should-log-field-fn
          ex-data-field-fn    default-ex-data-field-fn
          key-names           default-key-names
          json-error-fn       (fn [_t])}}]
   (timbre/set-config! {:min-level (or min-level level :info)
                        :appenders {:json (json-appender {:pretty              pretty
                                                          :pretty-array        pretty-array
                                                          :inline-args?        inline-args?
                                                          :level-key           level-key
                                                          :msg-key             msg-key
                                                          :should-log-field-fn should-log-field-fn
                                                          :ex-data-field-fn    ex-data-field-fn
                                                          :key-names           key-names
                                                          :json-error-fn       json-error-fn})}
                        :timestamp-opts {:pattern "yyyy-MM-dd'T'HH:mm:ssX"}})))

(defn log-success [request-method uri status]
  (timbre/info :method request-method :uri uri :status status))

(defn log-failure [t request-method uri]
  (timbre/error t "Failed to handle request" :method request-method :uri uri))

(defn wrap-json-logging
  "Ring middleware for JSON logging. Logs :method, :uri and :status for successful handler invocations,
  :method and :uri for failed invocations."
  [handler]
  (fn
    ([{:keys [request-method  uri] :as request}]
     (try
       (let [{:keys [status] :as response} (handler request)]
         (log-success request-method uri status)
         response)
       (catch Throwable t
         (log-failure t request-method uri)
         {:status 500
          :body "Server error"})))
    ([{:keys [request-method  uri] :as request} respond raise]
     (handler request
              (fn [{:keys [status] :as response}]
                (log-success request-method uri status)
                (respond response))
              (fn [t]
                (log-failure t request-method uri)
                (raise t))))))
