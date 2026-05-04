(ns clj-p4.api
  "Public facade: `clone!`, `sync!`, `repo-state`, `available?`, `clone?`.

   Each takes the data it needs explicitly — no opts god-map. Side effects
   delegated to `clj-p4.shell.*`; orchestration delegated to
   `clj-p4.execute`. JVM-only."
  (:require [clj-p4.execute :as execute]
            [clj-p4.exclude :as exclude]
            [clj-p4.plan :as plan]
            [clj-p4.shell.git :as git]
            [clj-p4.shell.p4 :as p4]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn available?
  "True if `p4` is on PATH and answers `p4 -V`. Cheap and offline."
  []
  (p4/p4-available?))

(defn clone?
  "True if `repo-path` is already a clj-p4 (or git-p4) clone — has a
   commit reachable from `refs/heads/main` whose message contains the
   `[git-p4: ...]` trailer."
  [repo-path]
  (try
    (and (.exists (io/file (str repo-path) "HEAD"))
         (let [{:keys [exit stdout-bytes]}
               (clj-p4.shell.proc/run! ["git" "-C" (str repo-path)
                                        "log" "-1" "--pretty=%B"
                                        "refs/heads/main"])]
           (and (zero? exit)
                (str/includes? (String. ^bytes stdout-bytes "UTF-8")
                               "git-p4:"))))
    (catch Exception _ false)))

(defn- choose-mode
  [conn]
  (let [info (p4/info conn :mode :G)
        major (:p4/server-version-major info)
        minor (:p4/server-version-minor info)]
    {:mode (if (and major minor
                    (or (> major 2024)
                        (and (= major 2024) (>= minor 1))))
             :Mj
             :G)
     :info info}))

(defn- resolve-changes
  [conn stream-name {:keys [max-changes since]} mode]
  (mapv :p4/change
        (sort-by :p4/change
                 (p4/changes conn (str stream-name "/...")
                             :mode mode :max max-changes :since since))))

(defn clone!
  "Clone a Perforce stream into a new bare git repo at `target`.

   Required:
     :conn         ConnectionSpec
     :stream       depot path of the stream (e.g. `\"//stream/main\"`)
     :target       absolute path for the new bare repo

   Optional:
     :ref          target ref (default `refs/heads/main`)
     :max-changes  cap on changelists imported
     :exclude      compiled exclude patterns (vector of `[pat re]`)
     :progress-fn  `(fn [op])` — invoked before each op
     :stop?        `(fn [])` — abort predicate

   Returns `{:target :commits :last-change}`."
  [{:keys [conn stream target ref max-changes exclude
           progress-fn stop?]
    :or   {ref         "refs/heads/main"
           progress-fn (fn [_])
           stop?       (constantly false)}}]
  (let [{:keys [mode]} (choose-mode conn)
        chain          (p4/stream-chain conn stream :mode mode)
        _              (when (some #(= :virtual (:stream/type %)) chain)
                         (throw (ex-info "virtual stream not supported in v0.1"
                                         {:clj-p4/error :virtual-stream-unsupported
                                          :stream stream})))
        changes        (resolve-changes conn stream
                                        {:max-changes max-changes}
                                        mode)
        plan-val       (plan/clone-plan
                        {:conn         conn
                         :stream-chain chain
                         :changelists  changes
                         :target       target
                         :excludes     exclude
                         :options      {:max-changes max-changes
                                        :checkpoint-every 1000}})]
    (git/init-bare! target)
    (let [marks-file (str (io/file target ".." "clj-p4.marks"))
          handle     (git/fast-import-start target {:marks-file marks-file})]
      (try
        (let [final (execute/execute! plan-val
                                      {:git-handle  handle
                                       :conn        conn
                                       :ref         ref
                                       :progress-fn progress-fn
                                       :stop?       stop?})
              {:keys [exit stderr]} (git/fast-import-close! handle)]
          (when-not (zero? exit)
            (throw (ex-info (str "git fast-import exited " exit)
                            {:clj-p4/error :fast-import-failed
                             :stderr stderr})))
          {:target      target
           :commits     (count changes)
           :last-change (:last-change final)})
        (catch Throwable t
          (try (git/fast-import-close! handle) (catch Exception _))
          (throw t))))))

(defn repo-state
  "Inspect a clj-p4 clone. Returns
   `{:target :head-sha :commit-count :last-change}`."
  [target & {:keys [ref] :or {ref "refs/heads/main"}}]
  (let [shas (git/rev-list target ref)]
    {:target       target
     :head-sha     (first shas)
     :commit-count (count shas)
     :last-change  (let [{:keys [stdout-bytes]}
                         (clj-p4.shell.proc/run!
                          ["git" "-C" (str target) "log" "-1"
                           "--pretty=%B" ref])
                         msg (when stdout-bytes
                               (String. ^bytes stdout-bytes "UTF-8"))]
                     (when-let [[_ ch] (and msg
                                            (re-find #"change\s*=\s*(\d+)"
                                                     msg))]
                       (parse-long ch)))}))

(defn sync!
  "Bring an existing clj-p4 clone at `target` up to date with the server.

   Required:
     :conn   ConnectionSpec
     :stream stream depot path
     :target existing bare-repo path

   Optional: same as `clone!`."
  [{:keys [conn stream target ref exclude progress-fn stop?]
    :or   {ref         "refs/heads/main"
           progress-fn (fn [_])
           stop?       (constantly false)}}]
  (let [{:keys [mode]} (choose-mode conn)
        chain          (p4/stream-chain conn stream :mode mode)
        state          (repo-state target :ref ref)
        since          (or (:last-change state) 0)
        new-changes    (resolve-changes conn stream {:since (inc since)} mode)]
    (if (empty? new-changes)
      (assoc state :synced 0)
      (let [plan-val   (plan/sync-plan
                        {:conn         conn
                         :stream-chain chain
                         :changelists  new-changes
                         :target       target
                         :excludes     exclude
                         :since-change since
                         :options      {:checkpoint-every 1000}})
            marks-file (str (io/file target ".." "clj-p4.marks"))
            handle     (git/fast-import-start target {:marks-file marks-file})]
        (try
          (let [final (execute/execute! plan-val
                                        {:git-handle  handle
                                         :conn        conn
                                         :ref         ref
                                         :progress-fn progress-fn
                                         :stop?       stop?})
                {:keys [exit stderr]} (git/fast-import-close! handle)]
            (when-not (zero? exit)
              (throw (ex-info (str "git fast-import exited " exit)
                              {:clj-p4/error :fast-import-failed
                               :stderr stderr})))
            (assoc (repo-state target :ref ref)
                   :synced (count new-changes)))
          (catch Throwable t
            (try (git/fast-import-close! handle) (catch Exception _))
            (throw t)))))))
