(ns timbre-json-appender.core-test
  (:require [clojure.test :refer [deftest is testing] :as t]
            [timbre-json-appender.core :as sut]
            [jsonista.core :as json]
            [taoensso.timbre :as timbre]))

(timbre/set-config! {:level :info
                     :appenders {:json (sut/json-appender)}})

(def object-mapper (json/object-mapper {:decode-key-fn true}))

(defn parse-string [str]
  (json/read-value str object-mapper))

(deftest only-message
  (is (= "Hello" (:msg (parse-string (with-out-str (timbre/info "Hello")))))))

(deftest only-args
  (let [log (parse-string (with-out-str (timbre/info :status 200 :duration 5)))]
    (is (= 200 (-> log :args :status)))
    (is (= 5 (-> log :args :duration)))))

(deftest message-and-args
  (let [log (parse-string (with-out-str (timbre/info "Task done" :duration 5)))]
    (is (= "Task done" (:msg log)))
    (is (= 5 (-> log :args :duration)))))

(deftest unserializable-value
  (testing "in a field"
    (is (= {} (-> (parse-string (with-out-str (timbre/info :a (Object.))))
                  :args
                  :a))))
  (testing "in ExceptionInfo"
    (is (= {} (-> (parse-string (with-out-str (timbre/info (ex-info "poks" {:a (Object.)}))))
                  :err
                  :data
                  :a)))))

(deftest exception
  (is (= "poks" (-> (parse-string (with-out-str (timbre/info (Exception. "poks") "Error")))
                    :err
                    :cause))))

(deftest format-string
  (is (= "Hello World!" (-> (parse-string (with-out-str (timbre/infof "Hello %s!" "World")))
                            :msg))))
