(ns clj-p4.spec-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clj-p4.spec :as spec]))

(deftest depot-path?-test
  (testing "valid depot paths"
    (doseq [p ["//depot/ProjectA/main/..."
               "//stream/main"
               "//stream/main/..."
               "//depot/ProjectA/main/*"
               "//depot/foo.bar/baz"
               "//d/file.cpp"]]
      (is (spec/depot-path? p) (str p " should be valid"))))

  (testing "invalid depot paths"
    (doseq [p [nil
               ""
               "depot/foo"
               "/single-slash/foo"
               "//"
               "//../escape"
               "//depot/../escape"
               "//depot/sub/../up"
               "//depot/$weird"
               "//depot with spaces"
               "//foo/.../bar"
               "//foo/*/bar"
               "//foo/.../*"]]
      (is (not (spec/depot-path? p)) (str (pr-str p) " should be invalid"))))

  (testing "wildcards only legal as final segment"
    (is (spec/depot-path? "//foo/..."))
    (is (spec/depot-path? "//foo/*"))
    (is (spec/depot-path? "//foo/bar/baz"))
    (is (nil? (spec/parse-depot-path "//foo/.../bar")))))

(deftest parse-depot-path-test
  (is (= {:depot/raw      "//depot/ProjectA/main/..."
          :depot/depot    "depot"
          :depot/segments ["ProjectA" "main"]
          :depot/wildcard :ellipsis}
         (spec/parse-depot-path "//depot/ProjectA/main/...")))

  (is (= {:depot/raw      "//stream/main"
          :depot/depot    "stream"
          :depot/segments ["main"]
          :depot/wildcard nil}
         (spec/parse-depot-path "//stream/main")))

  (is (= {:depot/raw      "//depot/ProjectA/main/*"
          :depot/depot    "depot"
          :depot/segments ["ProjectA" "main"]
          :depot/wildcard :star}
         (spec/parse-depot-path "//depot/ProjectA/main/*")))

  (is (nil? (spec/parse-depot-path "not a depot path"))))

(deftest validate-depot-path!-test
  (is (= "//depot/foo" (spec/validate-depot-path! "//depot/foo")))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (spec/validate-depot-path! "no good"))))

(deftest connection-spec?-test
  (is (spec/connection-spec? {:p4/port "ssl:host:1666"}))
  (is (spec/connection-spec? {:p4/port    "ssl:host:1666"
                              :p4/user    "ci"
                              :p4/client  "clj-p4-x"
                              :p4/charset :utf8
                              :p4/ticket  "abc"
                              :p4/timeout-ms 30000}))
  (is (not (spec/connection-spec? nil)))
  (is (not (spec/connection-spec? {})))
  (is (not (spec/connection-spec? {:p4/port 1666})))
  (is (not (spec/connection-spec? {:p4/port "ssl:host:1666" :p4/timeout-ms -1})))
  (is (not (spec/connection-spec? {:p4/port "ssl:host:1666" :p4/charset "utf8"}))))

(defspec depot-path-roundtrip 200
  (prop/for-all
   [depot     (gen/such-that not-empty gen/string-alphanumeric)
    segs      (gen/vector (gen/such-that not-empty gen/string-alphanumeric)
                          1 5)
    wildcard  (gen/elements [nil :ellipsis :star])]
   (let [tail   (case wildcard :ellipsis "/..." :star "/*" nil "")
         raw    (str "//" depot "/" (clojure.string/join "/" segs) tail)
         parsed (spec/parse-depot-path raw)]
     (and parsed
          (= raw   (:depot/raw parsed))
          (= depot (:depot/depot parsed))
          (= segs  (:depot/segments parsed))
          (= wildcard (:depot/wildcard parsed))))))
