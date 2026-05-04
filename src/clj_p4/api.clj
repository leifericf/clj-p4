(ns clj-p4.api
  "Public facade: `clone!`, `sync!`, `repo-state`, `available?`, `clone?`.

   Each takes the data it needs explicitly — no opts god-map. Side effects
   delegated to `clj-p4.shell.*`; orchestration delegated to
   `clj-p4.execute`. JVM-only."
  (:require [clj-p4.execute :as execute]
            [clj-p4.plan :as plan]
            [clj-p4.shell.git :as git]
            [clj-p4.shell.p4 :as p4]
            [clj-p4.view :as view]
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

(defn- change-from-trailer
  "Extract the changelist number from a `[git-p4: depot-paths = \"...\":
   change = N]` trailer in a commit message. Returns nil if no trailer.
   Anchored on `[git-p4:` so user-written prose containing `change = N`
   does not produce false positives."
  [msg]
  (when msg
    (when-let [[_ ch] (re-find
                       #"\[git-p4:[^\]]*?change\s*=\s*(\d+)\s*\]"
                       msg)]
      (parse-long ch))))

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
  "List changelist numbers visible at `query-path` (already includes the
   trailing `/...`), sorted oldest-first."
  [conn query-path {:keys [max-changes since]} mode]
  (mapv :p4/change
        (sort-by :p4/change
                 (p4/changes conn query-path
                             :mode mode :max max-changes :since since))))

(defn- target-status
  "Classify `target` for `clone!` pre-flight: `:empty` (does not exist
   or exists as an empty directory), `:clj-p4-clone` (already populated
   by a clj-p4 / git-p4 clone), or `:not-empty` (exists with arbitrary
   contents — refuse to overlay)."
  [target]
  (let [f (io/file (str target))]
    (cond
      (not (.exists f))                       :empty
      (and (.isDirectory f)
           (zero? (alength (.list f))))       :empty
      (clone? target)                         :clj-p4-clone
      :else                                   :not-empty)))

(defn- run-fast-import!
  "Open a `git fast-import` handle, run `execute/execute!` over `plan-val`
   with the given fetch `conn`, and close the handle. Returns the final
   ctx (`:last-change`, etc.). Always closes the handle, even on failure."
  [plan-val target conn ref progress-fn stop?]
  (let [marks-file (str (io/file target "clj-p4.marks"))
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
        final)
      (catch Throwable t
        (try (git/fast-import-close! handle) (catch Exception _))
        (throw t)))))

(defn- assert-target-empty!
  [target]
  (case (target-status target)
    :clj-p4-clone
    (throw (ex-info (str "clone! target is already a clj-p4 clone: "
                         target " — use sync! to update it")
                    {:clj-p4/error :clone-target-is-clone
                     :target       target}))

    :not-empty
    (throw (ex-info (str "clone! target is not empty: " target
                         " — delete it first or choose a fresh path")
                    {:clj-p4/error :clone-target-not-empty
                     :target       target}))

    :empty nil))

(defn- virtual-leaf?
  "True if the leaf (last) stream in `chain` is a virtual stream. P4
   refuses queries to a virtual stream's depot path directly; the
   library routes those through an ephemeral client."
  [chain]
  (= :virtual (:stream/type (last chain))))

(defn- classic-depot-error?
  "True if `e` is a `:proc-failed` ex-info from `p4 stream -o` reporting
   that the path is not a stream — meaning it's a classic depot path."
  [e]
  (let [{:keys [stderr]} (ex-data e)]
    (and stderr
         (boolean (re-find #"(?i)not a stream|no such stream" (str stderr))))))

(defn- resolve-source
  "Decide whether `source` is a stream (returns `{:source-type :stream
   :chain <vec>}`) or a classic depot path (`{:source-type :classic
   :stream <synth-spec>}`)."
  [conn source mode]
  (try
    {:source-type :stream :chain (p4/stream-chain conn source :mode mode)}
    (catch clojure.lang.ExceptionInfo e
      (if (classic-depot-error? e)
        (let [stripped (str/replace source #"/\.\.\.$" "")]
          {:source-type :classic
           :stream      {:stream/name     stripped
                         :stream/parent   nil
                         :stream/type     :classic
                         :stream/options  #{}
                         :stream/paths    []
                         :stream/remapped []
                         :stream/ignored  []}})
        (throw e)))))

(defn- clone-via-classic!
  "Clone path for classic (non-stream) depot paths. Creates a generic
   ephemeral client whose `View:` maps the depot path to the client root,
   runs the import, tears the client down."
  [{:keys [conn stream target classic-stream mode max-changes exclude
           fetch-parallelism max-print-bytes
           ref progress-fn stop?]}]
  (p4/with-classic-client
    conn stream
    (fn [eph-conn {:client/keys [name view-lines]}]
      (let [client-path (str "//" name "/...")
            view-val    (view/client-view->view
                         view-lines
                         :stream-name (:stream/name classic-stream))
            changes     (resolve-changes eph-conn client-path
                                         {:max-changes max-changes} mode)
            plan-val    (plan/clone-plan
                         {:conn         eph-conn
                          :stream-chain [classic-stream]
                          :changelists  changes
                          :target       target
                          :view         view-val
                          :excludes     exclude
                          :options      (cond-> {:max-changes max-changes
                                                 :checkpoint-every 1000}
                                          fetch-parallelism (assoc :fetch-parallelism fetch-parallelism)
                                          max-print-bytes   (assoc :max-print-bytes max-print-bytes))})]
        (git/init-bare! target)
        (let [final (run-fast-import! plan-val target eph-conn ref
                                      progress-fn stop?)]
          {:target      target
           :commits     (count changes)
           :last-change (:last-change final)})))))

(defn- clone-via-ephemeral!
  "Clone path for streams that need an ephemeral client (virtual streams).
   Creates the client, builds the view from the server-filled `View:`
   block, runs the import, tears the client down."
  [{:keys [conn stream target chain mode max-changes exclude
           fetch-parallelism max-print-bytes
           ref progress-fn stop?]}]
  (p4/with-ephemeral-client
    conn stream
    (fn [eph-conn {:client/keys [name view-lines]}]
      (let [client-path (str "//" name "/...")
            view-val    (view/client-view->view view-lines :stream-name stream)
            changes     (resolve-changes eph-conn client-path
                                         {:max-changes max-changes} mode)
            plan-val    (plan/clone-plan
                         {:conn         eph-conn
                          :stream-chain chain
                          :changelists  changes
                          :target       target
                          :view         view-val
                          :excludes     exclude
                          :options      (cond-> {:max-changes max-changes
                                                 :checkpoint-every 1000}
                                          fetch-parallelism (assoc :fetch-parallelism fetch-parallelism)
                                          max-print-bytes   (assoc :max-print-bytes max-print-bytes))})]
        (git/init-bare! target)
        (let [final (run-fast-import! plan-val target eph-conn ref
                                      progress-fn stop?)]
          {:target      target
           :commits     (count changes)
           :last-change (:last-change final)})))))

(defn- clone-direct!
  "Clone path for streams whose depot path is queryable directly (mainline,
   development, release). The view comes from the parent-chain
   merge."
  [{:keys [conn stream target chain mode max-changes exclude
           fetch-parallelism max-print-bytes
           ref progress-fn stop?]}]
  (let [changes  (resolve-changes conn (str stream "/...")
                                  {:max-changes max-changes} mode)
        plan-val (plan/clone-plan
                  {:conn         conn
                   :stream-chain chain
                   :changelists  changes
                   :target       target
                   :excludes     exclude
                   :options      (cond-> {:max-changes max-changes
                                          :checkpoint-every 1000}
                                   fetch-parallelism (assoc :fetch-parallelism fetch-parallelism)
                                   max-print-bytes   (assoc :max-print-bytes max-print-bytes))})]
    (git/init-bare! target)
    (let [final (run-fast-import! plan-val target conn ref progress-fn stop?)]
      {:target      target
       :commits     (count changes)
       :last-change (:last-change final)})))

(defn clone!
  "Clone a Perforce source path into a new bare git repo at `target`.

   `:stream` may be:
   - a stream depot path (`\"//stream/main\"`) — mainline, development,
     release, or virtual; the parent chain is walked automatically.
   - a classic depot path (`\"//depot/main/src\"` or
     `\"//depot/main/src/...\"`) — for non-stream depots.

   Required:
     :conn         ConnectionSpec (`:p4/retries N` retries on transient
                   network/server failure; `:p4/timeout-ms` per call).
     :stream       depot path (stream OR classic — see above)
     :target       absolute path for the new bare repo

   Optional:
     :ref               target ref (default `refs/heads/main`)
     :max-changes       cap on changelists imported
     :exclude           compiled exclude patterns (vector of `[pat re]`)
     :fetch-parallelism N parallel `p4 print` calls per changelist (1 = sequential)
     :max-print-bytes   cap on per-file `p4 print` size; throws above
     :progress-fn       `(fn [op])` — invoked before each op
     :stop?             `(fn [])` — abort predicate

   Returns `{:target :commits :last-change}`.

   Refuses to clone into an existing non-empty `target` — either delete
   it first or, if it is already a clj-p4 clone, call `sync!`.

   Virtual streams and classic depot paths are both routed through an
   auto-managed, locked-down ephemeral client (`Options: noallwrite
   noclobber locked`). The `git-p4:` trailer always carries the
   user-supplied source path, not the ephemeral client name."
  [{:keys [conn stream target ref max-changes exclude
           fetch-parallelism max-print-bytes
           progress-fn stop?]
    :or   {ref         "refs/heads/main"
           progress-fn (fn [_])
           stop?       (constantly false)}}]
  (assert-target-empty! target)
  (let [{:keys [mode]} (choose-mode conn)
        {:keys [source-type chain] classic-stream :stream}
        (resolve-source conn stream mode)
        ctx (cond-> {:conn conn :stream stream :target target
                     :mode mode
                     :max-changes max-changes :exclude exclude
                     :fetch-parallelism fetch-parallelism
                     :max-print-bytes   max-print-bytes
                     :ref ref :progress-fn progress-fn :stop? stop?}
              chain          (assoc :chain chain)
              classic-stream (assoc :classic-stream classic-stream))]
    (cond
      (= :classic source-type)    (clone-via-classic! ctx)
      (virtual-leaf? chain)        (clone-via-ephemeral! ctx)
      :else                        (clone-direct! ctx))))

(defn- last-trailer-message
  "Body of the most recent commit on `ref` whose message matches the
   `[git-p4:` trailer prefix. Returns nil if no such commit exists.
   `git log --grep -E -1` walks history backwards itself, so a manual
   commit on top of a clj-p4 history doesn't hide the trailer below it."
  [target ref]
  (let [{:keys [exit stdout-bytes]}
        (clj-p4.shell.proc/run!
         ["git" "-C" (str target) "log" "-1" "-E"
          "--grep=\\[git-p4:" "--pretty=%B" ref])]
    (when (and (zero? exit) stdout-bytes)
      (let [s (String. ^bytes stdout-bytes "UTF-8")]
        (when-not (str/blank? s) s)))))

(defn repo-state
  "Inspect a clj-p4 clone. Returns
   `{:target :head-sha :commit-count :last-change}`."
  [target & {:keys [ref] :or {ref "refs/heads/main"}}]
  (let [shas (git/rev-list target ref)]
    {:target       target
     :head-sha     (first shas)
     :commit-count (count shas)
     :last-change  (change-from-trailer (last-trailer-message target ref))}))

(defn- sync-options
  [{:keys [fetch-parallelism max-print-bytes]}]
  (cond-> {:checkpoint-every 1000}
    fetch-parallelism (assoc :fetch-parallelism fetch-parallelism)
    max-print-bytes   (assoc :max-print-bytes max-print-bytes)))

(defn- sync-via-ephemeral!
  [{:keys [conn stream target chain mode since exclude
           ref progress-fn stop?] :as ctx}]
  (p4/with-ephemeral-client
    conn stream
    (fn [eph-conn {:client/keys [name view-lines]}]
      (let [client-path (str "//" name "/...")
            view-val    (view/client-view->view view-lines :stream-name stream)
            new-changes (resolve-changes eph-conn client-path
                                         {:since (inc since)} mode)]
        (if (empty? new-changes)
          (assoc (repo-state target :ref ref) :synced 0)
          (let [plan-val (plan/sync-plan
                          {:conn         eph-conn
                           :stream-chain chain
                           :changelists  new-changes
                           :target       target
                           :view         view-val
                           :excludes     exclude
                           :since-change since
                           :options      (sync-options ctx)})]
            (run-fast-import! plan-val target eph-conn ref progress-fn stop?)
            (assoc (repo-state target :ref ref)
                   :synced (count new-changes))))))))

(defn- sync-direct!
  [{:keys [conn stream target chain mode since exclude
           ref progress-fn stop?] :as ctx}]
  (let [new-changes (resolve-changes conn (str stream "/...")
                                     {:since (inc since)} mode)]
    (if (empty? new-changes)
      (assoc (repo-state target :ref ref) :synced 0)
      (let [plan-val (plan/sync-plan
                      {:conn         conn
                       :stream-chain chain
                       :changelists  new-changes
                       :target       target
                       :excludes     exclude
                       :since-change since
                       :options      (sync-options ctx)})]
        (run-fast-import! plan-val target conn ref progress-fn stop?)
        (assoc (repo-state target :ref ref)
               :synced (count new-changes))))))

(defn- sync-via-classic!
  [{:keys [conn stream target classic-stream mode since exclude
           ref progress-fn stop?] :as ctx}]
  (p4/with-classic-client
    conn stream
    (fn [eph-conn {:client/keys [name view-lines]}]
      (let [client-path (str "//" name "/...")
            view-val    (view/client-view->view
                         view-lines
                         :stream-name (:stream/name classic-stream))
            new-changes (resolve-changes eph-conn client-path
                                         {:since (inc since)} mode)]
        (if (empty? new-changes)
          (assoc (repo-state target :ref ref) :synced 0)
          (let [plan-val (plan/sync-plan
                          {:conn         eph-conn
                           :stream-chain [classic-stream]
                           :changelists  new-changes
                           :target       target
                           :view         view-val
                           :excludes     exclude
                           :since-change since
                           :options      (sync-options ctx)})]
            (run-fast-import! plan-val target eph-conn ref progress-fn stop?)
            (assoc (repo-state target :ref ref)
                   :synced (count new-changes))))))))

(defn sync!
  "Bring an existing clj-p4 clone at `target` up to date with the server.

   Required:
     :conn   ConnectionSpec
     :stream depot path (stream or classic — same shape as `clone!`)
     :target existing bare-repo path

   Optional: same as `clone!`. Virtual streams and classic depot paths
   are routed through the same auto-managed ephemeral-client path as
   `clone!`."
  [{:keys [conn stream target ref exclude
           fetch-parallelism max-print-bytes
           progress-fn stop?]
    :or   {ref         "refs/heads/main"
           progress-fn (fn [_])
           stop?       (constantly false)}}]
  (let [{:keys [mode]} (choose-mode conn)
        {:keys [source-type chain] classic-stream :stream}
        (resolve-source conn stream mode)
        state (repo-state target :ref ref)
        since (or (:last-change state) 0)
        ctx   (cond-> {:conn conn :stream stream :target target
                       :mode mode :since since
                       :exclude exclude
                       :fetch-parallelism fetch-parallelism
                       :max-print-bytes   max-print-bytes
                       :ref ref :progress-fn progress-fn :stop? stop?}
                chain          (assoc :chain chain)
                classic-stream (assoc :classic-stream classic-stream))]
    (cond
      (= :classic source-type) (sync-via-classic! ctx)
      (virtual-leaf? chain)    (sync-via-ephemeral! ctx)
      :else                    (sync-direct! ctx))))
