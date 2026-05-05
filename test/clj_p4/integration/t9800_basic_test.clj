(ns clj-p4.integration.t9800-basic-test
  "Mirror of git-p4's `t/t9800-git-p4-basic.sh`: clone a stream depot, verify
   commit count, then sync after a new submit and verify exactly one new
   commit."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.shell.git :as git]
            [clj-p4.shell.proc :as proc]))

(use-fixtures :once
  (fn [f]
    (when (fix/integration-enabled?) (fix/with-p4d f))))

(defn- tmp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-p4-t9800"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest ^:integration t9800-clone-stream-main
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (tmp-dir) "main.git"))
        result (api/clone! {:conn   conn
                            :source "//stream/main"
                            :target target})]
    (testing "clone succeeded"
      (is (string? (:target result)))
      (is (pos? (:commits result))))
    (testing "git history has expected commits"
      (is (= (:commits result) (git/commit-count target "refs/heads/main"))))
    (testing "git-p4 trailer is preserved"
      (let [{:keys [stdout-bytes]}
            (proc/run-checked! ["git" "-C" target "log" "-1"
                                "--pretty=%B" "refs/heads/main"])
            msg (String. ^bytes stdout-bytes "UTF-8")]
        (is (clojure.string/includes? msg "git-p4:"))))))

(deftest ^:integration t9800-sync-after-submit
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (tmp-dir) "sync.git"))
        clone-result (api/clone! {:conn conn :source "//stream/main"
                                  :target target})
        before-commits (:commits clone-result)
        ;; Unique filename per invocation: a sub-second-precise nano-id
        ;; so this test stays idempotent if the same docker fixture is
        ;; shared across multiple in-session runs.
        extra (str "/p4/ws_main/src/extra-"
                   (System/nanoTime) ".txt")]
    ;; Submit a new change inside the docker container. Uses `set -eu`
    ;; (no `-o pipefail` — the container's /bin/sh is dash, which doesn't
    ;; recognise pipefail).
    (proc/run-checked!
     ["docker" "compose" "-f" "test/fixtures/p4d/docker-compose.yml"
      "exec" "-T" "p4d" "sh" "-c"
      (str "set -eu; "
           "echo new > " extra "; "
           "p4 -c clj_p4_seed_main -u admin -P admin1234 add " extra "; "
           "p4 -c clj_p4_seed_main -u admin -P admin1234 submit -d 't9800-sync-extra'")]
     {:timeout-ms 60000})

    (let [sync-result (api/sync! {:conn conn :source "//stream/main"
                                  :target target})]
      (is (= (inc before-commits) (:commit-count sync-result))))))
