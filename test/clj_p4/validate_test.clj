(ns clj-p4.validate-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-p4.api :as api]
            [clj-p4.shell.p4 :as p4]
            [clj-p4.validate :as validate]))

;; See clj-p4.api-test: 0.12.0 routes streams through ephemeral
;; clients by default. Force the create to fail so the pure-data
;; fallback path runs instead.
(use-fixtures
  :each
  (fn force-create-failure [test-fn]
    (with-redefs [p4/create-stream-client!
                  (fn [& _]
                    (throw (ex-info "test stub: no client creation"
                                    {:clj-p4/error :proc-failed})))]
      (test-fn))))

(defn- tmp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-p4-validate-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

(def ^:private mainline
  {:stream/name "//stream/main" :stream/parent nil
   :stream/type :mainline :stream/options #{}
   :stream/paths [[:share "src/..."]]
   :stream/remapped [] :stream/ignored []})

(def ^:private info-2024
  {:p4/server-version-major 2024 :p4/server-version-minor 1})

(deftest validate-tip-agreement-test
  (testing "validate-tip flags ok? true when git tree count + bytes match p4"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          file-bytes (.getBytes "x" "UTF-8")
          cl {:p4/change 100 :p4/user "x" :p4/time 0 :p4/desc "one"
              :p4/stream "//stream/main"
              :p4/files [{:rev/depot "//stream/main/src/a.txt"
                          :rev/rev 1 :rev/action :add :rev/type :text
                          :rev/flags #{} :rev/keyword-flags #{}}]}]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                      p4/describe     (constantly cl)
                      p4/print-bytes! (fn [_ _ out & _]
                                        (.write ^java.io.OutputStream out
                                                file-bytes))]
          (api/clone! {:conn   {:p4/port "h:1666"}
                       :stream "//stream/main"
                       :target target}))
        (with-redefs [p4/sizes-summary (fn [_ _ & _]
                                         {:p4/file-count 1
                                          :p4/total-bytes 1})]
          (let [result (validate/validate-tip
                        {:conn   {:p4/port "h:1666"}
                         :target target
                         :source "//stream/main"})]
            (is (:ok? result))
            (is (= 100 (:change result)))
            (is (= 1 (-> result :git :git/file-count)))
            (is (= 1 (-> result :p4  :p4/file-count)))))
        (finally (rm-rf d))))))

(deftest validate-tip-disagreement-test
  (testing "validate-tip flags ok? false when sizes disagree"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          cl {:p4/change 100 :p4/user "x" :p4/time 0 :p4/desc "one"
              :p4/stream "//stream/main"
              :p4/files [{:rev/depot "//stream/main/src/a.txt"
                          :rev/rev 1 :rev/action :add :rev/type :text
                          :rev/flags #{} :rev/keyword-flags #{}}]}]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                      p4/describe     (constantly cl)
                      p4/print-bytes! (fn [_ _ out & _]
                                        (.write ^java.io.OutputStream out
                                                (.getBytes "x" "UTF-8")))]
          (api/clone! {:conn   {:p4/port "h:1666"}
                       :stream "//stream/main"
                       :target target}))
        (with-redefs [p4/sizes-summary (fn [_ _ & _]
                                         {:p4/file-count 7    ; lies
                                          :p4/total-bytes 999})]
          (let [result (validate/validate-tip
                        {:conn   {:p4/port "h:1666"}
                         :target target
                         :source "//stream/main"})]
            (is (false? (:ok? result)))))
        (finally (rm-rf d))))))
