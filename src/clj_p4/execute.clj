(ns clj-p4.execute
  "Reduce a `clj-p4.plan` op-seq into a populated bare git repo.

   This is the only mutable layer. Every other namespace returns plain
   data; `execute!` is where blobs hit disk and commits become refs."
  (:require [clj-p4.exclude :as exclude]
            [clj-p4.parse.depot-path :as depot-path]
            [clj-p4.plan :as plan]
            [clj-p4.shell.git :as git]
            [clj-p4.shell.p4 :as p4]
            [clj-p4.view :as view]
            [clojure.core.async :as a]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream)))

(defn- bounded-pmap
  "Apply `f` across `coll` using up to `n` worker threads, preserving
   input order. Falls back to `mapv` for `n <= 1`. The first exception
   thrown by any worker propagates to the caller; partial results are
   discarded, and after the first failure subsequent items
   short-circuit without invoking `f` so a doomed run stops issuing
   work as quickly as possible.

   Backed by `core.async/pipeline-blocking`, which is the idiomatic
   primitive for blocking I/O fan-out: ordered, bounded, and uses one
   dedicated thread per worker (no go-thread parking)."
  [n f coll]
  (if (or (nil? n) (<= n 1))
    (mapv f coll)
    (let [err (atom nil)
          in  (a/to-chan! coll)
          out (a/chan)
          xf  (map (fn [x]
                     (if @err
                       ::skipped
                       (try (f x)
                            (catch Throwable t
                              (compare-and-set! err nil t)
                              (a/close! in)
                              ::skipped)))))]
      (a/pipeline-blocking n out xf in)
      (let [results (a/<!! (a/into [] out))]
        (when-let [t @err] (throw t))
        results))))

(def ^:private blob-mark-base 1000000000)

(defn- blob-mark
  "Deterministic blob mark from changelist + file-index, well above any
   plausible commit-mark value (commit marks are p4 changelist numbers)."
  [change file-idx]
  (+ blob-mark-base (* (long change) 10000) (long file-idx)))

(defn- file-mode
  [{:rev/keys [type flags]}]
  (cond
    (= type :symlink)     "120000"
    (contains? flags :x)  "100755"
    :else                 "100644"))

(defn- keyword-expand?
  "True if `p4 print` should let the server expand RCS keywords; false to
   pass `-k` so bytes round-trip unchanged on subsequent syncs."
  [{:rev/keys [keyword-flags]}]
  (not (or (contains? keyword-flags :k)
           (contains? keyword-flags :ko))))

(defn- excluded-by-policy?
  [excludes local-path]
  (and (seq excludes) (exclude/matches-any? excludes local-path)))

(defn- map-rev->local
  "Return the local path for a FileRev, or `nil` if the file is filtered
   out (view-excluded, view-ignored, or excludes-policy match).

   The depot path may carry Perforce's `%XX` escapes for `@`, `#`, `*`,
   `%` (and any other reserved char). View mapping passes those through
   verbatim, so the local path can still contain escapes when we get
   here. The exclusion check runs against the *unescaped* path so user
   patterns match human-readable filenames; the result returned to
   git-fast-import is also unescaped, since git stores the literal
   filename, not the p4 wire-format spelling."
  [{:keys [view excludes]} {:rev/keys [depot] :as _fr}]
  (let [local (view/map-depot->local view depot)]
    (cond
      (#{:clj-p4.view/excluded
         :clj-p4.view/ignored
         :clj-p4.view/no-match} local)
      nil

      :else
      (let [unescaped (depot-path/unescape local)]
        (when-not (excluded-by-policy? excludes unescaped)
          unescaped)))))

(defn- strip-trailing-newline
  "Return `bs` minus one trailing `\\n` byte (and an optional preceding
   `\\r` for CRLF), or the array unchanged if it has no trailing newline.
   Used only for symlink blobs: p4 stores the link target with a newline
   appended, but git's symlink convention is for the blob content to be
   exactly the target path with no terminator."
  ^bytes [^bytes bs]
  (let [n (alength bs)]
    (cond
      (and (>= n 2) (= (aget bs (- n 1)) (byte \newline))
                    (= (aget bs (- n 2)) (byte \return)))
      (java.util.Arrays/copyOf bs (- n 2))

      (and (pos? n) (= (aget bs (- n 1)) (byte \newline)))
      (java.util.Arrays/copyOf bs (- n 1))

      :else bs)))

