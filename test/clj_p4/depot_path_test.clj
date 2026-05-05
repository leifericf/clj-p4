(ns clj-p4.depot-path-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.depot-path :as dp]))

(deftest unescape-decodes-known-p4-reserved-chars
  (testing "the four documented reserved characters round-trip"
    (is (= "@" (dp/unescape "%40")))
    (is (= "#" (dp/unescape "%23")))
    (is (= "*" (dp/unescape "%2A")))
    (is (= "%" (dp/unescape "%25"))))
  (testing "lower-case hex is also accepted"
    (is (= "@" (dp/unescape "%40")))
    (is (= "@" (dp/unescape "%40")))
    (is (= "*" (dp/unescape "%2a")))))

(deftest unescape-passes-through-non-escape-content
  (is (= "" (dp/unescape "")))
  (is (= "no/escapes/here.txt" (dp/unescape "no/escapes/here.txt")))
  (is (nil? (dp/unescape nil)))
  (testing "lone % at the end is literal"
    (is (= "trailing%" (dp/unescape "trailing%"))))
  (testing "% followed by non-hex is literal"
    (is (= "%foo" (dp/unescape "%foo")))
    (is (= "%g0" (dp/unescape "%g0")))
    (is (= "% 4" (dp/unescape "% 4"))))
  (testing "idempotent on already-decoded strings"
    (is (= "@bar" (dp/unescape (dp/unescape "%40bar"))))))

(deftest unescape-handles-mixed-and-multiple-escapes
  (testing "multiple sequences in a row"
    (is (= "@#" (dp/unescape "%40%23")))
    (is (= "@#*%" (dp/unescape "%40%23%2A%25"))))
  (testing "embedded among regular path segments"
    (is (= "//stream/main/src/wild/at@x.txt"
           (dp/unescape "//stream/main/src/wild/at%40x.txt")))
    (is (= "//stream/main/src/wild/hash#x.txt"
           (dp/unescape "//stream/main/src/wild/hash%23x.txt")))
    (is (= "//stream/main/src/wild/star*x.txt"
           (dp/unescape "//stream/main/src/wild/star%2Ax.txt")))
    (is (= "//stream/main/src/wild/pct%x.txt"
           (dp/unescape "//stream/main/src/wild/pct%25x.txt")))))

(deftest unescape-decodes-arbitrary-hex
  (testing "any %XX is decoded for forward-compat"
    (is (= " " (dp/unescape "%20")))
    (is (= "/" (dp/unescape "%2F")))))
