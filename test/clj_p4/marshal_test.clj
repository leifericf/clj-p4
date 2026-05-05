(ns clj-p4.marshal-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.marshal :as m])
  (:import (java.io ByteArrayInputStream
                    ByteArrayOutputStream
                    DataOutputStream)))

;; --- helpers to build marshal byte arrays ----------------------------------

(defn- write-le-uint32 [^DataOutputStream out n]
  (.writeByte out (bit-and  n         0xFF))
  (.writeByte out (bit-and (bit-shift-right n 8) 0xFF))
  (.writeByte out (bit-and (bit-shift-right n 16) 0xFF))
  (.writeByte out (bit-and (bit-shift-right n 24) 0xFF)))

(defn- write-string [^DataOutputStream out s]
  (let [bytes (.getBytes s "UTF-8")]
    (.writeByte out 0x73)
    (write-le-uint32 out (count bytes))
    (.write out bytes)))

(defn- write-int [^DataOutputStream out n]
  (.writeByte out 0x69)
  (write-le-uint32 out n))

(defn- write-dict-start [^DataOutputStream out]
  (.writeByte out 0x7B))

(defn- write-dict-end [^DataOutputStream out]
  (.writeByte out 0x30))

(defn- build-record [pairs]
  (let [baos (ByteArrayOutputStream.)
        out  (DataOutputStream. baos)]
    (write-dict-start out)
    (doseq [[k v] pairs]
      (write-string out k)
      (cond
        (string? v)  (write-string out v)
        (integer? v) (write-int out v)
        :else        (throw (ex-info "unsupported value type" {:v v}))))
    (write-dict-end out)
    (.toByteArray baos)))

(defn- build-records [records]
  (let [baos (ByteArrayOutputStream.)]
    (doseq [r records]
      (.write baos ^bytes (build-record r)))
    (.toByteArray baos)))

;; --- tests -----------------------------------------------------------------

(deftest decode-empty-dict-test
  (let [bs (build-record [])
        records (m/decode-marshal-records (ByteArrayInputStream. bs))]
    (is (= [{}] (vec records)))))

(deftest decode-string-string-test
  (let [bs (build-record [["change" "94312"] ["user" "bob"]])
        [r] (vec (m/decode-marshal-records (ByteArrayInputStream. bs)))]
    (is (= "94312" (get r "change")))
    (is (= "bob"   (get r "user")))))

(deftest decode-string-int-test
  (let [bs (build-record [["count" 42] ["change" "100"]])
        [r] (vec (m/decode-marshal-records (ByteArrayInputStream. bs)))]
    (is (= 42 (get r "count")))
    (is (= "100" (get r "change")))))

(deftest decode-multi-record-test
  (let [bs (build-records [[["change" "100"] ["user" "alice"]]
                           [["change" "101"] ["user" "bob"]]])
        records (vec (m/decode-marshal-records (ByteArrayInputStream. bs)))]
    (is (= 2 (count records)))
    (is (= "alice" (get-in records [0 "user"])))
    (is (= "bob"   (get-in records [1 "user"])))))

(deftest decode-empty-stream-test
  (is (empty? (m/decode-marshal-records (ByteArrayInputStream. (byte-array 0))))))

(deftest decode-large-string-test
  (let [big (apply str (repeat 100000 \x))
        bs  (build-record [["data" big]])
        [r] (vec (m/decode-marshal-records (ByteArrayInputStream. bs)))]
    (is (= 100000 (count (get r "data"))))))

(deftest decode-utf8-test
  (let [bs (build-record [["msg" "héllo"]])
        [r] (vec (m/decode-marshal-records (ByteArrayInputStream. bs)))]
    (is (= "héllo" (get r "msg")))))

(deftest decode-json-records-test
  (let [json-bytes (.getBytes "{\"change\":\"100\",\"user\":\"alice\"}\n{\"change\":\"101\",\"user\":\"bob\"}\n"
                              "UTF-8")
        records (vec (m/decode-json-records (ByteArrayInputStream. json-bytes)))]
    (is (= 2 (count records)))
    (is (= "alice" (get-in records [0 "user"])))
    (is (= "bob"   (get-in records [1 "user"])))))

(deftest decode-json-blank-lines-test
  (let [bs (.getBytes "{\"a\":1}\n\n{\"b\":2}\n" "UTF-8")
        records (vec (m/decode-json-records (ByteArrayInputStream. bs)))]
    (is (= 2 (count records)))))
