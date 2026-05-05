(ns clj-p4.audit-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-p4.api :as api]
            [clj-p4.io.p4 :as p4]
            [clj-p4.audit :as audit]))

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

(deftest audit-tip-agreement-test
  (testing "audit-tip flags ok? true when git tree count + bytes match p4"
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
                       :source "//stream/main"
                       :target target}))
        (with-redefs [p4/sizes-summary (fn [_ _ & _]
                                         {:p4/file-count 1
                                          :p4/total-bytes 1})]
          (let [result (audit/audit-tip
                        {:conn   {:p4/port "h:1666"}
                         :target target
                         :source "//stream/main"})]
            (is (:ok? result))
            (is (= 100 (:change result)))
            (is (= 1 (-> result :git :git/file-count)))
            (is (= 1 (-> result :p4  :p4/file-count)))))
        (finally (rm-rf d))))))

(deftest audit-deep-agreement-test
  (testing "audit-deep! returns ok? true when every file matches"
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
                       :source "//stream/main"
                       :target target}))
        ;; The deep harness re-runs `p4 print -k` per file. Stub it to
        ;; return the same bytes the clone wrote; agreement.
        (with-redefs [p4/print-bytes! (fn [_ _ out & _]
                                        (.write ^java.io.OutputStream out
                                                file-bytes))]
          (let [result (audit/audit-deep!
                        {:conn   {:p4/port "h:1666"}
                         :target target
                         :source "//stream/main"
                         :sample :all})]
            (is (:ok? result))
            (is (= 1 (:commits-checked result)))
            (is (= 1 (:files-checked result)))))
        (finally (rm-rf d))))))

(deftest audit-deep-divergence-test
  (testing "audit-deep! returns first-divergence detail when bytes mismatch"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          orig-bytes (.getBytes "ORIGINAL" "UTF-8")
          tampered-bytes (.getBytes "TAMPERED" "UTF-8")
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
                                                orig-bytes))]
          (api/clone! {:conn   {:p4/port "h:1666"}
                       :source "//stream/main"
                       :target target}))
        ;; Now the deep harness sees DIFFERENT bytes from what was
        ;; written at clone time (simulating drift / corruption).
        (with-redefs [p4/print-bytes! (fn [_ _ out & _]
                                        (.write ^java.io.OutputStream out
                                                tampered-bytes))]
          (let [result (audit/audit-deep!
                        {:conn   {:p4/port "h:1666"}
                         :target target
                         :source "//stream/main"
                         :sample :all})]
            (is (false? (:ok? result)))
            (is (= "src/a.txt" (-> result :divergence :path)))
            (is (= 100 (-> result :divergence :change)))
            (testing "digests are exposed for triage"
              (is (string? (-> result :divergence :git-sha)))
              (is (string? (-> result :divergence :p4-sha)))
              (is (not= (-> result :divergence :git-sha)
                        (-> result :divergence :p4-sha))))))
        (finally (rm-rf d))))))

(deftest audit-deep-rejects-invalid-sample-test
  (testing ":sample must be :all or a positive int — boundary check"
    (doseq [bad [0 -1 nil 1.5]]
      (let [e (try (audit/audit-deep!
                    {:conn   {:p4/port "h:1666"}
                     :target "/tmp/clj-p4-audit-test-noop"
                     :source "//stream/main"
                     :sample bad})
                   :no-throw
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo e)
            (str ":sample " (pr-str bad) " should raise ex-info"))
        (is (= :invalid-options (:clj-p4/error (ex-data e)))
            (str ":sample " (pr-str bad)
                 " should produce :invalid-options, got "
                 (pr-str (:clj-p4/error (ex-data e)))))))))

(deftest audit-tip-disagreement-test
  (testing "audit-tip flags ok? false when sizes disagree"
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
                       :source "//stream/main"
                       :target target}))
        (with-redefs [p4/sizes-summary (fn [_ _ & _]
                                         {:p4/file-count 7    ; lies
                                          :p4/total-bytes 999})]
          (let [result (audit/audit-tip
                        {:conn   {:p4/port "h:1666"}
                         :target target
                         :source "//stream/main"})]
            (is (false? (:ok? result)))))
        (finally (rm-rf d))))))
