(ns clj-p4.integration.virtual-stream-test
  "Integration test for cloning a virtual stream end-to-end. Verifies that
   the auto-managed ephemeral client lifecycle works against a real p4d
   server, that the stream's filtered view is honoured, and that no
   ephemeral client is left behind."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.shell.proc :as proc]))

(use-fixtures :once
  (fn [f]
    (when (fix/integration-enabled?) (fix/with-p4d f))))

(defn- tmp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-p4-virt"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- list-clients-named-clj-p4
  "Names of any p4 clients on the test server whose name starts with
   `clj-p4-`. The integration assertion is that none survive a successful
   import — the with-ephemeral-client cleanup must run."
  []
  (let [{:keys [stdout-bytes]}
        (proc/run-checked!
         ["docker" "compose" "-f" "test/fixtures/p4d/docker-compose.yml"
          "exec" "-T" "p4d" "sh" "-c"
          "p4 -p tcp:localhost:1666 -u admin -P admin1234 clients -e 'clj-p4-*'"]
         {:timeout-ms 30000})]
    (->> (str/split-lines (String. ^bytes stdout-bytes "UTF-8"))
         (filter (complement str/blank?))
         vec)))

(deftest ^:integration virtual-stream-clones-via-ephemeral-client
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (tmp-dir) "virt.git"))
        result (api/clone! {:conn   conn
                            :stream "//stream/virtual"
                            :target target})]
    (testing "clone returned commits and last-change"
      (is (string? (:target result)))
      (is (pos? (:commits result))))

    (testing "tree contents respect the virtual stream's filtered view (src/...)"
      (let [{:keys [stdout-bytes]}
            (proc/run-checked! ["git" "-C" target "ls-tree" "-r"
                                "refs/heads/main"])
            listing (String. ^bytes stdout-bytes "UTF-8")]
        (is (str/includes? listing "src/"))))

    (testing "git-p4 trailer carries the user-visible stream name"
      (let [{:keys [stdout-bytes]}
            (proc/run-checked! ["git" "-C" target "log" "-1"
                                "--pretty=%B" "refs/heads/main"])
            msg (String. ^bytes stdout-bytes "UTF-8")]
        (is (str/includes? msg "//stream/virtual"))
        (is (not (re-find #"clj-p4-[0-9a-f]" msg)))))

    (testing "no ephemeral client survives on the server"
      (is (empty? (list-clients-named-clj-p4))))))
