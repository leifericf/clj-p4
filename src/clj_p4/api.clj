(ns clj-p4.api
  "Public facade: `clone!`, `sync!`, `repo-state`, `available?`, `clone?`.

   Each takes the data it needs explicitly — no opts god-map. Side effects
   delegated to `clj-p4.shell.*`; orchestration delegated to
   `clj-p4.execute`."
  (:require [clj-p4.execute :as execute]
            [clj-p4.parse.marshal :as marshal]
            [clj-p4.plan :as plan]
            [clj-p4.schema :as schema]
            [clj-p4.shell.git :as git]
            [clj-p4.shell.p4 :as p4]
            [clj-p4.shell.proc :as proc]
            [clj-p4.view :as view]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(defn- validate-options!
  "Boundary check for `clone!` / `sync!` option maps. Throws ex-info with
   a humanized message when `args` doesn't conform to `schema-val`. The
   `:source` xor `:sources` constraint is enforced separately by the
   caller; the schema is intentionally loose on that pair."
  [schema-val args op-name]
  (when-not (m/validate schema-val args)
    (let [explanation (m/explain schema-val args)]
      (throw (ex-info (str op-name " options invalid: "
                           (me/humanize explanation))
                      {:clj-p4/error :invalid-options
                       :op           op-name
                       :explanation  explanation
                       :args         args})))))

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
               (proc/run! ["git" "-C" (str repo-path)
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
   trailing `/...`), sorted oldest-first.

   When `:changes-block-size` is set, walks the CL range in fixed-size
   windows so no single `p4 changes` call exceeds the server's per-group
   `MaxResults` limit. Without it, a single `p4 changes -m N` call
   pulls everything (subject to `:max-changes` if also set)."
  [conn query-path {:keys [max-changes since changes-block-size]} mode]
  (mapv :p4/change
        (sort-by :p4/change
                 (if changes-block-size
                   (p4/changes-blocked conn query-path
                                       :mode mode
                                       :since (or since 0)
                                       :block-size changes-block-size)
                   (p4/changes conn query-path
                               :mode mode :max max-changes :since since)))))

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

(defn- classic-depot-error?
  "True if `e` is a `:proc-failed` ex-info from `p4 stream -o` whose
   shape is consistent with the path being a classic (non-stream) depot.

   The error text comes back differently in different wire modes:
   `-G` (marshal) puts it in stderr as `not a stream`/`no such stream`;
   `-Mj` (JSON) emits the failure record on stdout, leaving stderr
   empty. Rather than try to enumerate every server-version-specific
   phrasing across both wire modes, we treat *any* `:proc-failed` from
   the stream-spec call as a candidate for the classic fallback —
   genuinely transient failures (auth, network) will surface again
   when `with-classic-client` tries to talk to the same server."
  [e]
  (= :proc-failed (:clj-p4/error (ex-data e))))

(defn- resolve-source
  "Decide whether `source` is a stream (returns `{:source-type :stream
   :chain <vec>}`) or a classic depot path (`{:source-type :classic
   :source <synth-stream-spec>}`)."
  [conn source mode]
  (try
    {:source-type :stream :chain (p4/stream-chain conn source :mode mode)}
    (catch clojure.lang.ExceptionInfo e
      (if (classic-depot-error? e)
        (let [stripped (str/replace source #"/\.\.\.$" "")]
          {:source-type :classic
           :source      {:stream/name     stripped
                         :stream/parent   nil
                         :stream/type     :classic
                         :stream/options  #{}
                         :stream/paths    []
                         :stream/remapped []
                         :stream/ignored  []}})
        (throw e)))))

(defn- build-options
  "Plan-options map shared by every clone and sync helper. Keys are
   only included when truthy on the source ctx, so a clone that didn't
   pass `:user-map` produces an options map without that key — keeps
   the plan EDN-printable without nil-valued keys."
  [{:keys [max-changes fetch-parallelism max-print-bytes user-map
           emit-labels? lookahead no-merge? keep-empty-commits?]}]
  (cond-> {:checkpoint-every 1000}
    max-changes         (assoc :max-changes max-changes)
    fetch-parallelism   (assoc :fetch-parallelism fetch-parallelism)
    max-print-bytes     (assoc :max-print-bytes max-print-bytes)
    user-map            (assoc :user-map user-map)
    emit-labels?        (assoc :emit-labels? emit-labels?)
    lookahead           (assoc :lookahead lookahead)
    no-merge?           (assoc :no-merge? no-merge?)
    keep-empty-commits? (assoc :keep-empty-commits? keep-empty-commits?)))

(defn- do-clone!
  "Shared body for the three clone-helper variants. `clone-conn` is the
   connection to issue P4 reads against (eph-conn for ephemeral paths,
   the user's conn for direct). `query-path` is the `<...>/...` path
   passed to `p4 changes`. `stream-chain` is the parent-first list of
   stream specs the executor uses for view composition. `view-val` is
   a pre-built `View` (auto-view paths) or nil to derive from the chain."
  [{:keys [target max-changes changes-block-size exclude ref
           progress-fn stop?] :as ctx}
   clone-conn query-path stream-chain view-val]
  (let [mode     (:mode ctx)
        changes  (resolve-changes clone-conn query-path
                                  {:max-changes max-changes
                                   :changes-block-size changes-block-size}
                                  mode)
        plan-val (plan/clone-plan
                  {:conn         clone-conn
                   :stream-chain stream-chain
                   :changelists  changes
                   :target       target
                   :view         view-val
                   :excludes     exclude
                   :options      (build-options ctx)})]
    (git/init-bare! target)
    (let [final (run-fast-import! plan-val target clone-conn ref
                                  progress-fn stop?)]
      {:target      target
       :commits     (count changes)
       :last-change (:last-change final)})))

(defn- clone-via-classic!
  "Clone path for classic (non-stream) depot paths. Creates a generic
   ephemeral client whose `View:` maps the depot path to the client root,
   runs the import, tears the client down."
  [{:keys [conn source classic-source] :as ctx}]
  (p4/with-classic-client
    conn source
    (fn [eph-conn {:client/keys [name view-lines]}]
      (let [view-val (view/client-view->view
                      view-lines :source-name (:stream/name classic-source))]
        (do-clone! ctx eph-conn (str "//" name "/...")
                   [classic-source] view-val)))))

(defn- run-ephemeral-import!
  "Body of an ephemeral-client clone for a stream. Caller provides the
   already-created client (`eph-conn`, `client-name`, `view-lines`).
   The `with-stream-client-or-fallback` dispatch in `clone-one!` is the
   only caller — it handles the ephemeral-client lifecycle."
  [{:keys [source chain] :as ctx} eph-conn client-name view-lines]
  (let [view-val (view/client-view->view view-lines :source-name source)]
    (do-clone! ctx eph-conn (str "//" client-name "/...")
               chain view-val)))

(defn- clone-direct!
  "Clone path for streams whose depot path is queryable directly (mainline,
   development, release). The view comes from the parent-chain merge."
  [{:keys [conn source chain] :as ctx}]
  (do-clone! ctx conn (str source "/...") chain nil))

(defn- default-ref-of-source
  "Stream basename → `refs/heads/<basename>`. `//stream/main` →
   `refs/heads/main`; `//depot/legacy/src/...` → `refs/heads/src`."
  [source]
  (let [stripped (str/replace source #"/\.\.\.$" "")
        segs     (remove str/blank? (str/split stripped #"/"))]
    (str "refs/heads/" (last segs))))

(defn- clone-one!
  "Clone a single source into the (already-empty-checked) `target` at
   `ref`. Marks file is shared at `<target>/clj-p4.marks`, so a
   multi-stream clone's subsequent imports automatically see prior
   imports' marks (e.g. for `merge :<C>` parent emission across streams).

   Dispatch (since 0.12.0):
   - Classic depot path → `clone-via-classic!` (generic ephemeral client).
   - Stream → ephemeral-client view by default (via
     `with-stream-client-or-fallback`) — picks up server-side
     `Components:` resolution and any other view composition the server
     does that pure-data `effective-view` would miss. Falls back to
     `clone-direct!` (pure-data parent-chain merge) only if ephemeral
     client creation fails (e.g. P4 protect table forbids `client -i`)."
  [{:keys [conn target] :as args} source ref]
  (let [{:keys [mode]} (choose-mode conn)
        {:keys [source-type chain] classic-source :source}
        (resolve-source conn source mode)
        ctx (cond-> (assoc args
                           :source source :ref ref :mode mode)
              chain          (assoc :chain chain)
              classic-source (assoc :classic-source classic-source))]
    (cond
      (= :classic source-type)
      (clone-via-classic! ctx)

      :else
      (or (p4/with-stream-client-or-fallback
            conn source
            (fn [eph-conn {:client/keys [name view-lines]}]
              (run-ephemeral-import! ctx eph-conn name view-lines))
            (constantly nil))
          (clone-direct! ctx)))))

(defn clone!
  "Clone one or more Perforce sources into a new bare git repo at `target`.

   `:source` may be:
   - a stream depot path (`\"//stream/main\"`) — mainline, development,
     release, virtual, task, sparsedev, or sparserel; the parent chain
     is walked automatically.
   - a classic depot path (`\"//depot/main/src\"` or
     `\"//depot/main/src/...\"`) — for non-stream depots.

   `:sources` is a vector of source paths cloned into the same repo.
   Each becomes its own ref; the default ref derives from the source's
   last path segment (`//stream/main` → `refs/heads/main`,
   `//stream/dev` → `refs/heads/dev`). Override per-source with
   `:source->ref {<source> <ref>}`. `:source` and `:sources` are
   mutually exclusive.

   Required:
     :conn         ConnectionSpec (`:p4/retries N` retries on transient
                   network/server failure; `:p4/timeout-ms` per call).
     :target       absolute path for the new bare repo
     :source       OR :sources — one of these.

   Optional:
     :ref               target ref (single-source only; default
                        `refs/heads/main`)
     :source->ref       per-source ref override (multi-source only)
     :max-changes       cap on changelists imported
     :exclude           compiled exclude patterns (vector of `[pat re]`)
     :fetch-parallelism N parallel `p4 print` calls per changelist (1 = sequential)
     :max-print-bytes   cap on per-file `p4 print` size; throws above
     :lookahead         N upcoming changelists to `p4 describe` in parallel
                        with the current commit's emission (0 = sequential)
     :no-merge?         disable integrate-as-merge detection (default: enabled)
     :progress-fn       `(fn [op])` — invoked before each op
     :stop?             `(fn [])` — abort predicate

   Returns `{:target :commits :last-change}` for the single-source form;
   a vector of those maps (one per source) for `:sources`.

   Refuses to clone into an existing non-empty `target` — either delete
   it first or, if it is already a clj-p4 clone, call `sync!`.

   Virtual streams and classic depot paths are both routed through an
   auto-managed, locked-down ephemeral client (`Options: noallwrite
   noclobber locked`). The `git-p4:` trailer always carries the
   user-supplied source path, not the ephemeral client name."
  [{:keys [conn target ref
           source sources source->ref
           max-changes exclude
           fetch-parallelism max-print-bytes user-map emit-labels?
           lookahead no-merge? progress-fn stop?]
    :or   {ref         "refs/heads/main"
           progress-fn (fn [_])
           stop?       (constantly false)}
    :as   args}]
  (validate-options! schema/clone-options args "clone!")
  (when (and source sources)
    (throw (ex-info ":source and :sources are mutually exclusive on clone!"
                    {:clj-p4/error :source-and-sources-set
                     :source  source
                     :sources sources})))
  (when-not (or source sources)
    (throw (ex-info "clone! needs :source or :sources"
                    {:clj-p4/error :no-source})))
  (assert-target-empty! target)
  (let [base (-> args
                 (dissoc :source :sources :source->ref :ref)
                 (assoc :progress-fn progress-fn :stop? stop?))
        decoder (marshal/metadata-decoder
                 (or (:metadata-decoding-strategy args) :strict)
                 (:metadata-fallback-encoding args))]
    (binding [marshal/*decode-string* decoder]
      (if sources
        (mapv (fn [src]
                (let [src-ref (or (get source->ref src)
                                  (default-ref-of-source src))]
                  (clone-one! base src src-ref)))
              sources)
        (clone-one! base source ref)))))

(defn- last-trailer-message
  "Body of the most recent commit on `ref` whose message matches the
   `[git-p4:` trailer prefix. Returns nil if no such commit exists.
   `git log --grep -E -1` walks history backwards itself, so a manual
   commit on top of a clj-p4 history doesn't hide the trailer below it."
  [target ref]
  (let [{:keys [exit stdout-bytes]}
        (proc/run!
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

(defn- do-sync!
  "Shared body for the three sync-helper variants. See `do-clone!` for
   the parameter shape — the only differences are that sync uses
   `:since` to filter `p4 changes`, builds a `sync-plan`, skips
   `init-bare!`, and folds the change count into the returned
   `repo-state`."
  [{:keys [target since changes-block-size exclude ref
           progress-fn stop?] :as ctx}
   sync-conn query-path stream-chain view-val]
  (let [mode        (:mode ctx)
        new-changes (resolve-changes sync-conn query-path
                                     {:since (inc since)
                                      :changes-block-size changes-block-size}
                                     mode)]
    (if (seq new-changes)
      (let [plan-val (plan/sync-plan
                      {:conn         sync-conn
                       :stream-chain stream-chain
                       :changelists  new-changes
                       :target       target
                       :view         view-val
                       :excludes     exclude
                       :since-change since
                       :options      (build-options ctx)})]
        (run-fast-import! plan-val target sync-conn ref progress-fn stop?)
        (assoc (repo-state target :ref ref)
               :synced (count new-changes)))
      (assoc (repo-state target :ref ref) :synced 0))))

(defn- run-ephemeral-sync!
  "Body of an ephemeral-client sync. Caller provides the live client."
  [{:keys [source chain] :as ctx} eph-conn client-name view-lines]
  (let [view-val (view/client-view->view view-lines :source-name source)]
    (do-sync! ctx eph-conn (str "//" client-name "/...")
              chain view-val)))

(defn- sync-direct!
  [{:keys [conn source chain] :as ctx}]
  (do-sync! ctx conn (str source "/...") chain nil))

(defn- sync-via-classic!
  [{:keys [conn source classic-source] :as ctx}]
  (p4/with-classic-client
    conn source
    (fn [eph-conn {:client/keys [name view-lines]}]
      (let [view-val (view/client-view->view
                      view-lines :source-name (:stream/name classic-source))]
        (do-sync! ctx eph-conn (str "//" name "/...")
                  [classic-source] view-val)))))

(defn- sync-one!
  "Sync a single source into `target` on `ref`. Helper used by both
   single and multi-stream `sync!`."
  [{:keys [conn target] :as args} source ref]
  (let [{:keys [mode]} (choose-mode conn)
        {:keys [source-type chain] classic-source :source}
        (resolve-source conn source mode)
        state (repo-state target :ref ref)
        since (or (:last-change state) 0)
        ctx   (cond-> (assoc args
                             :source source :ref ref :mode mode :since since)
                chain          (assoc :chain chain)
                classic-source (assoc :classic-source classic-source))]
    (cond
      (= :classic source-type)
      (sync-via-classic! ctx)

      :else
      (or (p4/with-stream-client-or-fallback
            conn source
            (fn [eph-conn {:client/keys [name view-lines]}]
              (run-ephemeral-sync! ctx eph-conn name view-lines))
            (constantly nil))
          (sync-direct! ctx)))))

(defn sync!
  "Bring an existing clj-p4 clone at `target` up to date with the server.

   Required:
     :conn   ConnectionSpec
     :target existing bare-repo path
     :source OR :sources — same shape as `clone!`. `:sources` syncs
              each source against its respective ref (default
              `refs/heads/<basename>`; override with `:source->ref`).

   Optional: same as `clone!`. Virtual streams and classic depot paths
   are routed through the same auto-managed ephemeral-client path as
   `clone!`."
  [{:keys [conn target ref
           source sources source->ref
           exclude
           fetch-parallelism max-print-bytes user-map lookahead no-merge?
           progress-fn stop?]
    :or   {ref         "refs/heads/main"
           progress-fn (fn [_])
           stop?       (constantly false)}
    :as   args}]
  (validate-options! schema/sync-options args "sync!")
  (when (and source sources)
    (throw (ex-info ":source and :sources are mutually exclusive on sync!"
                    {:clj-p4/error :source-and-sources-set
                     :source  source
                     :sources sources})))
  (when-not (or source sources)
    (throw (ex-info "sync! needs :source or :sources"
                    {:clj-p4/error :no-source})))
  (let [base (-> args
                 (dissoc :source :sources :source->ref :ref)
                 (assoc :progress-fn progress-fn :stop? stop?))
        decoder (marshal/metadata-decoder
                 (or (:metadata-decoding-strategy args) :strict)
                 (:metadata-fallback-encoding args))]
    (binding [marshal/*decode-string* decoder]
      (if sources
        (mapv (fn [src]
                (let [src-ref (or (get source->ref src)
                                  (default-ref-of-source src))]
                  (sync-one! base src src-ref)))
              sources)
        (sync-one! base source ref)))))
