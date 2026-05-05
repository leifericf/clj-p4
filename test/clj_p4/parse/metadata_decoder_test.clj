(ns clj-p4.parse.metadata-decoder-test
  "Unit coverage for the three metadata-decoding strategies in
   `clj-p4.parse.marshal/metadata-decoder`. Exercises:
   - `:strict` → UTF-8 only; throws on bad bytes.
   - `:fallback` → UTF-8 first, then a configurable fallback charset.
   - `:passthrough` → ISO-8859-1; never throws."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.parse.marshal :as marshal]))

(defn- ^bytes b [& xs]
  (byte-array (map unchecked-byte xs)))

(def ^:private utf8-snowman   "☃ snow")
(def ^:private utf8-snowman-bytes
  ;; ☃ is U+2603 → e2 98 83 in UTF-8
  (b 0xe2 0x98 0x83 0x20 0x73 0x6e 0x6f 0x77))

(def ^:private latin1-ntilde  "señor")
(def ^:private latin1-ntilde-bytes
  ;; "señor" in Latin-1: s=73 e=65 ñ=f1 o=6f r=72
  (b 0x73 0x65 0xf1 0x6f 0x72))

(def ^:private cp1252-rsquo   "it’s")  ; right single quote U+2019
(def ^:private cp1252-rsquo-bytes
  ;; "it’s" in CP-1252: i=69 t=74 \x92 s=73
  (b 0x69 0x74 0x92 0x73))

(deftest strict-decoder
  (testing "valid UTF-8 round-trips"
    (let [d (marshal/metadata-decoder :strict)]
      (is (= utf8-snowman (d utf8-snowman-bytes)))))
  (testing "ASCII subset works"
    (let [d (marshal/metadata-decoder :strict)]
      (is (= "hello" (d (.getBytes "hello" "UTF-8"))))))
  (testing "invalid UTF-8 throws ex-info with diagnostic data"
    (let [d (marshal/metadata-decoder :strict)]
      (try (d latin1-ntilde-bytes)
           (is false "should have thrown")
           (catch clojure.lang.ExceptionInfo e
             (is (= :metadata-decode-failed (:clj-p4/error (ex-data e))))
             (is (= :strict (:strategy (ex-data e))))
             (is (= 5 (:length (ex-data e)))))))))

(deftest fallback-decoder
  (testing "valid UTF-8 still decodes via the UTF-8 path"
    (let [d (marshal/metadata-decoder :fallback "CP1252")]
      (is (= utf8-snowman (d utf8-snowman-bytes)))))
  (testing "Latin-1 bytes fall back to Latin-1 (configured fallback)"
    (let [d (marshal/metadata-decoder :fallback "ISO-8859-1")]
      (is (= latin1-ntilde (d latin1-ntilde-bytes)))))
  (testing "CP-1252 fallback recognises the right-single-quote byte"
    (let [d (marshal/metadata-decoder :fallback "CP1252")]
      (is (= cp1252-rsquo (d cp1252-rsquo-bytes)))))
  (testing "default fallback is CP-1252"
    (let [d (marshal/metadata-decoder :fallback)]
      (is (= cp1252-rsquo (d cp1252-rsquo-bytes))))))

(deftest passthrough-decoder
  (testing "every byte maps to one Unicode code point"
    (let [d (marshal/metadata-decoder :passthrough)
          out (d latin1-ntilde-bytes)]
      (is (= 5 (.length out)))
      (is (= 0xf1 (long (.charAt out 2))))))
  (testing "passthrough never throws on arbitrary bytes"
    (let [d (marshal/metadata-decoder :passthrough)]
      (is (string? (d (b 0xff 0xfe 0xfd 0xfc)))))))

(deftest default-decoder-is-strict
  (testing "no-arg constructor matches :strict"
    (let [d (marshal/metadata-decoder)]
      (try (d latin1-ntilde-bytes)
           (is false)
           (catch clojure.lang.ExceptionInfo e
             (is (= :metadata-decode-failed
                    (:clj-p4/error (ex-data e))))
             (is (= :strict (:strategy (ex-data e)))))))))
