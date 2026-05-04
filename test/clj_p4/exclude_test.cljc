(ns clj-p4.exclude-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.exclude :as ex]))

(deftest exclude-patterns-test
  (testing "resource patterns plus extras"
    (is (= ["*.exe" "*.dll" "*.zip" "*.bak"]
           (ex/exclude-patterns
            {:resource {:executables ["*.exe" "*.dll"]
                        :archives    ["*.zip"]}
             :extra-excludes ["*.bak"]}))))

  (testing "no-default-excludes? drops resource"
    (is (= ["*.bak"]
           (ex/exclude-patterns
            {:resource {:executables ["*.exe"]}
             :no-default-excludes? true
             :extra-excludes ["*.bak"]}))))

  (testing "includes whitelist"
    (is (= ["*.dll" "*.bak"]
           (ex/exclude-patterns
            {:resource {:executables ["*.exe" "*.dll"]}
             :extra-excludes ["*.bak"]
             :includes ["*.exe"]}))))

  (testing "dedupe"
    (is (= ["*.exe"]
           (ex/exclude-patterns
            {:resource {:a ["*.exe"] :b ["*.exe"]}
             :extra-excludes ["*.exe"]})))))

(deftest pattern->re-test
  (testing "*.ext at any depth"
    (let [re (ex/pattern->re "*.exe")]
      (is (re-find re "foo.exe"))
      (is (re-find re "src/bin/foo.exe"))
      (is (not (re-find re "foo.exec")))))

  (testing "/anchored only at root"
    (let [re (ex/pattern->re "/build")]
      (is (re-find re "build"))
      (is (not (re-find re "src/build")))))

  (testing "trailing slash directory match"
    (let [re (ex/pattern->re "node_modules/")]
      (is (re-find re "node_modules"))
      (is (re-find re "node_modules/foo/bar.js"))
      (is (re-find re "src/node_modules/foo.js"))
      (is (not (re-find re "src/node_modules.txt")))))

  (testing "P4 ellipsis"
    (let [re (ex/pattern->re "build/...")]
      (is (re-find re "build/x/y.o")))))

(deftest matches-any?-test
  (let [compiled (ex/compile-patterns ["*.exe" "*.dll" "build/..."])]
    (is (ex/matches-any? compiled "foo.exe"))
    (is (ex/matches-any? compiled "src/lib.dll"))
    (is (ex/matches-any? compiled "build/output/foo.o"))
    (is (not (ex/matches-any? compiled "src/foo.cpp")))))

(deftest matching-pattern-test
  (let [compiled (ex/compile-patterns ["*.exe" "*.dll"])]
    (is (= "*.exe" (ex/matching-pattern compiled "foo.exe")))
    (is (= "*.dll" (ex/matching-pattern compiled "lib/util.dll")))
    (is (nil? (ex/matching-pattern compiled "foo.cpp")))))
