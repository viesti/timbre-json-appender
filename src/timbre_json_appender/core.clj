(ns timbre-json-appender.core
  (:require [jsonista.core :as json]
            [taoensso.timbre :as timbre])
  (:import (com.fasterxml.jackson.databind SerializationFeature)))

(defn object-mapper [opts]
  (doto (json/object-mapper opts)
    (.configure SerializationFeature/FAIL_ON_EMPTY_BEANS false)))

(defn count-format-specifiers [format-string]
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

(defn inline-args [log-map args]
  (reduce (fn [acc [k v]]
            (assoc acc k v))
          log-map
          (partition 2 args)))

(defn has-message? [vargs]
  (odd? (count vargs)))

(defn handle-vargs [log-map ?msg-fmt vargs inline-args?]
  (cond
    ?msg-fmt (let [format-specifiers (count-format-specifiers ?msg-fmt)
                   log-map (assoc log-map :msg (String/format ?msg-fmt (to-array (take format-specifiers vargs))))]
               (if inline-args?
                 (inline-args log-map (drop format-specifiers vargs))
                 (assoc log-map :args (apply hash-map (seq (drop format-specifiers vargs))))))
    :else (let [message-found (has-message? vargs)
                log-map (if message-found
                          (assoc log-map :msg (first vargs))
                          log-map)]
            (if inline-args?
              (inline-args log-map (if message-found
                                     (rest vargs)
                                     vargs))
              (assoc log-map :args (apply hash-map (if message-found
                                                     (rest vargs)
                                                     vargs)))))))

(defn json-appender
  "Creates Timbre configuration map for JSON appender"
  ([]
   (json-appender {}))
  ([{:keys [pretty inline-args?] :or {pretty false inline-args? false}}]
   (let [object-mapper (object-mapper {:pretty pretty})
         println-appender (taoensso.timbre/println-appender)
         fallback-logger (:fn println-appender)]
     {:enabled? true
      :async? false
      :min-level nil
      :fn (fn [{:keys [instant level ?ns-str ?file ?line ?err vargs ?msg-fmt context] :as data}]
            (let [log-map (handle-vargs {:timestamp instant
                                         :level level
                                         :thread (.getName (Thread/currentThread))}
                                        ?msg-fmt
                                        vargs
                                        inline-args?)
                  log-map (cond-> log-map
                            ?err (->
                                  (assoc :err (Throwable->map ?err))
                                  (assoc :ns ?ns-str)
                                  (assoc :file ?file)
                                  (assoc :line ?line))
                            (and context inline-args?) 
                            (merge context)
                            (and context (not inline-args?))
                            (update :args merge context))]
              (try
                (println (json/write-value-as-string log-map object-mapper))
                (catch Throwable _
                  (fallback-logger data)))))})))

(defn install
  "Installs json-appender as the sole appender for Timbre, options

  `level`:       Timbre log level
  `pretty`:      Pretty-print JSON
  `inline-args?` Place arguments on top level, instead of placing behing `args` field"
  ([]
   (install :info))
  ([{:keys [level pretty inline-args?] :or {level :info
                                            pretty false
                                            inline-args? true}}]
   (timbre/set-config! {:level level
                        :appenders {:json (json-appender {:pretty pretty :inline-args? inline-args?})}})))

(defn log-success [request-method uri status]
  (timbre/info :method request-method :uri uri :status status))

(defn log-failure [t request-method uri]
  (timbre/error t "Failed to handle request" :method request-method :uri uri))

(defn wrap-json-logging
  "Ring middleware for JSON logging. Logs :method, :uri and :status for successfull handler invocations,
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
