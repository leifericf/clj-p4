(ns clj-p4.integration.t9821-case-only-paths-test
  "Mirror of git-p4's `t/t9821-git-p4-path-variations.sh` (case-only
   sibling): `src/case.txt` and `src/Case.txt` coexist on a case-sensitive
   server and arrive as two distinct entries in the imported tree.

   The docker fixture's p4d is Linux and case-sensitive, and `init-bare!`
   forces `core.ignorecase=false` so fast-import on macOS hosts doesn't
   merge the entries. Both files must always be present."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9821-case-only-siblings-coexist
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9821") "case-only.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        paths  (set (map :path (ga/ls-tree target "refs/heads/main")))]
    (testing "both variants land as distinct tree entries"
      (is (contains? paths "src/case.txt"))
      (is (contains? paths "src/Case.txt")))
    (testing "each variant carries its original content"
      (is (= "lower\n"
             (ga/cat-blob-string target "refs/heads/main" "src/case.txt")))
      (is (= "upper\n"
             (ga/cat-blob-string target "refs/heads/main" "src/Case.txt"))))))
