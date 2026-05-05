(ns clj-p4.excludes
  "Client-side file filtering, separate from a stream's own
   `:stream/ignored` list. Used to drop binary noise (build artefacts,
   asset binaries, etc.) at clone time.

   Two complementary mechanisms:

   - **Path-pattern filter.** Gitignore-flavoured subset:
       `*.ext`        — match extension at any depth
       `name`         — match `name` at any depth
       `dir/`         — trailing `/` matches a directory and everything under
       `/anchored`    — leading `/` anchors at root
       P4 `...`       — `foo/...` recursive subtree
       P4 `*` and `%n` — same semantics as in `clj-p4.view`
     Built-in categorised lists (`:images`, `:audio`, …) ship in
     `resources/clj_p4/excludes/binaries.edn` and are selected via the
     `:categories` option on `exclude-patterns`.
   - **Type-based predicate.** `binary-rev?` consults P4's per-revision
     `:rev/type` (parsed from `p4 describe -s`) to drop revs P4 itself
     classifies as binary — `:binary`, `:apple`, `:resource`. Catches
     unknown extensions; trusts P4's at-add-time content sniff.

   Pure data layer."
  (:require [clj-p4.view :as view]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn pattern->re
  "Compile a pattern string into a regex matching local relative paths.
   Anchoring rules:
   - leading `/` → root-anchored.
   - trailing `/` → directory match: matches the directory itself and
     anything under it.
   - otherwise → matches at any depth (a containing-dir prefix is implied)."
  [pat]
  (when (or (= "" pat) (= "/" pat))
    (throw (ex-info (str "pattern->re: empty pattern " (pr-str pat))
                    {:clj-p4/error :invalid-pattern
                     :pattern      pat})))
  (let [anchored? (str/starts-with? pat "/")
        dir-only? (str/ends-with?   pat "/")
        core      (cond-> pat
                    anchored? (subs 1)
                    dir-only? (#(subs % 0 (dec (count %)))))
        body      (view/tokens->re-body (view/glob->tokens core))
        prefix    (if anchored? "^" "(?:^|.*/)")
        suffix    (if dir-only? "(?:/.*)?$" "$")]
    (re-pattern (str prefix body suffix))))

(def ^:private builtin-binaries-resource
  "Categorised binary patterns shipped with clj-p4. Lazy-loaded so test
   runs that never touch this file pay no cost."
  (delay
    (-> "clj_p4/excludes/binaries.edn"
        io/resource
        slurp
        edn/read-string)))

(defn binary-categories
  "The set of category keywords available in clj-p4's built-in
   `binaries.edn` resource. Useful for tooling and validation."
  []
  (-> @builtin-binaries-resource keys set))

(defn- select-categories
  "Pick `categories` from `resource-map`. `categories` is `:all` (every
   key) or a set of keywords. Returns a seq of pattern strings in stable
   order — resource keys' iteration order, with each value's own order
   preserved within."
  [resource-map categories]
  (let [keys-in-order (if (= :all categories)
                        (keys resource-map)
                        (filter (set categories) (keys resource-map)))]
    (mapcat resource-map keys-in-order)))

(defn exclude-patterns
  "Compute the final exclusion pattern list given an options map.

   Options:
     :categories           — `:all` or a set of category keywords selected
                             from the resource. When set, the built-in
                             `binaries.edn` is used as the universe;
                             `:resource`, if also given, supersedes it.
     :resource             — a map of category → seq-of-pattern-strings.
                             Used as the universe `:categories` selects
                             from when both are present, and as the source
                             of all patterns when `:categories` is absent.
     :no-default-excludes? — skip the resource's patterns when `:categories`
                             is absent. Has no effect on an explicit
                             `:categories` selection.
     :extra-excludes       — additional patterns to add.
     :includes             — patterns to remove from the union (whitelist).

   Returns a deduped vector of pattern strings, in stable order:
   resource patterns first (category iteration order), then extras."
  [{:keys [categories resource no-default-excludes? extra-excludes includes]}]
  (let [universe (or resource
                     (when categories @builtin-binaries-resource))
        defaults (cond
                   categories             (select-categories universe categories)
                   no-default-excludes?   nil
                   :else                  (apply concat (vals (or universe {}))))
        all         (concat defaults extra-excludes)
        include-set (set includes)]
    (->> all (remove include-set) distinct vec)))

(def binary-rev-types
  "P4 base types whose content is binary. Revisions whose `:rev/type` is
   one of these are dropped by the type-based filter; everything else
   (text/utf8/utf16/unicode/symlink) is kept.

   `:apple` is the legacy AppleSingle/AppleDouble container; `:resource`
   is a Mac OS resource fork. Both are binary blobs."
  #{:binary :apple :resource})

(defn binary-rev?
  "True when `fr`'s `:rev/type` is one P4 classifies as binary
   (`:binary`, `:apple`, `:resource`). Designed for use as a runner-level
   `:exclude-fn` predicate.

   The type comes from `p4 describe -s` parsed by `clj-p4.records/parse-file-type`
   — no additional P4 call is needed to consult it."
  [fr]
  (contains? binary-rev-types (:rev/type fr)))

(defn compile-patterns
  "Compile a seq of pattern strings into a vector of `[<pattern> <regex>]`
   pairs, ready for `matches-any?`."
  [patterns]
  (mapv (fn [p] [p (pattern->re p)]) patterns))

(defn matches-any?
  "True if `path` matches any of the compiled patterns
   (output of `compile-patterns`)."
  [compiled path]
  (boolean (some (fn [[_ re]] (re-find re path)) compiled)))

(defn matching-pattern
  "If `path` matches, return the original pattern string for diagnostics;
   else `nil`."
  [compiled path]
  (some (fn [[p re]] (when (re-find re path) p)) compiled))
