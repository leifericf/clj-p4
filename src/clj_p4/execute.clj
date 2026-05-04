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

(defn- emit-blob-and-modify!
  "Stream `p4 print` bytes for one file into fast-import as a blob, then
   return the corresponding `M`-op for the commit."
  [{:keys [conn git-handle]} change file-idx fr local]
  (let [mark   (blob-mark change file-idx)
        baos   (ByteArrayOutputStream.)]
    (p4/print-bytes! conn (str (:rev/depot fr) "@" change) baos
                     :keyword-expand? (keyword-expand? fr))
    (git/blob! git-handle mark (.toByteArray baos))
    {:op :M :mode (file-mode fr) :mark mark :path local}))

(defn- file-ops-for-change
  "Build the ordered vector of fast-import file ops for one change.
   Files that map to no local path (excluded/ignored/no-match) are skipped."
  [ctx {:keys [p4/change p4/files]}]
  (loop [out [], i 0, fs files]
    (if-let [fr (first fs)]
      (let [local (map-rev->local ctx fr)]
        (cond
          (nil? local)
          (recur out (inc i) (rest fs))

          (#{:delete :move/delete} (:rev/action fr))
          (recur (conj out {:op :D :path local}) (inc i) (rest fs))

          :else
          (recur (conj out (emit-blob-and-modify! ctx change i fr local))
                 (inc i) (rest fs))))
      out)))

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
  {:plan        plan
   :conn        conn
   :git-handle  git-handle
   :view        (:plan/view plan)
   :excludes    (:plan/excludes plan)
   :target      (:plan/target plan)
   :stream-name (:stream/name (:plan/stream plan))
   :ref         (or ref "refs/heads/main")
   :last-change (when (= :sync (:plan/kind plan))
                  (:plan/since-change plan))})

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
