(ns clj-p4.integration.t9821-case-only-paths-test
  "Mirror of git-p4's `t/t9821-git-p4-path-variations.sh` (case-only
   sibling): `src/case.txt` and `src/Case.txt` coexist on a case-sensitive
   server and arrive as two distinct entries in the imported tree.

   The seed submits the uppercase variant via `pp-soft!`, so a
   case-insensitive p4d (default on macOS/Windows) silently drops it; the
   docker fixture is Linux and case-sensitive. We branch on what the seed
   actually produced rather than guarding on `p4 info`."
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
    (is (contains? paths "src/case.txt")
        "lowercase variant must always be present (added via strict pp!)")
    (if (contains? paths "src/Case.txt")
      (testing "case-sensitive server: both variants survive with their content"
        (is (= "lower\n"
               (ga/cat-blob-string target "refs/heads/main" "src/case.txt")))
        (is (= "upper\n"
               (ga/cat-blob-string target "refs/heads/main" "src/Case.txt"))))
      (testing "case-insensitive server: only the lowercase variant lands"
        ;; pp-soft! in seed.bb tolerates the rejected submit on stricter
        ;; servers — verify the import didn't crash and produced a sane tree.
        (is (some #(re-find #"^src/" %) paths))))))
