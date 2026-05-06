(ns clj-p4.integration.t9826-keep-empty-commits-test
  "Mirror of git-p4's `t/t9826-git-p4-keep-empty-commits.sh`: when a
   changelist's only files are filtered out (by sub-source view in the
   upstream test, by `:exclude` here — the same effective contract),
   git-p4 *skips* the commit by default and emits an empty one with
   `--keep-empty-commits`.

   We exercise the same matrix using `:exclude` to make a real CL
   produce zero file ops:
   - Change 7 (t9803 shell metachars) only modifies files under
     `src/oddly named/`. Excluding that subtree turns change 7 into
     an empty CL on import.
   - Default clone: change 7 is dropped; commit count = (total CLs - 1).
   - With `:keep-empty-commits? true`: change 7 still emits; commit
     count equals the unfiltered baseline."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.excludes :as excludes]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]
            [clj-p4.io.git :as git]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(defn- contains-msg?
  "True if any commit message reachable from `ref` contains `subject`."
  [target ref subject]
  (some? (ga/find-commit-sha target ref subject)))

(deftest ^:integration t9826-empty-commits-skipped-by-default-kept-with-flag
  (let [conn      (fix/admin-conn-with-ticket)
        base      (ga/tmp-dir "clj-p4-t9826")
        baseline  (str (io/file base "baseline.git"))
        skipped   (str (io/file base "skipped.git"))
        kept      (str (io/file base "kept.git"))
        excludes  (excludes/compile-patterns
                   (excludes/exclude-patterns
                    {:no-default-excludes? true
                     :excludes ["src/oddly named/"]}))]
    ;; Baseline: no exclusion. All CLs land.
    (api/clone! {:conn conn :source "//stream/main" :target baseline})
    ;; Default: exclusion AND no flag. Change 7 has no remaining files;
    ;; should be skipped from the imported history.
    (api/clone! {:conn conn :source "//stream/main" :target skipped
                 :exclude excludes})
    ;; Flag on: empty commits preserved.
    (api/clone! {:conn conn :source "//stream/main" :target kept
                 :exclude excludes
                 :keep-empty-commits? true})
    (let [baseline-n (git/commit-count baseline "refs/heads/main")
          skipped-n  (git/commit-count skipped  "refs/heads/main")
          kept-n     (git/commit-count kept     "refs/heads/main")]
      (testing "default behaviour skips the all-excluded CL"
        (is (= (dec baseline-n) skipped-n))
        (is (not (contains-msg? skipped "refs/heads/main" "t9803"))))
      (testing ":keep-empty-commits? true preserves the all-excluded CL"
        (is (= baseline-n kept-n))
        (is (contains-msg? kept "refs/heads/main" "t9803")))
      (testing "the kept-empty commit's tree omits the excluded subtree"
        (let [sha (ga/find-commit-sha kept "refs/heads/main" "t9803")
              listing (ga/ls-tree kept sha)]
          (is (not-any? #(re-find #"^src/oddly named/" %)
                        (map :path listing))))))))
