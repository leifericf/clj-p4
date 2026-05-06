(ns clj-p4.plan
  "Builds `ClonePlan` / `SyncPlan` values and the lazy op-seq that an
   executor reduces over.

   A plan is *data*: EDN-printable, deterministic, replayable. Inputs are
   already-resolved (stream chain fetched, changelist range computed,
   excludes given). The pure layer never performs I/O; the host-bound
   `clj-p4.io.*` namespaces are responsible for resolving those.

   Internally, `clone-plan` and `sync-plan` use the Perforce verb 'sync'
   for the incremental case — the API exposes the same operation as
   `fetch!`, but inside this layer we stay in P4 vocabulary."
  (:require [clj-p4.view :as view]))

(defn clone-plan
  "Build a `ClonePlan`.

   Required keys:
     :conn           ConnectionSpec
     :stream-chain   parent-first vector of StreamSpecs
     :changelists    vector of changelist numbers (oldest → newest)
     :target         absolute path to the local repo dir

   Optional keys:
     :view           pre-built `View` value, e.g. from
                     `clj-p4.view/client-view->view`. When given, takes
                     priority over `effective-view`-from-`stream-chain`.
                     Required path for virtual streams (where P4 has
                     already composed the view server-side).
     :excludes       vector of compiled exclude entries (output of
                     `clj-p4.excludes/compile-patterns`)
     :includes       vector of compiled include carve-out entries (same
                     shape as `:excludes`); a path is filtered out iff
                     some `:excludes` entry matches and no `:includes`
                     entry matches
     :options        free-form opts map (`:max-changes`, `:checkpoint-every`,
                     `:resume?`)"
  [{:keys [conn stream-chain changelists target view excludes includes options]}]
  {:plan/kind        :clone
   :plan/conn        conn
   :plan/source      (last stream-chain)
   :plan/view        (or view (view/effective-view stream-chain))
   :plan/changelists (vec changelists)
   :plan/excludes    (vec (or excludes []))
   :plan/includes    (vec (or includes []))
   :plan/target      target
   :plan/options     (or options {})})

(defn sync-plan
  "Build a `SyncPlan`. Like `clone-plan` but tagged `:sync` and carries
   `:plan/since-change` (the last imported changelist)."
  [{:keys [conn stream-chain changelists target view excludes includes
           options since-change]}]
  (assoc (clone-plan {:conn conn :stream-chain stream-chain
                      :changelists changelists :target target
                      :view view :excludes excludes :includes includes
                      :options options})
         :plan/kind         :sync
         :plan/since-change since-change))

(defn re-plan
  "Drop changelists at-or-before `last-imported` from `plan`, returning a new
   plan. Used to resume after a crash given a checkpoint."
  [plan last-imported]
  (update plan :plan/changelists
          (fn [cls]
            (vec (drop-while #(<= % last-imported) cls)))))

(defn- ops-for-change
  [idx change checkpoint? last?]
  (cond-> [{:op/kind :process-change :op/change change :op/idx idx}]
    (or checkpoint? last?)
    (conj {:op/kind :checkpoint :op/last-change change})))

(defn operation-seq
  "Lazy seq of `Op` maps for `plan`. One `:process-change` per changelist, plus
   `:checkpoint` ops every `:checkpoint-every` changes (default 1000) and at
   the end. `:process-change` carries `:op/idx` — the change's position in
   `:plan/changelists` — so a lookahead-aware executor can prefetch ahead.
   The executor is free to expand `:process-change` into finer-grained
   `:describe`/`:fetch-file`/`:emit-commit` work internally."
  [{:plan/keys [changelists options]}]
  (let [cls (vec changelists)
        n   (count cls)
        every (or (:checkpoint-every options) 1000)]
    (->> cls
         (map-indexed
          (fn [i change]
            (ops-for-change i change
                            (and (pos? (inc i)) (zero? (mod (inc i) every)))
                            (= (inc i) n))))
         (mapcat identity))))
