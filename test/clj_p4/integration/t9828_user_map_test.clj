(ns clj-p4.integration.t9828-user-map-test
  "Mirror of git-p4's `t/t9828-git-p4-map-user.sh`: the `:user-map`
   option remaps the committer identity on imported commits. A CL
   submitted by `alice` on the p4 server lands in git with the
   default committer `alice <alice@perforce>`; with a `:user-map`
   entry the commit's `%an <%ae>` is the mapped name and email.

   The fixture seeds user `alice` and one CL she submitted
   (`t9828: alice's change`). The test runs two clones — one without
   the map, one with — and asserts the committer changes on exactly
   that commit."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]
            [clj-p4.io.subprocess :as proc]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(defn- committer [target sha]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked!
         ["git" "-C" target "log" "-1" "--format=%an <%ae>" sha])]
    (str/trim-newline (String. ^bytes stdout-bytes "UTF-8"))))

(deftest ^:integration t9828-default-committer-uses-perforce-domain
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9828-default") "def.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        sha    (ga/find-commit-sha target "refs/heads/main" "alice's change")]
    (is (some? sha) "expected a commit message containing 'alice's change'")
    (is (= "alice <alice@perforce>" (committer target sha)))))

(deftest ^:integration t9828-user-map-rewrites-committer-name-and-email
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9828-mapped") "map.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target
                            :user-map {"alice" {:name "Alice Author"
                                                :email "alice@example.com"}}})
        sha    (ga/find-commit-sha target "refs/heads/main" "alice's change")]
    (is (= "Alice Author <alice@example.com>" (committer target sha)))))
