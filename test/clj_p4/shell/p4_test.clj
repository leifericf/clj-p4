(ns clj-p4.shell.p4-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.shell.p4 :as p4]
            [clj-p4.shell.proc :as proc])
  (:import (java.io ByteArrayOutputStream
                    DataOutputStream)))

;; --- helpers: build canned p4 -G byte streams -----------------------------

(defn- write-le-u32 [^DataOutputStream out n]
  (.writeByte out (bit-and  n         0xFF))
  (.writeByte out (bit-and (bit-shift-right n 8) 0xFF))
  (.writeByte out (bit-and (bit-shift-right n 16) 0xFF))
  (.writeByte out (bit-and (bit-shift-right n 24) 0xFF)))

(defn- write-string [^DataOutputStream out s]
  (let [bs (.getBytes s "UTF-8")]
    (.writeByte out 0x73)
    (write-le-u32 out (count bs))
    (.write out bs)))

(defn- marshal-record [pairs]
  (let [baos (ByteArrayOutputStream.)
        out  (DataOutputStream. baos)]
    (.writeByte out 0x7B)        ; TYPE_DICT
    (doseq [[k v] pairs]
      (write-string out k)
      (write-string out v))
    (.writeByte out 0x30)        ; TYPE_NULL (dict end)
    (.toByteArray baos)))

(defn- marshal-records [recs]
  (let [baos (ByteArrayOutputStream.)]
    (doseq [r recs]
      (.write baos ^bytes (marshal-record r)))
    (.toByteArray baos)))

(defn- stub-checked!
  "Build a stub proc/run-checked! that captures the argv passed in and
   returns the given bytes. Use with `with-redefs`."
  [captured-argv stdout-bytes]
  (fn [argv & _]
    (reset! captured-argv argv)
    {:exit 0 :stdout-bytes stdout-bytes :stderr "" :elapsed-ms 1}))

;; --- argv assembly --------------------------------------------------------

(deftest argv-assembly-includes-conn-args-test
  (let [argv (atom nil)
        bs   (marshal-record [["serverVersion" "P4D/LINUX/2024.1/123 (2024/04/30)"]])]
    (with-redefs [proc/run-checked! (stub-checked! argv bs)]
      (p4/info {:p4/port "ssl:host:1666" :p4/user "ci" :p4/client "cw"
                :p4/charset :utf8})
      (is (= ["p4" "-G" "-p" "ssl:host:1666"
              "-u" "ci" "-c" "cw" "-C" "utf8" "info"]
             @argv)))))

(deftest argv-assembly-mj-mode-test
  (let [argv (atom nil)
        bs   (.getBytes "{\"serverVersion\":\"P4D/.../2024.1/...\"}\n" "UTF-8")]
    (with-redefs [proc/run-checked! (stub-checked! argv bs)]
      (p4/info {:p4/port "host:1666"} :mode :Mj)
      (is (= "p4"  (first @argv)))
      (is (= "-Mj" (second @argv))))))

(deftest argv-stream-spec-test
  (let [argv (atom nil)
        bs   (marshal-record [["Stream" "//stream/main"]
                              ["Type" "mainline"]
                              ["Options" ""]])]
    (with-redefs [proc/run-checked! (stub-checked! argv bs)]
      (p4/stream-spec {:p4/port "h:1666"} "//stream/main")
      (is (= ["stream" "-o" "//stream/main"]
             (->> @argv (drop 4)))))))

(deftest argv-changes-test
  (let [argv (atom nil)
        bs   (marshal-records [])]
    (with-redefs [proc/run-checked! (stub-checked! argv bs)]
      (p4/changes {:p4/port "h:1666"} "//stream/main/..."
                  :max 5)
      (is (= ["changes" "-l" "-m" "5" "//stream/main/..."]
             (->> @argv (drop 4)))))))

(deftest argv-changes-since-filter-test
  (let [argv (atom nil)]
    (with-redefs [proc/run-checked! (stub-checked! argv (marshal-records []))]
      (p4/changes {:p4/port "h:1666"} "//stream/main/..." :since 100)
      (is (= "//stream/main/...@>=100" (last @argv))))))

(deftest argv-describe-test
  (let [argv (atom nil)
        bs   (marshal-record [["change" "94312"]])]
    (with-redefs [proc/run-checked! (stub-checked! argv bs)]
      (p4/describe {:p4/port "h:1666"} 94312)
      (is (= ["describe" "-s" "94312"]
             (->> @argv (drop 4)))))))

;; --- decoded-shape passthrough -------------------------------------------

(deftest info-parses-server-version-test
  (let [bs (marshal-record [["serverVersion" "P4D/LINUX/2024.1/2596294 (2024/04/30)"]
                            ["unicode"       "enabled"]])]
    (with-redefs [proc/run-checked! (constantly {:exit 0 :stdout-bytes bs
                                                 :stderr "" :elapsed-ms 1})]
      (let [info (p4/info {:p4/port "h:1666"})]
        (is (= 2024 (:p4/server-version-major info)))
        (is (= 1    (:p4/server-version-minor info)))
        (is (true?  (:p4/unicode? info)))))))

(deftest stream-chain-detects-cycle-test
  (testing "throws ex-info on a cyclic parent chain instead of looping forever"
    (let [calls (atom 0)
          ;; A's parent is B; B's parent is A.
          records {"//s/A" (marshal-record [["Stream"  "//s/A"]
                                            ["Parent"  "//s/B"]
                                            ["Type"    "development"]
                                            ["Options" ""]])
                   "//s/B" (marshal-record [["Stream"  "//s/B"]
                                            ["Parent"  "//s/A"]
                                            ["Type"    "development"]
                                            ["Options" ""]])}
          run-stub (fn [argv & _]
                     (swap! calls inc)
                     ;; Defensive: bail out if the loop is unbounded.
                     (when (> @calls 64)
                       (throw (ex-info "test runaway: stream-chain looped > 64 calls" {})))
                     {:exit 0
                      :stdout-bytes (get records (last argv))
                      :stderr "" :elapsed-ms 1})]
      (with-redefs [proc/run-checked! run-stub]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"(?i)cycle"
             (p4/stream-chain {:p4/port "h:1666"} "//s/A")))))))

(deftest stream-chain-walks-parent-test
  (let [calls (atom 0)
        records [(marshal-record [["Stream" "//stream/leaf"]
                                  ["Parent" "//stream/middle"]
                                  ["Type"   "development"]
                                  ["Options" ""]])
                 (marshal-record [["Stream" "//stream/middle"]
                                  ["Parent" "//stream/main"]
                                  ["Type"   "development"]
                                  ["Options" ""]])
                 (marshal-record [["Stream" "//stream/main"]
                                  ["Parent" "none"]
                                  ["Type"   "mainline"]
                                  ["Options" ""]])]]
    (with-redefs [proc/run-checked! (fn [_ & _]
                                      (let [bs (nth records @calls)]
                                        (swap! calls inc)
                                        {:exit 0 :stdout-bytes bs
                                         :stderr "" :elapsed-ms 1}))]
      (let [chain (p4/stream-chain {:p4/port "h:1666"} "//stream/leaf")]
        (is (= 3 (count chain)))
        (is (= ["//stream/main" "//stream/middle" "//stream/leaf"]
               (mapv :stream/name chain)))))))

(deftest p4-available?-test
  ;; Does not assert true/false — just that the call doesn't throw, even if
  ;; p4 isn't installed in the test environment.
  (is (boolean? (p4/p4-available?))))
