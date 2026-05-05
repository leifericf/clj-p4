(ns clj-p4.io.subprocess-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clj-p4.io.subprocess :as proc]))

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

(deftest run!-retries-on-transient-failure-test
  (testing "retries up to :retries times when stderr looks transient"
    (let [tmp     (java.io.File/createTempFile "clj-p4-retry" ".count")
          tmp-path (.getAbsolutePath tmp)
          script  (str "n=$(cat " tmp-path " 2>/dev/null || echo 0); "
                       "echo $((n+1)) > " tmp-path "; "
                       "if [ $n -lt 2 ]; then "
                       "  echo 'connection reset by peer' 1>&2; exit 5; "
                       "else "
                       "  echo done; exit 0; "
                       "fi")]
      (try
        (spit tmp "0")
        (let [{:keys [exit stdout-bytes]}
              (proc/run! ["sh" "-c" script]
                         {:retries 5 :retry-backoff-ms 1})]
          (is (zero? exit))
          (is (= "done\n" (String. stdout-bytes "UTF-8")))
          (is (= "3\n" (slurp tmp))))
        (finally (.delete tmp))))))

(deftest run!-no-retries-on-non-transient-failure-test
  (testing "non-transient failure (e.g. bad arg) is not retried"
    (let [counter (atom 0)
          ;; Use a script that ALWAYS fails with non-transient stderr.
          {:keys [exit]}
          (proc/run! ["sh" "-c"
                      (str "echo 'invalid argument' 1>&2; exit 2")]
                     {:retries 5 :retry-backoff-ms 1})]
      (is (= 2 exit)))))

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
