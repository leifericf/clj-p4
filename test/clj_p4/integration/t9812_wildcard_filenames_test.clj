(ns clj-p4.integration.t9812-wildcard-filenames-test
  "Mirror of git-p4's `t/t9812-git-p4-wildcards.sh`: filenames whose
   literal characters include any of the four P4-reserved wildcards
   (`@`, `#`, `*`, `%`) survive a clone with their literal names intact.

   The depot stores these paths in their `%40 %23 %2A %25` percent-encoded
   form, so the test exercises `parse/depot_path/unescape` end-to-end:
   anything that lands escaped in the git tree would prove the importer
   skipped the unescape step."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(def ^:private expected
  {"src/wild/at@x.txt"   "at\n"
   "src/wild/hash#x.txt" "hash\n"
   "src/wild/star*x.txt" "star\n"
   "src/wild/pct%x.txt"  "pct\n"})

(deftest ^:integration t9812-wildcard-chars-in-filenames
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9812") "wild.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        paths  (set (map :path (ga/ls-tree target "refs/heads/main")))]
    (testing "every wildcard-named path lands in the tree under its literal name"
      (doseq [[path _] expected]
        (is (contains? paths path)
            (str "missing literal-named entry: " path))))
    (testing "no escaped form leaks into the tree"
      (doseq [[_ esc-fragment]
              [["%40" "%40"] ["%23" "%23"] ["%2A" "%2A"] ["%25" "%25"]]]
        (is (not-any? #(re-find (re-pattern esc-fragment) %) paths)
            (str "found escaped-form path containing " esc-fragment))))
    (testing "blob content round-trips for each wildcard file"
      (doseq [[path body] expected]
        (is (= body
               (ga/cat-blob-string target "refs/heads/main" path))
            (str "content mismatch for " path))))))
