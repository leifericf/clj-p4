(ns clj-p4.view
  "Stream view composition: parent-chain inheritance, Paths/Remapped/Ignored
   handling, glob compilation, and depot → local-path mapping.

   This is the value-add over `git-p4`. Pure `.cljc`."
  (:require [clojure.string :as str]))

(defn- regex-escape [s]
  (str/replace s #"[.\\+*?\[\]\^\$\(\)\{\}\|]" "\\\\$0"))

(defn glob->tokens
  "Tokenise a P4-style glob into `[:literal s] :star :ellipsis [:percent n]`.
   `*` matches non-slash; `...` matches anything; `%n` (n=1..9) is a positional
   placeholder (treated like `*` for matching, kept as `[:percent n]` for
   rewrite substitution)."
  [glob]
  (loop [i 0, acc []]
    (let [n (count glob)]
      (cond
        (>= i n)
        (->> acc
             (partition-by #(or (= :star %) (= :ellipsis %) (vector? %)))
             (mapcat (fn [chunk]
                       (if (and (string? (first chunk)) (every? string? chunk))
                         [[:literal (apply str chunk)]]
                         chunk)))
             vec)

        (and (<= (+ i 3) n) (= "..." (subs glob i (+ i 3))))
        (recur (+ i 3) (conj acc :ellipsis))

        (= "*" (subs glob i (inc i)))
        (recur (inc i) (conj acc :star))

        (and (< (inc i) n)
             (= "%" (subs glob i (inc i)))
             (re-matches #"\d" (subs glob (inc i) (+ i 2))))
        (recur (+ i 2) (conj acc [:percent (parse-long (subs glob (inc i) (+ i 2)))]))

        :else
        (recur (inc i) (conj acc (subs glob i (inc i))))))))

(defn tokens->re
  "Tokens → regex pattern with each wildcard token a capture group, anchored."
  [tokens]
  (let [body (str/join
              (for [t tokens]
                (cond
                  (= :ellipsis t)            "(.*)"
                  (= :star t)                "([^/]*)"
                  (and (vector? t)
                       (= :percent (first t))) "([^/]*)"
                  (and (vector? t)
                       (= :literal (first t))) (regex-escape (second t))
                  :else                       (regex-escape (str t)))))]
    (re-pattern (str "^" body "$"))))

(defn glob->re
  "Convenience: compile a glob string straight to its anchored regex."
  [glob]
  (tokens->re (glob->tokens glob)))

(defn subst-tokens
  "Substitute `captures` (vector of strings) into `tokens`'s wildcard slots in
   order; literal tokens pass through. For mapping captures from one glob
   (depot) into another (local) with parallel wildcard structure."
  [tokens captures]
  (loop [out []
         i 0
         ts tokens]
    (if-let [t (first ts)]
      (cond
        (or (= :star t) (= :ellipsis t)
            (and (vector? t) (= :percent (first t))))
        (recur (conj out (nth captures i "")) (inc i) (rest ts))

        (and (vector? t) (= :literal (first t)))
        (recur (conj out (second t)) i (rest ts))

        :else
        (recur (conj out (str t)) i (rest ts)))
      (apply str out))))

(defn- view-path-of
  "The view-relative path slot of a parsed path entry."
  [[kind a _b]]
  (case kind
    (:share :isolate :exclude) a
    (:import :import+)         a))

(defn- depot-glob-for-path
  "The depot-side glob string for a path entry, given the stream's depot name."
  [stream-name [kind a b]]
  (case kind
    (:share :isolate :exclude) (str stream-name "/" a)
    (:import :import+)         b))

(defn- compile-path-entry
  "Compile one parsed path-entry (e.g. `[:share \"src/...\"]`) into a view entry:
   `{:kind (:include|:exclude) :source <kind> :match-fn :rewrite-fn :raw}`."
  [stream-name entry]
  (let [[kind] entry
        depot-glob   (depot-glob-for-path stream-name entry)
        view-glob    (view-path-of entry)
        depot-tokens (glob->tokens depot-glob)
        view-tokens  (glob->tokens view-glob)
        re           (tokens->re depot-tokens)]
    {:kind       (if (= kind :exclude) :exclude :include)
     :source     kind
     :raw        entry
     :match-fn   (fn match [depot-file]
                   (when-let [m (re-matches re depot-file)]
                     (if (sequential? m) (vec (rest m)) [])))
     :rewrite-fn (fn rewrite [captures]
                   (subst-tokens view-tokens captures))}))

(defn- override-paths
  "Child paths with the same view-path as parent paths replace them.
   New child paths append. Parent-only paths preserved."
  [parent-paths child-paths]
  (let [child-keys (set (map view-path-of child-paths))]
    (vec (concat (remove (comp child-keys view-path-of) parent-paths)
                 child-paths))))

(defn merge-streams
  "Merge `child` stream-spec on top of `parent`. Honours `:no-fromparent`
   on child (when set, child fully replaces parent's paths/remapped/ignored)."
  [parent child]
  (let [no-from? (contains? (:stream/options child) :no-fromparent)]
    (-> child
        (assoc :stream/paths
               (if no-from?
                 (vec (:stream/paths child))
                 (override-paths (:stream/paths parent) (:stream/paths child))))
        (assoc :stream/remapped
               (vec (concat (when-not no-from? (:stream/remapped parent))
                            (:stream/remapped child))))
        (assoc :stream/ignored
               (vec (concat (when-not no-from? (:stream/ignored parent))
                            (:stream/ignored child)))))))

(defn collapse-chain
  "Reduce a parent-first stream chain into a single effective stream-spec.
   `chain` order: `[oldest-ancestor … this-stream]`."
  [chain]
  (reduce merge-streams (first chain) (rest chain)))

(defn effective-view
  "Build a `View` from a parent-first stream chain. The view is a value
   consumed by `map-depot->local`."
  [chain]
  (let [collapsed   (collapse-chain chain)
        stream-name (:stream/name collapsed)
        ;; P4 rule: later view entries override earlier ones, so we walk
        ;; in reverse order at lookup time. Materialise the reversed vector
        ;; once at compile time.
        entries     (->> (:stream/paths collapsed)
                         (mapv #(compile-path-entry stream-name %))
                         rseq
                         vec)
        remap       (mapv (fn [[from to]]
                            {:from-tokens (glob->tokens from)
                             :from-re     (glob->re from)
                             :to-tokens   (glob->tokens to)})
                          (:stream/remapped collapsed))
        ignore-res  (mapv glob->re (:stream/ignored collapsed))]
    {:view/stream  stream-name
     :view/entries entries
     :view/remap   remap
     :view/ignores ignore-res}))

(defn- apply-remap
  "Apply remap rules to a local path. First match wins."
  [remap local]
  (or (some (fn [{:keys [from-re to-tokens]}]
              (when-let [m (re-matches from-re local)]
                (subst-tokens to-tokens (if (sequential? m) (vec (rest m)) []))))
            remap)
      local))

(defn- ignored?
  [ignore-res local]
  (boolean (some #(re-matches % local) ignore-res)))

(defn map-depot->local
  "Map a depot file to a local-relative path under the stream view, or
   `:clj-p4.view/excluded` / `:clj-p4.view/ignored` if filtered out.
   First matching view entry wins (P4's own rule)."
  [view depot-file]
  (or (some (fn [{:keys [match-fn rewrite-fn kind]}]
              (when-let [captures (match-fn depot-file)]
                (cond
                  (= kind :exclude) ::excluded
                  :else (let [local (rewrite-fn captures)
                              remapped (apply-remap (:view/remap view) local)]
                          (if (ignored? (:view/ignores view) remapped)
                            ::ignored
                            remapped)))))
            (:view/entries view))
      ::no-match))
