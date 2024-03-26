(ns timbre-json-appender.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing] :as t]
            [timbre-json-appender.core :as sut]
            [jsonista.core :as json]
            [taoensso.timbre :as timbre]))

(timbre/set-config! {:level :info
                     :appenders {:json (sut/json-appender)}
                     :timestamp-opts {:pattern "yyyy-MM-dd'T'HH:mm:ssX"}})

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

(deftest non-string-message-and-args
  (let [log (parse-string (with-out-str (timbre/info 1 :duration 5)))]
    (is (= 1 (:msg log)))
    (is (= 5 (-> log :args :duration)))))

(deftest default-timestamp
  (let [log (parse-string (with-out-str (timbre/info 1 :duration 5)))]
    (is (re-find #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z" (:timestamp log)))))

(deftest changing-timestamp-pattern
  (let [old-config timbre/*config*]
    (try
      (timbre/merge-config! {:timestamp-opts {:pattern "yyyy-MM-dd'T'HH:mm:ss.SSSX"}})
      (let [log (parse-string (with-out-str (timbre/info 1 :duration 5)))]
        (is (re-find #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z" (:timestamp log))))
      (finally
        (timbre/set-config! old-config)))))

(deftest message-and-map
  (let [log (parse-string (with-out-str (timbre/info "Task done" {:duration 5
                                                                  :operation "123"})))]
    (is (= "Task done" (:msg log)))
    (is (= 5 (-> log :args :duration)))
    (is (= "123" (-> log :args :operation)))))

(deftest non-string-message-and-map
  (let [log (parse-string (with-out-str (timbre/info 1 {:duration 5
                                                        :operation "123"})))]
    (is (= nil (:msg log)))
    (is (= {:duration 5
            :operation "123"} (-> log :args :1)))))

(deftest only-map
  (let [log (parse-string (with-out-str (timbre/info {:duration 5
                                                      :operation "123"})))]
    (is (= nil (:msg log)))
    (is (= 5 (-> log :args :duration)))
    (is (= "123" (-> log :args :operation)))))

(deftest two-args-with-map
  (let [log (parse-string (with-out-str (timbre/info :some-context {:duration 5
                                                                    :operation "123"})))]
    (is (= nil (:msg log)))
    (is (= {:duration 5
            :operation "123"} (-> log :args :some-context)))))

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

(deftest exception-stacktrace
  (is (-> (parse-string (with-out-str (timbre/info (Exception. "poks") "Error")))
          :err
          :via
          first
          :at
          first
          (str/starts-with? "timbre_json_appender.core_test")))
  (is (-> (parse-string (with-out-str (timbre/info (ex-info "poks" {}) "Error")))
          :err
          :via
          first
          :at
          first
          (str/starts-with? "timbre_json_appender.core_test"))))

(deftest format-string
  (is (= "Hello World!" (-> (parse-string (with-out-str (timbre/infof "Hello %s!" "World")))
                            :msg)))
  (let [log (parse-string (with-out-str (timbre/infof "%s %d%% ready" "Upload" 50 :role "admin")))]
    (is (=  "Upload 50% ready"
            (:msg log)))
    (is (= {:role "admin"}
           (:args log))))
  (let [log (parse-string (with-out-str (timbre/infof "%s %d%% ready" "Upload" 50 {:role "admin"})))]
    (is (= {:role "admin"}
           (:args log)))))

(deftest context-item
  (testing "resolves context items"
    (let [log (parse-string
               (with-out-str
                 (timbre/with-context {:context-item 987}
                   (timbre/infof "%s %d%% ready" "Upload" 50 :role "admin"))))]
      (is (= {:role "admin"
              :context-item 987}
             (:args log)))))

  (testing "overrides context items with logged items"
    (let [log (parse-string
               (with-out-str
                 (timbre/with-context {:role "admin"}
                   (timbre/infof "%s %d%% ready" "Upload" 50 :role "developer"))))]
      (is (= {:role "developer"}
             (:args log)))))

  (testing "does not override base fields"
    (let [log (parse-string
               (with-out-str
                 (timbre/with-context {:thread "admin"}
                   (timbre/infof "%s %d%% ready" "Upload" 50 :thread "developer"))))]
      (is (= (.getName (Thread/currentThread))
             (:thread log)))))

  (testing "merges context with trailing map"
    (let [log (parse-string
               (with-out-str
                 (timbre/with-context {:role "admin"}
                   (timbre/infof "%s %d%% ready" "Upload" 50 {:role "developer"}))))]
      (is (= {:role "developer"}
             (:args log))))))

(deftest inline-args
  (let [inline-args-config {:level :info
                            :appenders {:json (sut/json-appender {:inline-args? true})}}]
    (testing "simple"
      (let [log (parse-string (with-out-str
                                (timbre/with-config inline-args-config
                                  (timbre/info "plop" :a 1))))]
        (is (= "plop" (:msg log)))
        (is (= 1 (:a log)))))

    (testing "with format"
      (let [log (parse-string (with-out-str
                                (timbre/with-config inline-args-config
                                  (timbre/infof "count: %d" 1 :a 1))))]
        (is (= "count: 1" (:msg log)))
        (is (= 1 (:a log)))))

    (testing "no args"
      (let [log (parse-string (with-out-str
                                (timbre/with-config inline-args-config
                                  (timbre/info "test"))))]
        (is (= "test" (:msg log)))
        (is (= #{:timestamp :level :thread :hostname :msg} (set (keys log))))))

    (testing "only args"
      (let [log (parse-string (with-out-str
                                (timbre/with-config inline-args-config
                                  (timbre/info :a 1))))]
        (is (= 1 (:a log)))
        (is (= #{:timestamp :level :thread :hostname :a} (set (keys log))))))

    (testing "does not overrride base fields"
      (let [log (parse-string (with-out-str
                                (timbre/with-config inline-args-config
                                  (timbre/info :thread "some-thread"))))]
        (is (= (.getName (Thread/currentThread))
               (:thread log)))))

    (testing "with context"
      (let [log (parse-string (with-out-str
                                (timbre/with-config inline-args-config
                                  (timbre/with-context {:test-context 123}
                                    (timbre/info :a 1)))))]
        (is (= 123 (:test-context log)))))))

(deftest ex-data-field-fn
  (testing "default function passes all values as they are"
    (let [log (parse-string (with-out-str
                              (timbre/with-config {:level :info
                                                   :appenders {:json (sut/json-appender)}}
                                (timbre/info (ex-info "plop" {:user-id 1 :name "alice"})))))]
      (is (= {:user-id 1 :name "alice"} (get-in log [:err :data])))))

  (testing "provided function processes values in data map"
    (let [log (parse-string (with-out-str
                              (timbre/with-config {:level :info
                                                   :appenders {:json (sut/json-appender {:ex-data-field-fn #(when (number? %) %)})}}
                                (timbre/info (ex-info "plop" {:user-id 1 :name "alice"})))))]
      (is (= {:user-id 1 :name nil}
             (get-in log [:err :data])))
      (is (= "clojure.lang.ExceptionInfo"
             (-> log :err :via first :type))
          "Type of exception when ex-info is thrown")))
  (testing "keep type of exception"
    (let [log (parse-string (with-out-str
                              (timbre/with-config {:level :info
                                                   :appenders {:json (sut/json-appender {:ex-data-field-fn (fn [f] (when (instance? java.io.Serializable f) f))})}}
                                (timbre/info (Exception. "boom")))))]
      (is (= "java.lang.Exception"
             (-> log :err :via first :type))
          "Type of exception when exception that is not clojure.lang.ExceptionInfo is thrown")))
  (testing "nested exceptions"
    (let [log (parse-string (with-out-str
                              (timbre/with-config {:level :info
                                                   :appenders {:json (sut/json-appender {:ex-data-field-fn (fn [f]
                                                                                                             (if (instance? java.io.Serializable f)
                                                                                                               f
                                                                                                               :non-json-serializable-exception-data))})}}
                                (timbre/info (ex-info "boom1" {:object (Object.)} (Exception. "boom2"))))))]
      (is (= "clojure.lang.ExceptionInfo"
             (-> log :err :via first :type)))
      (is (= "java.lang.Exception"
             (-> log :err :via second :type)))
      (is (= {:object "non-json-serializable-exception-data"}
             (-> log :err :via first :data))))))

(deftest should-log-field-fn
  (testing "default function omits fields on non-errors"
    (let [log (parse-string (with-out-str
                              (timbre/with-config {:level :info
                                                   :appenders {:json (sut/json-appender)}}
                                (timbre/info "plop" :a 1))))]
      (is (= #{:args :msg :hostname :level :thread :timestamp} (set (keys log))))))

  (testing "default function logs fields on errors"
    (let [log (parse-string (with-out-str
                              (timbre/with-config {:level :info
                                                   :appenders {:json (sut/json-appender)}}
                                (timbre/error (ex-info "poks" {:a (Object.)}) "plop" :a 1))))]
      (is (= #{:args :msg :hostname :level :thread :timestamp :err :ns :file :line} (set (keys log))))))

  (testing "does not support filtering level or timestamp"
    (let [log (parse-string (with-out-str
                              (timbre/with-config {:level :info
                                                   :appenders {:json (sut/json-appender {:should-log-field-fn (constantly false)})}}
                                (timbre/info "plop" :a 1))))]
      (is (contains? (set (keys log)) :timestamp))
      (is (contains? (set (keys log)) :level))))

  (doseq [field-filter [:thread :file :hostname :line :ns]]
    (testing (str "filtering " filter)
      (let [log (parse-string (with-out-str
                                (timbre/with-config {:level :info
                                                     :appenders {:json (sut/json-appender {:should-log-field-fn (fn [field-name data]
                                                                                                                  (if (= field-name field-filter)
                                                                                                                    false
                                                                                                                    (sut/default-should-log-field-fn field-name data)))})}}
                                  (timbre/info "plop" :a 1))))]
        (is (nil? (get log field-filter)))))))

(deftest level-key-changes
  (let [level-key-diff {:level :info :appenders {:json (sut/json-appender {})}}]
    (testing "test key for info"
      (let [log (parse-string (with-out-str (timbre/with-config level-key-diff (timbre/info "test"))))]
        (is (= "info" (:level log)))))
    (testing "test key for info"
      (let [log (parse-string (with-out-str (timbre/with-config level-key-diff (timbre/warn "test"))))]
        (is (= "warn" (:level log))))))
  (let [level-key-diff {:level :info :appenders {:json (sut/json-appender {:level-key :severity})}}]
    (testing "test key for info"
      (let [log (parse-string (with-out-str (timbre/with-config level-key-diff (timbre/info "test"))))]
        (is (= "info" (:severity log)))))
    (testing "test key for info"
      (let [log (parse-string (with-out-str (timbre/with-config level-key-diff (timbre/warn "test"))))]
        (is (= "warn" (:severity log)))))))

(deftest msg-key-changes
  (let [msg-key-diff {:level     :info
                      :appenders {:json (sut/json-appender {:msg-key :message})}}]
    (testing "with different msg-key"
      (let [log (parse-string (with-out-str (timbre/with-config msg-key-diff (timbre/info "test"))))]
        (is (= "test" (:message log))))
      (let [log (parse-string (with-out-str (timbre/with-config msg-key-diff (timbre/warn "test"))))]
        (is (= "test" (:message log)))))))

(deftest output-fn
  (let [log (parse-string (timbre/with-config (assoc timbre/default-config
                                                     :output-fn (sut/make-json-output-fn))
                            (with-out-str (timbre/info "test"))))]
    (is (= "test" (:msg log))))
  (testing "json-error-fn"
    (with-redefs [jsonista.core/write-value-as-string (fn [& args] (throw (Exception. "poks")))]
      (let [log (timbre/with-config (assoc timbre/default-config
                                           :output-fn (sut/make-json-output-fn {:json-error-fn (fn [_t] (println "something failed"))}))
                  (with-out-str (timbre/info "test")))]
        (is (.contains log "something failed"))))))

(deftest key-names
  (let [log (json/read-value (timbre/with-config (assoc timbre/default-config
                                                        :output-fn (sut/make-json-output-fn {:key-names {:msg "@message"
                                                                                                         :thread "@thread_name"}}))
                               (with-out-str (timbre/info "test"))))]
    (is (= "test" (get log "@message")))
    (is (contains? (set (keys log)) "@thread_name"))))

(deftest install
  (testing "install custom should-log-field-fn"
    (let [old-config timbre/*config*]
      (try
        (sut/install {:should-log-field-fn (fn [field-name _data]
                                             ;; Excludes :thread field from log
                                             (not= :thread field-name))})
        (let [log (parse-string (with-out-str
                                  (timbre/info "test")))]
          (is (not (contains? log :thread))))
        (finally
          (timbre/set-config! old-config))))))
