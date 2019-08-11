(ns timbre-json-appender.core
  (:require [jsonista.core :as json]
            [taoensso.timbre :as timbre])
  (:import (com.fasterxml.jackson.databind SerializationFeature)))

(defn object-mapper [opts]
  (doto (json/object-mapper opts)
    (.configure SerializationFeature/FAIL_ON_EMPTY_BEANS false)))

(defn json-appender
  "Creates Timbre configuration map for JSON appender"
  ([]
   (json-appender {}))
  ([{:keys [pretty] :or {pretty false}}]
   (let [object-mapper (object-mapper {:pretty pretty})]
     {:enabled? true
      :async? false
      :min-level nil
      :fn (fn [{:keys [instant level ?ns-str ?file ?line ?err vargs]}]
            (println (json/write-value-as-string (cond-> {:timestamp instant
                                                          :level level
                                                          :thread (.getName (Thread/currentThread))}
                                                   ?err (->
                                                         (assoc :err (Throwable->map ?err))
                                                         (assoc :ns ?ns-str)
                                                         (assoc :file ?file)
                                                         (assoc :line ?line))
                                                   (even? (count vargs)) (assoc :args (apply hash-map vargs))
                                                   (odd? (count vargs)) (->
                                                                         (assoc :msg (first vargs))
                                                                         (assoc :args (apply hash-map (rest vargs)))))
                                                 object-mapper)))})))

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
