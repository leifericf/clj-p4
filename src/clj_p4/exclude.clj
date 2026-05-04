(ns clj-p4.exclude
  "Client-side file-pattern filtering, separate from a stream's own
   `:stream/ignored` list. Used to drop binary noise (build artefacts,
   asset binaries, etc.) at clone time. Patterns use a gitignore-flavoured
   subset:

   - `*.ext`        — match any file with that extension at any depth
   - `name`         — match `name` at any depth
   - `dir/`         — trailing `/` matches a directory and everything under
   - `/anchored`    — leading `/` anchors at root
   - P4 `...`       — `foo/...` recursive subtree
   - P4 `*` and `%n` — same semantics as in `clj-p4.view`

   Pure data layer."
  (:require [clj-p4.view :as view]
            [clojure.string :as str]))

(defn pattern->re
  "Compile a pattern string into a regex matching local relative paths.
   Anchoring rules:
   - leading `/` → root-anchored.
   - trailing `/` → directory match: matches the directory itself and
     anything under it.
   - otherwise → matches at any depth (a containing-dir prefix is implied)."
  [pat]
  (let [anchored? (str/starts-with? pat "/")
        dir-only? (str/ends-with?   pat "/")
        core      (cond-> pat
                    anchored? (subs 1)
                    dir-only? (#(subs % 0 (dec (count %)))))
        body      (view/tokens->re-body (view/glob->tokens core))
        prefix    (if anchored? "^" "(?:^|.*/)")
        suffix    (if dir-only? "(?:/.*)?$" "$")]
    (re-pattern (str prefix body suffix))))

(defn exclude-patterns
  "Compute the final exclusion pattern list given an options map.

   Options:
     :resource             — a map of category → seq-of-pattern-strings
                             (e.g. Noumenon's `p4-excludes.edn` content).
     :no-default-excludes? — skip the resource's patterns.
     :extra-excludes       — additional patterns to add.
     :includes             — patterns to remove from the union (whitelist).

   Returns a deduped vector of pattern strings, in stable order:
   resource patterns first (category iteration order), then extras."
  [{:keys [resource no-default-excludes? extra-excludes includes]}]
  (let [defaults    (when-not no-default-excludes?
                      (->> (vals (or resource {})) (apply concat)))
        all         (concat defaults extra-excludes)
        include-set (set includes)]
    (->> all (remove include-set) distinct vec)))

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
