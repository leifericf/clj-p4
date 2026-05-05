(ns clj-p4.integration.t9819-case-folding-test
  "Mirror of git-p4's `t/t9819-git-p4-case-folding.sh`: a single-cased
   filename round-trips intact. The companion t9821 covers coexisting
   case-only siblings; this test stays focused on the lowercase baseline."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9819-lowercase-name-round-trips
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9819") "case.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        paths  (set (map :path (ga/ls-tree target "refs/heads/main")))]
    (testing "lowercase path lands at its original spelling"
      (is (contains? paths "src/case.txt")))
    (testing "content survives unchanged"
      (is (= "lower\n"
             (ga/cat-blob-string target "refs/heads/main" "src/case.txt"))))))
