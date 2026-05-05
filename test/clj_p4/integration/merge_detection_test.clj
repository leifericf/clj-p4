(ns clj-p4.integration.merge-detection-test
  "Not a numbered git-p4 port (the upstream suite has no equivalent —
   the gap was flagged by p4-fusion's bug history). Exercises clj-p4's
   `execute/merge-source-for-cl` end-to-end:

   When a changelist on `branches/main/...` is the result of a `p4
   integrate` from `branches/feature/...`, and both branches are cloned
   into the same git repo, the matching git commit must have two
   parents — the prior commit on `refs/heads/main` and the most-recent
   imported commit from `refs/heads/feature`.

   Uses a classic depot rather than streams so the integrate is a
   vanilla `p4 integrate` without stream-flow constraints."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]
            [clj-p4.shell.proc :as proc]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(defn- parents [target sha]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked!
         ["git" "-C" target "log" "-1" "--format=%P" sha])]
    (->> (str/trim (String. ^bytes stdout-bytes "UTF-8"))
         (#(str/split % #"\s+"))
         (remove str/blank?)
         vec)))

(deftest ^:integration merge-detection-produces-two-parent-commit
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-merge") "merge.git"))
        ;; Order matters: import feature FIRST so its CLs land in the
        ;; shared marks file before main is processed. Then when main's
        ;; merge commit (CL 26) runs through `merge-source-for-cl`, the
        ;; feature CL it points at is already in `imported?`.
        _      (api/clone! {:conn conn
                            :sources ["//classic_depot/branches/feature"
                                      "//classic_depot/branches/main"]
                            :target target})
        merge-sha   (ga/find-commit-sha target "refs/heads/main"
                                        "merge feature into main")
        feature-sha (ga/find-commit-sha target "refs/heads/feature"
                                        "feature change")]
    (testing "the merge commit and the feature commit both imported"
      (is (string? merge-sha))
      (is (string? feature-sha)))
    (testing "the merge commit has exactly two parents"
      (let [ps (parents target merge-sha)]
        (is (= 2 (count ps))
            (str "expected 2 parents, got " (count ps) ": " ps))))
    (testing "the second parent is the feature-branch tip we imported"
      (let [[_ second-parent] (parents target merge-sha)]
        (is (= feature-sha second-parent))))))
