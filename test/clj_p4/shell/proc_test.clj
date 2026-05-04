(ns clj-p4.shell.proc-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clj-p4.shell.proc :as proc]))

(deftest run!-basic-test
  (let [{:keys [exit stdout-bytes stderr]} (proc/run! ["echo" "hello"])]
    (is (zero? exit))
    (is (= "hello\n" (String. stdout-bytes "UTF-8")))
    (is (= "" stderr))))

(deftest run!-stdin-test
  (let [{:keys [stdout-bytes]} (proc/run! ["cat"]
                                          {:stdin-bytes (.getBytes "ping" "UTF-8")})]
    (is (= "ping" (String. stdout-bytes "UTF-8")))))

(deftest run!-stderr-test
  (let [{:keys [exit stderr]} (proc/run! ["sh" "-c" "echo oops 1>&2; exit 7"])]
    (is (= 7 exit))
    (is (str/includes? stderr "oops"))))

(deftest run-checked!-throws-test
  (is (thrown? clojure.lang.ExceptionInfo
               (proc/run-checked! ["sh" "-c" "exit 3"]))))

(deftest run!-timeout-test
  (testing "kills long-running process and throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (proc/run! ["sleep" "5"] {:timeout-ms 100})))))

(deftest run!-env-test
  (let [{:keys [stdout-bytes]} (proc/run! ["sh" "-c" "echo $CLJ_P4_TEST"]
                                          {:env {"CLJ_P4_TEST" "abc"}})]
    (is (str/includes? (String. stdout-bytes "UTF-8") "abc"))))

(deftest stream!-roundtrip-test
  (let [handle (proc/stream! ["cat"])
        in     (:in handle)]
    (.write ^java.io.OutputStream in (.getBytes "line1\n" "UTF-8"))
    (.write ^java.io.OutputStream in (.getBytes "line2\n" "UTF-8"))
    (.close ^java.io.OutputStream in)
    (let [out-bytes (.readAllBytes ^java.io.InputStream (:out handle))
          {:keys [exit]} (proc/stream-close! (assoc handle :in nil))]
      (is (= "line1\nline2\n" (String. out-bytes "UTF-8")))
      (is (zero? exit)))))
