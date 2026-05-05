(ns clj-p4.integration.t9827-filetype-change-test
  "Mirror of git-p4's `t/t9827-git-p4-change-filetype.sh`: a file whose
   p4 type morphs (text → binary) keeps the correct content at each
   revision. The seed produces two changelists for `src/morphing.txt`
   tagged `t9827a` (text) and `t9827b` (binary)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9827-filetype-change-between-revisions
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9827") "morph.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        path   "src/morphing.txt"
        text-sha   (ga/find-commit-sha target "refs/heads/main" "t9827a")
        binary-sha (ga/find-commit-sha target "refs/heads/main" "t9827b")]
    (testing "both seed commits made it into the imported history"
      (is (string? text-sha))
      (is (string? binary-sha)))
    (testing "text revision content"
      (is (= "now binary?\n"
             (ga/cat-blob-string target text-sha path))))
    (testing "binary revision starts with the seeded NUL/SOH header"
      (let [bs (ga/cat-blob-bytes target binary-sha path)]
        (is (= [0x00 0x01]
               (mapv #(bit-and 0xff %) (take 2 (vec bs)))))))))
