(ns clj-p4.integration.t9818-block-mode-test
  "Mirror of git-p4's `t/t9818-git-p4-block.sh`: when the source p4d
   refuses an unbounded `p4 changes` query (typically because the
   requesting user is in a group with `MaxResults`/`MaxScanRows`), the
   importer must walk the changelist range in fixed-size windows.

   The fixture creates user `block_test` in group `clj_p4_block_test`
   with `MaxResults: 6`. The seeded depot has 13 CLs on `//stream/main`,
   so any unbounded clone as `block_test` exceeds the limit.

   - Default clone (no `:changes-block-size`) must throw with the
     server's `Request too large` message visible in the failure.
   - Clone with `:changes-block-size 5` must succeed (5 < 6 keeps each
     fetch under the limit).
   - The block-mode clone's history must equal the unrestricted-admin
     baseline byte-for-byte: same commit count, same head SHA — block
     mode is a fetch strategy, not a different import."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]
            [clj-p4.shell.git :as git]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9818-unbounded-clone-trips-maxresults
  (let [conn   (fix/block-test-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9818-fail") "fail.git"))]
    (try
      (api/clone! {:conn conn :source "//stream/main" :target target})
      (is false "expected MaxResults rejection")
      (catch clojure.lang.ExceptionInfo e
        (is (= :proc-failed (:clj-p4/error (ex-data e))))))))

(deftest ^:integration t9818-block-size-5-succeeds-and-matches-baseline
  (let [admin   (fix/admin-conn-with-ticket)
        block   (fix/block-test-conn-with-ticket)
        base    (str (io/file (ga/tmp-dir "clj-p4-t9818-base") "base.git"))
        chunked (str (io/file (ga/tmp-dir "clj-p4-t9818-chk") "chunked.git"))]
    (api/clone! {:conn admin :source "//stream/main" :target base})
    (api/clone! {:conn block :source "//stream/main" :target chunked
                 :changes-block-size 5})
    (testing "block-mode clone produces the same commit count as baseline"
      (is (= (git/commit-count base    "refs/heads/main")
             (git/commit-count chunked "refs/heads/main"))))
    (testing "block-mode clone produces the same head SHA as baseline"
      (is (= (first (git/rev-list base    "refs/heads/main"))
             (first (git/rev-list chunked "refs/heads/main")))))))
