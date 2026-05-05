(ns clj-p4.integration.fixture
  "Helpers for running tests against the docker-compose-managed p4d container.

   Tests gated behind `^:integration` and require:
   - `CLJ_P4_INTEGRATION=1`
   - `docker compose` available on PATH
   - Project layout: this file at `test/clj_p4/integration/`, fixture at
     `test/fixtures/p4d/`."
  (:require [clj-p4.shell.proc :as proc]
            [clojure.java.io :as io]))

(def ^:private fixture-dir
  "test/fixtures/p4d")

(def ^:private fixture-mixed-dir
  "test/fixtures/p4d-mixed")

(defn integration-enabled?
  "True if `CLJ_P4_INTEGRATION=1` is set in the environment."
  []
  (= "1" (System/getenv "CLJ_P4_INTEGRATION")))

(defn docker-up!
  "`docker compose up -d --build`. Returns when the container is healthy."
  []
  (proc/run-checked! ["docker" "compose" "-f"
                      (str (io/file fixture-dir "docker-compose.yml"))
                      "up" "-d" "--build"]
                     {:timeout-ms (* 10 60 1000)})
  (loop [tries 60]
    (let [{:keys [exit]}
          (proc/run! ["docker" "compose" "-f"
                      (str (io/file fixture-dir "docker-compose.yml"))
                      "exec" "-T" "p4d"
                      "p4" "-p" "tcp:localhost:1666" "info"]
                     {:timeout-ms 5000})]
      (cond
        (zero? exit)   :ready
        (zero? tries)  (throw (ex-info "p4d never became healthy" {}))
        :else          (do (Thread/sleep 1000) (recur (dec tries)))))))

(defn docker-down!
  "`docker compose down -v`."
  []
  (proc/run-checked! ["docker" "compose" "-f"
                      (str (io/file fixture-dir "docker-compose.yml"))
                      "down" "-v"]
                     {:timeout-ms 60000}))

(def ^:private admin-conn
  {:p4/port "tcp:localhost:1666"
   :p4/user "admin"
   :p4/charset :utf8})

(defn admin-conn-with-ticket
  "Issue `p4 login -a -p` to get a host-agnostic ticket and return a
   ConnectionSpec carrying it. The `-a` flag is essential: a default
   `login -p` ticket is bound to the issuing client's IP, which works
   inside the docker container but not from the host running the test
   process — those see different peer addresses for the same p4d."
  []
  (let [{:keys [stdout-bytes]} (proc/run-checked!
                                ["docker" "compose" "-f"
                                 (str (io/file fixture-dir "docker-compose.yml"))
                                 "exec" "-T" "p4d"
                                 "sh" "-c"
                                 "echo admin1234 | p4 -p tcp:localhost:1666 -u admin login -a -p"]
                                {:timeout-ms 30000})
        out  (String. ^bytes stdout-bytes "UTF-8")
        ;; The ticket is the last non-empty line of the output.
        lines (filter seq (clojure.string/split-lines out))
        ticket (last lines)]
    (assoc admin-conn :p4/ticket ticket)))

(defn block-test-conn-with-ticket
  "ConnectionSpec for the `block_test` user, who's constrained by the
   `clj_p4_block_test` group's `MaxResults: 5`. Used by the t9818
   block-mode change-fetching test."
  []
  (let [{:keys [stdout-bytes]} (proc/run-checked!
                                ["docker" "compose" "-f"
                                 (str (io/file fixture-dir "docker-compose.yml"))
                                 "exec" "-T" "p4d"
                                 "sh" "-c"
                                 "echo admin1234 | p4 -p tcp:localhost:1666 -u block_test login -a -p"]
                                {:timeout-ms 30000})
        out  (String. ^bytes stdout-bytes "UTF-8")
        lines (filter seq (clojure.string/split-lines out))
        ticket (last lines)]
    {:p4/port    "tcp:localhost:1666"
     :p4/user    "block_test"
     :p4/charset :utf8
     :p4/ticket  ticket}))

(defn with-p4d
  "fn-2-arg fixture: brings p4d up, runs `f`, takes it down."
  [f]
  (if (integration-enabled?)
    (try
      (docker-up!)
      (f)
      (finally
        (try (docker-down!) (catch Exception _))))
    (println "[clj-p4] integration tests skipped (set CLJ_P4_INTEGRATION=1)")))

;; --- Non-unicode (mixed-encoding) sidecar ---------------------------------
;; Used by the metadata-encoding ports (t9835/t9836). The sidecar runs a
;; second p4d on host port 1667 in *non-unicode* mode; its seed deliberately
;; submits changelist descriptions with CP-1252 / Latin-1 bytes so the
;; importer's metadata-decoder strategies have something real to chew on.

(defn docker-up-mixed! []
  (proc/run-checked! ["docker" "compose" "-f"
                      (str (io/file fixture-mixed-dir "docker-compose.yml"))
                      "up" "-d" "--build"]
                     {:timeout-ms (* 10 60 1000)})
  (loop [tries 60]
    (let [{:keys [exit]}
          (proc/run! ["docker" "compose" "-f"
                      (str (io/file fixture-mixed-dir "docker-compose.yml"))
                      "exec" "-T" "p4d-mixed"
                      "p4" "-p" "tcp:localhost:1667" "info"]
                     {:timeout-ms 5000})]
      (cond
        (zero? exit)   :ready
        (zero? tries)  (throw (ex-info "p4d-mixed never became healthy" {}))
        :else          (do (Thread/sleep 1000) (recur (dec tries)))))))

(defn docker-down-mixed! []
  (proc/run-checked! ["docker" "compose" "-f"
                      (str (io/file fixture-mixed-dir "docker-compose.yml"))
                      "down" "-v"]
                     {:timeout-ms 60000}))

(def ^:private mixed-conn
  {:p4/port "tcp:localhost:1667"
   :p4/user "admin"})

(defn mixed-conn-with-ticket
  "`p4 login -a -p` against the non-unicode sidecar. Charset is left
   unset because the server isn't in unicode mode."
  []
  (let [{:keys [stdout-bytes]} (proc/run-checked!
                                ["docker" "compose" "-f"
                                 (str (io/file fixture-mixed-dir "docker-compose.yml"))
                                 "exec" "-T" "p4d-mixed"
                                 "sh" "-c"
                                 "echo admin1234 | p4 -p tcp:localhost:1667 -u admin login -a -p"]
                                {:timeout-ms 30000})
        out  (String. ^bytes stdout-bytes "UTF-8")
        lines (filter seq (clojure.string/split-lines out))
        ticket (last lines)]
    (assoc mixed-conn :p4/ticket ticket)))

(defn with-p4d-mixed
  "fn-1-arg fixture: brings the non-unicode sidecar up, runs `f`, takes
   it down. Independent of `with-p4d`'s primary fixture."
  [f]
  (if (integration-enabled?)
    (try
      (docker-up-mixed!)
      (f)
      (finally
        (try (docker-down-mixed!) (catch Exception _))))
    (println "[clj-p4] integration tests skipped (set CLJ_P4_INTEGRATION=1)")))
