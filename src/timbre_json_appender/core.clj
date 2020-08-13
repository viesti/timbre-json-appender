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

(defn handle-vargs [log-map ?msg-fmt vargs]
  (cond
    ?msg-fmt (let [format-specifiers (count-format-specifiers ?msg-fmt)]
               (assoc log-map
                      :msg (String/format ?msg-fmt (to-array (take format-specifiers vargs)))
                      :args (apply hash-map (drop format-specifiers vargs))))
    (even? (count vargs)) (assoc log-map :args (apply hash-map vargs))
    :else (-> log-map
              (assoc :msg (first vargs))
              (assoc :args (apply hash-map (rest vargs))))))

(defn json-appender
  "Creates Timbre configuration map for JSON appender"
  ([]
   (json-appender {}))
  ([{:keys [pretty] :or {pretty false}}]
   (let [object-mapper (object-mapper {:pretty pretty})
         println-appender (taoensso.timbre/println-appender)
         fallback-logger (:fn println-appender)]
     {:enabled? true
      :async? false
      :min-level nil
      :fn (fn [{:keys [instant level ?ns-str ?file ?line ?err vargs ?msg-fmt] :as data}]
            (let [log-map (handle-vargs {:timestamp instant
                                         :level level
                                         :thread (.getName (Thread/currentThread))}
                                        ?msg-fmt
                                        vargs)
                  log-map (cond-> log-map
                            ?err (->
                                  (assoc :err (Throwable->map ?err))
                                  (assoc :ns ?ns-str)
                                  (assoc :file ?file)
                                  (assoc :line ?line)))]
              (try
                (println (json/write-value-as-string log-map object-mapper))
                (catch Throwable _
                  (fallback-logger data)))))})))

(defn install
  "Installs json-appender as the sole appender for Timbre"
  ([]
   (install :info))
  ([{:keys [level pretty] :or {level :info
                               pretty false}}]
   (timbre/set-config! {:level level
                        :appenders {:json (json-appender {:pretty pretty})}})))

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
