(ns clj-p4.execute
  "Reduce a `clj-p4.plan` op-seq into a populated bare git repo.

   This is the only mutable layer. Every other namespace returns plain
   data; `execute!` is where blobs hit disk and commits become refs.

   JVM-only."
  (:require [clj-p4.plan :as plan]
            [clj-p4.shell.git :as git]
            [clj-p4.shell.p4 :as p4]
            [clj-p4.view :as view]
            [clj-p4.exclude :as exclude]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream)))

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
   out (view-excluded, view-ignored, or excludes-policy match)."
  [{:keys [view excludes]} {:rev/keys [depot] :as _fr}]
  (let [local (view/map-depot->local view depot)]
    (cond
      (#{:clj-p4.view/excluded
         :clj-p4.view/ignored
         :clj-p4.view/no-match} local)
      nil

      (excluded-by-policy? excludes local)
      nil

      :else local)))

(defn- fetch-blob-bytes!
  "Run `p4 print` for one file and return the bytes. Honours
   `:max-print-bytes` cap; throws ex-info if exceeded."
  [{:keys [conn max-print-bytes]} change fr]
  (let [baos (ByteArrayOutputStream.)]
    (p4/print-bytes! conn (str (:rev/depot fr) "@" change) baos
                     :keyword-expand? (keyword-expand? fr))
    (let [bs (.toByteArray baos)]
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
    (if (or (nil? paral) (<= paral 1))
      (mapv fetch-one ops)
      (vec (pmap fetch-one ops)))))

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
  [cl]
  {:name    (or (:p4/user cl) "unknown")
   :email   (str (or (:p4/user cl) "unknown") "@perforce")
   :time-ms (or (:p4/time cl) 0)
   :tz      "+0000"})

(defn- emit-change!
  [{:keys [git-handle ref last-change stream-name] :as ctx} change-num]
  (let [cl     (p4/describe (:conn ctx) change-num)
        ops    (file-ops-for-change ctx cl)
        commit {:ref       ref
                :mark      (:p4/change cl)
                :committer (committer-of cl)
                :message   (commit-message stream-name cl)
                :files     ops}
        commit (cond-> commit
                 last-change (assoc :from (str ":" last-change)))]
    (git/emit-commit! git-handle commit)
    (assoc ctx :last-change (:p4/change cl))))

(defn- step
  [ctx op]
  (case (:op/kind op)
    :process-change
    (emit-change! ctx (:op/change op))

    :checkpoint
    (do (git/checkpoint! (:git-handle ctx))
        (assoc ctx :checkpointed-at (:op/last-change op)))))

(defn- initial-ctx
  [plan {:keys [git-handle conn ref]}]
  (let [opts (:plan/options plan)]
    {:plan              plan
     :conn              conn
     :git-handle        git-handle
     :view              (:plan/view plan)
     :excludes          (:plan/excludes plan)
     :target            (:plan/target plan)
     :stream-name       (:stream/name (:plan/stream plan))
     :ref               (or ref "refs/heads/main")
     :fetch-parallelism (:fetch-parallelism opts)
     :max-print-bytes   (:max-print-bytes opts)
     :last-change       (when (= :sync (:plan/kind plan))
                          (:plan/since-change plan))}))

(defn execute!
  "Execute `plan` against a populated git repo at `(:plan/target plan)`.

   Required:
     :git-handle  open `git fast-import` handle (callee owns close).
     :conn        ConnectionSpec passed to `p4` invocations.

   Optional:
     :ref         git ref to write to (default `refs/heads/main`).
     :progress-fn `(fn [op])` — invoked before each op.
     :stop?       `(fn [])` — predicate; when true, halt after current op."
  [plan {:keys [git-handle conn ref progress-fn stop?]
         :or   {progress-fn (fn [_]) stop? (constantly false)}}]
  (transduce
   (comp (take-while (fn [_] (not (stop?))))
         (map (fn [op] (progress-fn op) op)))
   (completing step identity)
   (initial-ctx plan {:git-handle git-handle :conn conn :ref ref})
   (plan/operation-seq plan)))
