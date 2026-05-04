(ns clj-p4.parse.marshal
  "Decode p4's structured wire formats into generic Clojure record-maps.

   Two paths:
   - `decode-marshal-records`  — Python-2-marshal output of `p4 -G` (default).
   - `decode-json-records`     — line-delimited JSON of `p4 -Mj` (server ≥ 2024.1).

   Both produce a lazy seq of string-keyed Clojure maps, ready for
   `clj-p4.parse.semantic`. JVM-only — JSON path uses `clojure.data.json`,
   marshal path is a hand-rolled byte decoder."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io InputStream
                    PushbackInputStream)
           (java.util ArrayList)))

;; --- Python marshal decoder -------------------------------------------------

(def ^:private TYPE-MASK     0x7F)

(def ^:private TYPE-NULL      0x30)  ; '0'
(def ^:private TYPE-NONE      0x4E)  ; 'N'
(def ^:private TYPE-INT       0x69)  ; 'i'
(def ^:private TYPE-INT64     0x49)  ; 'I'
(def ^:private TYPE-STRING    0x73)  ; 's'
(def ^:private TYPE-INTERNED  0x74)  ; 't'
(def ^:private TYPE-STRINGREF 0x52)  ; 'R'
(def ^:private TYPE-DICT      0x7B)  ; '{'
(def ^:private TYPE-TUPLE     0x28)  ; '('
(def ^:private TYPE-LIST      0x5B)  ; '['

(defn- read-byte! [^PushbackInputStream is]
  (let [b (.read is)]
    (when (neg? b)
      (throw (ex-info "unexpected EOF in marshal stream"
                      {:clj-p4/error :marshal-eof})))
    b))

(defn- read-bytes! [^PushbackInputStream is n]
  (let [arr (byte-array n)]
    (loop [off 0]
      (when (< off n)
        (let [r (.read is arr off (- n off))]
          (when (neg? r)
            (throw (ex-info "unexpected EOF in marshal payload"
                            {:clj-p4/error :marshal-eof})))
          (recur (+ off r)))))
    arr))

(defn- read-uint32-le! [^PushbackInputStream is]
  (let [b0 (read-byte! is) b1 (read-byte! is)
        b2 (read-byte! is) b3 (read-byte! is)]
    (bit-or b0
            (bit-shift-left b1 8)
            (bit-shift-left b2 16)
            (bit-shift-left b3 24))))

(defn- read-int32-le! [^PushbackInputStream is]
  (let [u (read-uint32-le! is)]
    (if (>= u 0x80000000)
      (- u 0x100000000)
      u)))

(declare ^:private decode-value)

(defn- decode-dict [^PushbackInputStream is intern-tab]
  (loop [acc (transient {})]
    (let [b (.read is)]
      (cond
        (or (neg? b) (= (bit-and b TYPE-MASK) TYPE-NULL))
        (persistent! acc)

        :else
        (let [k  (decode-value b is intern-tab)
              vt (read-byte! is)
              v  (decode-value vt is intern-tab)]
          (recur (assoc! acc k v)))))))

(defn- decode-collection [^PushbackInputStream is intern-tab]
  (let [n (read-uint32-le! is)]
    (loop [i 0, acc (transient [])]
      (if (>= i n)
        (persistent! acc)
        (recur (inc i)
               (conj! acc (decode-value (read-byte! is) is intern-tab)))))))

(defn- decode-value [type-byte ^PushbackInputStream is ^ArrayList intern-tab]
  (let [t (bit-and type-byte TYPE-MASK)]
    (cond
      (= t TYPE-NULL)   nil
      (= t TYPE-NONE)   nil
      (= t TYPE-INT)    (read-int32-le! is)
      (= t TYPE-INT64)  (let [lo (read-uint32-le! is)
                              hi (read-int32-le! is)]
                          (bit-or lo (bit-shift-left hi 32)))
      (= t TYPE-STRING) (String. ^bytes (read-bytes! is (read-uint32-le! is)) "UTF-8")
      (= t TYPE-INTERNED)
      (let [s (String. ^bytes (read-bytes! is (read-uint32-le! is)) "UTF-8")]
        (.add intern-tab s)
        s)
      (= t TYPE-STRINGREF)
      (let [idx (read-int32-le! is)]
        (.get intern-tab idx))
      (= t TYPE-DICT)   (decode-dict is intern-tab)
      (= t TYPE-TUPLE)  (decode-collection is intern-tab)
      (= t TYPE-LIST)   (decode-collection is intern-tab)
      :else
      (throw (ex-info "unknown marshal type"
                      {:clj-p4/error :marshal-unknown-type
                       :type-byte    type-byte})))))

(defn decode-marshal-records
  "Lazy seq of decoded records from a `p4 -G` byte stream. Each record is a
   string-keyed map. The intern table resets per record (P4's convention)."
  [in]
  (let [^PushbackInputStream is (if (instance? PushbackInputStream in)
                                  in
                                  (PushbackInputStream. (io/input-stream in) 1))]
    (letfn [(step []
              (lazy-seq
               (let [b (.read is)]
                 (when (>= b 0)
                   (let [intern-tab (ArrayList.)]
                     (cons (decode-value b is intern-tab) (step)))))))]
      (step))))

;; --- JSON decoder (-Mj, server ≥ 2024.1) ------------------------------------

(defn decode-json-records
  "Lazy seq of decoded records from a `p4 -Mj` newline-delimited JSON byte
   stream. Each line is one JSON object."
  [in]
  (let [rdr (io/reader in)
        lines (line-seq rdr)]
    (->> lines
         (remove str/blank?)
         (map #(json/read-str % :key-fn str)))))