(defn- fetch-blob-bytes!
  "Run `p4 print` for one file and return the bytes. Honours
   `:max-print-bytes` cap; throws ex-info if exceeded.

   Symlink revs get a trailing-newline strip: p4 ships the link target
   with a `\\n` terminator that must not survive into git, since git
   stores the literal target path as the blob content."
  [{:keys [conn max-print-bytes]} change fr]
  (let [baos (ByteArrayOutputStream.)]
    (p4/print-bytes! conn (str (:rev/depot fr) "@" change) baos
                     :keyword-expand? (keyword-expand? fr))
    (let [raw (.toByteArray baos)
          bs  (if (= :symlink (:rev/type fr))
                (strip-trailing-newline raw)
                raw)]
      (when (and max-print-bytes (> (alength bs) (long max-print-bytes)))
        (throw (ex-info (str "p4 print exceeded :max-print-bytes ("
                             max-print-bytes ")")
                        {:clj-p4/error :max-print-bytes-exceeded
                         :depot        (:rev/depot fr)
                         :size         (alength bs)
                         :max          max-print-bytes})))
      bs)))

(defn- pair-moves
  "Pair `:move/delete` and `:move/add` entries in `files` by `:rev/moved-file`.
   Returns `{:pairs {<add-depot> <delete-fr>} :paired #{<depot>...}}` —
   `paired` lists every depot path absorbed into a pair (so the main
   loop can skip those). When a pair's partner is missing (e.g. one
   side was filtered out by the view), neither end is paired."
  [files]
  (let [add-by-source    (->> files
                              (filter #(= :move/add (:rev/action %)))
                              (filter :rev/moved-file)
                              (map (juxt :rev/moved-file identity))
                              (into {}))
        delete-by-depot  (->> files
                              (filter #(= :move/delete (:rev/action %)))
                              (map (juxt :rev/depot identity))
                              (into {}))
        pairs (->> add-by-source
                   (keep (fn [[delete-depot add-fr]]
                           (when-let [del-fr (get delete-by-depot delete-depot)]
                             [(:rev/depot add-fr) {:add-fr add-fr
                                                   :delete-fr del-fr}])))
                   (into {}))
        paired (reduce-kv (fn [acc add-depot {:keys [delete-fr]}]
                            (-> acc
                                (conj add-depot)
                                (conj (:rev/depot delete-fr))))
                          #{} pairs)]
    {:pairs pairs :paired paired}))

(defn- materialize-ops
  "Walk a changelist's files and decide what fast-import op each one
   produces — without doing any I/O. Returns a vector of op maps:
   - `{:op :M :mode :path :fr :file-idx}` (modify; bytes fetched later)
   - `{:op :D :path}` (delete)
   - `{:op :R :from :to}` (rename; emitted in addition to a :M when the
     paired :move/add carries new content)

   Move pairs may produce `[:R ...] [:M ...]` together so that move+edit
   commits keep the new content. The `:M` is always added: fast-import
   tolerates redundant content writes after `R`, and we cannot tell
   without comparing bytes whether the move was content-preserving."
  [ctx {:keys [p4/files]}]
  (let [{:keys [pairs paired]} (pair-moves files)]
    (loop [out [], i 0, fs files]
      (if-let [fr (first fs)]
        (let [depot (:rev/depot fr)
              local (map-rev->local ctx fr)]
          (cond
            (nil? local)
            (recur out (inc i) (rest fs))

            (and (= :move/add (:rev/action fr))
                 (contains? pairs depot))
            (let [{:keys [delete-fr]} (get pairs depot)
                  old-local (map-rev->local ctx delete-fr)]
              (if (and old-local (not= old-local local))
                (recur (-> out
                           (conj {:op :R :from old-local :to local})
                           (conj {:op :M :mode (file-mode fr) :path local
                                  :fr fr :file-idx i}))
                       (inc i) (rest fs))
                ;; partner mapped out by view — emit a plain modify only
                (recur (conj out {:op :M :mode (file-mode fr) :path local
                                  :fr fr :file-idx i})
                       (inc i) (rest fs))))

            (and (= :move/delete (:rev/action fr))
                 (contains? paired depot))
            ;; Already absorbed by the partner :move/add.
            (recur out (inc i) (rest fs))

            (#{:delete :move/delete} (:rev/action fr))
            (recur (conj out {:op :D :path local}) (inc i) (rest fs))

            :else
            (recur (conj out {:op :M :mode (file-mode fr) :path local
                              :fr fr :file-idx i})
                   (inc i) (rest fs))))
        out))))

(defn- fetch-blobs!
  "Fetch bytes for every `:M` op in `ops` using up to `paral` workers.
   Returns ops with `:bytes` populated on each `:M`. Sequential
   (`paral <= 1`) preserves the simple call shape and any error
   propagates through the same stack the caller expects."
  [ctx change ops paral]
  (let [fetch-one (fn [op]
                    (if (= :M (:op op))
                      (assoc op :bytes (fetch-blob-bytes! ctx change (:fr op)))
                      op))]
    (bounded-pmap paral fetch-one ops)))

(defn- emit-ops!
  "Walk `ops` (already byte-loaded) and serialise them into fast-import.
   Writes the `M` ops' blobs first; returns a vector of op maps shaped
   for `git/emit-commit!`'s `:files` key."
  [{:keys [git-handle]} change ops]
  (mapv (fn [op]
          (case (:op op)
            :D {:op :D :path (:path op)}
            :R {:op :R :from (:from op) :to (:to op)}
            :M (let [mark (blob-mark change (:file-idx op))]
                 (git/blob! git-handle mark (:bytes op))
                 {:op :M :mode (:mode op) :mark mark :path (:path op)})))
        ops))

(defn- file-ops-for-change
  "Phase orchestration: materialize → parallel-fetch → serial-emit."
  [ctx {:keys [p4/change] :as cl}]
  (let [paral (:fetch-parallelism ctx)
        ops   (materialize-ops ctx cl)
        ops'  (fetch-blobs! ctx change ops paral)]
    (emit-ops! ctx change ops')))

(defn- p4-trailer
  "Format the git-p4-compatible commit trailer. Uses the stream name the
   plan was built with — `parse-changelist` cannot populate `:p4/stream`
   reliably because some `p4 describe` outputs omit it."
  [stream-name {:p4/keys [change]}]
  (str "[git-p4: depot-paths = \"" stream-name "/\": change = " change "]"))

(defn- commit-message
  [stream-name cl]
  (let [desc (str/trim (or (:p4/desc cl) ""))]
    (if (str/blank? desc)
      (p4-trailer stream-name cl)
      (str desc "\n\n" (p4-trailer stream-name cl)))))

(defn- committer-of
  "Build the committer record for a changelist. When `user-map` carries
   an entry for `(:p4/user cl)`, use that entry's `:name` and `:email`;
   otherwise fall back to `<user>@perforce`."
  [user-map cl]
  (let [user   (or (:p4/user cl) "unknown")
        mapped (get user-map user)]
    {:name    (or (:name mapped) user)
     :email   (or (:email mapped) (str user "@perforce"))
     :time-ms (or (:p4/time cl) 0)
     :tz      "+0000"}))

(defn- prime-describes!
  "Maintain up to `lookahead + 1` in-flight `p4 describe` futures starting
   at `from-idx` in `:changelists`. The `+ 1` covers the change we're
   about to consume — its describe runs in parallel with whatever
   `emit-change!` is doing for the previous change. No-op when
   `lookahead` is nil or zero."
  [{:keys [conn lookahead describes changelists]} from-idx]
  (when (and lookahead (pos? lookahead))
    (let [n       (count changelists)
          end-idx (min n (+ from-idx lookahead 1))]
      (doseq [i (range from-idx end-idx)
              :let [c (nth changelists i)]
              :when (not (contains? @describes c))]
        (swap! describes assoc c (future (p4/describe conn c)))))))

(defn- describe-cached
  "Return the resolved `describe` for `change`, draining the cached
   future if one exists. Falls back to a synchronous `p4/describe` when
   lookahead is off or the cache misses."
  [{:keys [conn describes]} change]
  (if-let [fut (and describes (get @describes change))]
    (let [result @fut]
      (swap! describes dissoc change)
      result)
    (p4/describe conn change)))

(defn- cancel-pending-describes!
  "Best-effort: cancel any outstanding describe futures. Called when the
   executor unwinds on error so a hung `p4` doesn't keep the JVM alive."
  [{:keys [describes]}]
  (when describes
    (run! future-cancel (vals @describes))
    (reset! describes {})))

(defn- merge-source-for-cl
  "Given a changelist `cl`, look up the merge-source change number — the
   change a majority of the CL's `:integrate` files were branched/merged
   from — or nil. Returns nil when:
   - no `:integrate` files in the CL, or
   - lookups returned no candidates, or
   - no single source change holds a strict majority of integrates, or
   - the majority winner isn't in `imported-set`.

   Two RPCs per integrate file: `p4 integrated -F change=<C>` to find
   the source path + rev, then `p4 fstat <source>#<rev>` to find the
   change that produced that rev. Fanned out via `bounded-pmap` so the
   cost is bounded by `:fetch-parallelism`."
  [{:keys [conn fetch-parallelism]} {:p4/keys [change files]} imported?]
  (let [integrate-files (filter #(= :integrate (:rev/action %)) files)
        lookup-source
        (fn [fr]
          (try
            (let [rows (p4/integrated conn change (:rev/depot fr))
                  row  (first rows)]
              (when (and row
                         (:integ/from-file row)
                         (:integ/end-from-rev row))
                (:fstat/head-change
                 (p4/fstat conn
                           (str (:integ/from-file row)
                                "#"
                                (:integ/end-from-rev row))))))
            (catch Exception _ nil)))]
    (when (seq integrate-files)
      (let [sources (->> integrate-files
                         (bounded-pmap fetch-parallelism lookup-source)
                         (filter some?)
                         (filter imported?))]
        (when (seq sources)
          (let [counts        (frequencies sources)
                [winner cnt]  (apply max-key val counts)]
            (when (> (* 2 cnt) (count integrate-files))
              winner)))))))

(defn- emit-change!
  "Emit one fast-import commit for `change-num`. When the CL produces no
   file ops after view + exclude filtering, the commit is skipped unless
   `:keep-empty-commits?` is set on the plan options. The skip leaves
   `:last-change` unchanged, so the next emitted commit chains its
   `from` parent off the previous *emitted* CL — preserving a contiguous
   git history over the touching changes only.

   Default is to skip (matches git-p4's default — `--keep-empty-commits`
   turns keeping on)."
  [{:keys [git-handle ref last-change stream-name user-map
           no-merge? imported? keep-empty-commits?] :as ctx} change-num]
  (let [cl  (describe-cached ctx change-num)
        ops (file-ops-for-change ctx cl)]
    (if (and (empty? ops) (not keep-empty-commits?))
      ctx
      (let [merge-src (when-not no-merge?
                        (merge-source-for-cl ctx cl
                                             (or imported? (constantly false))))
            commit {:ref       ref
                    :mark      (:p4/change cl)
                    :committer (committer-of user-map cl)
                    :message   (commit-message stream-name cl)
                    :files     ops}
            commit (cond-> commit
                     last-change (assoc :from (str ":" last-change))
                     merge-src   (assoc :merge [(str ":" merge-src)]))]
        (git/emit-commit! git-handle commit)
        (assoc ctx :last-change (:p4/change cl))))))

(defn- step
  [ctx op]
  (case (:op/kind op)
    :process-change
    (do (prime-describes! ctx (or (:op/idx op) 0))
        (emit-change! ctx (:op/change op)))

    :checkpoint
    (do (git/checkpoint! (:git-handle ctx))
        (assoc ctx :checkpointed-at (:op/last-change op)))))

(defn- initial-ctx
  [plan {:keys [git-handle conn ref]}]
  (let [opts      (:plan/options plan)
        lookahead (:lookahead opts)
        new-set   (set (:plan/changelists plan))
        since     (when (= :sync (:plan/kind plan))
                    (:plan/since-change plan))]
    {:plan              plan
     :conn              conn
     :git-handle        git-handle
     :view              (:plan/view plan)
     :excludes          (:plan/excludes plan)
     :target            (:plan/target plan)
     :stream-name       (:stream/name (:plan/source plan))
     :ref               (or ref "refs/heads/main")
     :fetch-parallelism (:fetch-parallelism opts)
     :max-print-bytes   (:max-print-bytes opts)
     :user-map          (:user-map opts)
     :lookahead         lookahead
     :changelists       (vec (:plan/changelists plan))
     :describes         (when (and lookahead (pos? lookahead)) (atom {}))
     :no-merge?         (boolean (:no-merge? opts))
     :keep-empty-commits? (boolean (:keep-empty-commits? opts))
     :imported?         (fn [c] (or (contains? new-set c)
                                    (and since (some-> c (<= since)))))
     :last-change       since}))

(defn- parse-revision-change
  "Pull a changelist number out of a label's `Revision:` field. Common
   shapes: `@1234`, `1234`, `//depot/foo/...@1234`. Returns nil if the
   revision can't be coerced to a single change number."
  [^String rev]
  (when rev
    (when-let [[_ n] (re-find #"@?(\d+)\s*$" (str/trim rev))]
      (parse-long n))))

(defn- emit-labels!
  "After the main import, walk `p4 labels`, fetch each spec via
   `p4 label -o`, and emit a fast-import tag block for any label whose
   `Revision:` resolves to a changelist number we just imported. Labels
   that point at unimported or unparseable revisions are skipped
   silently (a label can legitimately tag a single file revision rather
   than a whole CL)."
  [{:keys [git-handle conn ref] :as ctx} imported-changes]
  (let [imported (set imported-changes)
        labels   (try (p4/labels conn) (catch Exception _ []))]
    (doseq [{:label/keys [name]} labels
            :when name
            :let [spec   (try (p4/label-spec conn name) (catch Exception _ nil))
                  change (parse-revision-change (:label/revision spec))]
            :when (and change (contains? imported change))]
      (git/emit-tag! git-handle
                     {:name name
                      :from (str ":" change)
                      :tagger {:name    "clj-p4"
                               :email   "clj-p4@local"
                               :time-ms 0
                               :tz      "+0000"}
                      :message (or (:label/desc spec) name)}))
    ctx))

(defn execute!
  "Execute `plan` against a populated git repo at `(:plan/target plan)`.

   Required:
     :git-handle  open `git fast-import` handle (callee owns close).
     :conn        ConnectionSpec passed to `p4` invocations.

   Optional:
     :ref         git ref to write to (default `refs/heads/main`).
     :progress-fn `(fn [op])` — invoked before each op.
     :stop?       `(fn [])` — predicate; when true, halt after current op.

   When `:emit-labels?` is true on `(:plan/options plan)`, walks
   `p4 labels` after the main import and emits annotated git tags for
   any label whose `Revision:` resolves to an imported changelist."
  [plan {:keys [git-handle conn ref progress-fn stop?]
         :or   {progress-fn (fn [_]) stop? (constantly false)}}]
  (let [opts  (:plan/options plan)
        ctx0  (initial-ctx plan {:git-handle git-handle :conn conn :ref ref})
        final (try
                (transduce
                 (comp (take-while (fn [_] (not (stop?))))
                       (map (fn [op] (progress-fn op) op)))
                 (completing step identity)
                 ctx0
                 (plan/operation-seq plan))
                (catch Throwable t
                  (cancel-pending-describes! ctx0)
                  (throw t)))]
    (when (:emit-labels? opts)
      (emit-labels! final (:plan/changelists plan)))
    final))
