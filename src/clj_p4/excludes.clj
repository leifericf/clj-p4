(ns clj-p4.excludes
  "Client-side file filtering, separate from a stream's own
   `:stream/ignored` list. Used to drop binary noise (build artefacts,
   asset binaries, etc.) and to carve out subtrees at clone time.

   Two complementary mechanisms:

   - **Path-pattern filter.** Gitignore-flavoured subset:
       `*.ext`        — match extension at any depth
       `name`         — match `name` at any depth
       `dir/`         — trailing `/` matches a directory and everything under
       `/anchored`    — leading `/` anchors at root
       P4 `...`       — `foo/...` recursive subtree
       P4 `*` and `%n` — same semantics as in `clj-p4.view`
     Built-in categorised lists (`:images`, `:audio`, …) ship in the
     `builtin-binaries` def below and are selected via the
     `:categories` option on `exclude-patterns`. The resolver returns
     two parallel lists (`:excludes` and `:includes`); a path is
     dropped iff some `:excludes` matches AND no `:includes` matches
     (gitignore-style set difference).
   - **Type-based predicate.** `binary-rev?` consults P4's per-revision
     `:rev/type` (parsed from `p4 describe -s`) to drop revs P4 itself
     classifies as binary — `:binary`, `:apple`, `:resource`. Catches
     unknown extensions; trusts P4's at-add-time content sniff.

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

(def ^:private builtin-binaries
  "Categorised binary patterns shipped with clj-p4.

   Curation principle: only include extensions that are *always* a
   binary container, regardless of variant. Source-form text/config
   (e.g. `.svg` XML, `.gltf` JSON, `.dae` COLLADA, `.obj` Wavefront
   text, Unity YAML scenes/prefabs/assets, Unreal `.upluginmanifest`
   JSON) is intentionally absent — those are code, not assets, and
   users importing source for review usually want them in git.
   Type-based filtering (`:rev/type :binary`) catches their binary
   cousins like `.glb` automatically.

   Opt-in via `:exclude-categories` on `clone!` / `fetch!`. The
   orthogonal `:exclude-binaries?` option (default true) drops anything
   Perforce itself classifies as binary, regardless of extension."
  {:images
   ["*.png" "*.jpg" "*.jpeg" "*.tga" "*.bmp" "*.tiff" "*.exr" "*.hdr"
    "*.psd" "*.dds" "*.ico" "*.gif" "*.webp"]

   :audio
   ;; `.bnk` and `.wem` are Wwise (Audiokinetic) audio middleware:
   ;; sound bank container and encoded audio asset, both always binary.
   ["*.wav" "*.mp3" "*.ogg" "*.flac" "*.aiff" "*.wma" "*.aac" "*.opus"
    "*.mid" "*.bnk" "*.wem"]

   :video
   ["*.mp4" "*.avi" "*.mov" "*.mkv" "*.wmv" "*.bik" "*.webm"]

   :models
   ;; Always-binary 3D model containers. `.obj`/`.dae`/`.gltf`/`.ply`/`.stl`
   ;; have text variants and are deliberately excluded; their binary cousin
   ;; `.glb` (binary glTF) is included.
   ["*.fbx" "*.blend" "*.max" "*.ma" "*.mb" "*.3ds" "*.abc" "*.glb"]

   :archives
   ["*.pak" "*.zip" "*.tar" "*.gz" "*.7z" "*.rar"]

   :fonts
   ["*.ttf" "*.otf" "*.woff" "*.woff2" "*.eot"]

   :documents
   ["*.pdf" "*.doc" "*.docx" "*.xls" "*.xlsx" "*.ppt" "*.pptx"]

   :compiled
   ["*.dll" "*.so" "*.dylib" "*.exe" "*.lib" "*.a" "*.o" "*.pdb" "*.ilk"
    "*.exp" "*.jar" "*.war" "*.class" "*.pyc" "*.pyo" "*.whl"]

   :engine-assets
   ;; Unreal cooked binary content only. Unity scene/prefab/asset YAML
   ;; (`.unity`, `.prefab`, `.asset`, `.anim`, `.controller`) and Unreal
   ;; `.upluginmanifest` (JSON config) are deliberately omitted — they're
   ;; text and typically version-controlled alongside code.
   ["*.uasset" "*.umap" "*.upk" "*.udk" "*.ubulk" "*.uexp"]})

(defn binary-categories
  "The set of category keywords available in clj-p4's built-in
   `builtin-binaries` def. Useful for tooling and validation."
  []
  (-> builtin-binaries keys set))

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
  "Compute the resolved exclude/include pattern lists from an options map.

   The two lists are orthogonal — `:includes` is *not* string-removal of
   exclude patterns. Both are evaluated against each candidate path at
   match time: a path is filtered out iff some `:excludes` entry matches
   AND no `:includes` entry matches (gitignore-style set difference).

   Options:
     :categories           — `:all` or a set of category keywords selected
                             from the resource. When set, the built-in
                             `builtin-binaries` is used as the universe;
                             `:resource`, if also given, supersedes it.
     :resource             — a map of category → seq-of-pattern-strings.
                             Used as the universe `:categories` selects
                             from when both are present, and as the source
                             of all patterns when `:categories` is absent.
     :no-default-excludes? — skip the resource's patterns when `:categories`
                             is absent. Has no effect on an explicit
                             `:categories` selection.
     :excludes             — additional exclude patterns.
     :includes             — re-include carve-out patterns.

   Returns `{:excludes [str…] :includes [str…]}` — each list deduped and
   in stable insertion order (resource patterns first when applicable,
   then user `:excludes`)."
  [{:keys [categories resource no-default-excludes? excludes includes]}]
  (let [universe (or resource
                     (when categories builtin-binaries))
        defaults (cond
                   categories             (select-categories universe categories)
                   no-default-excludes?   nil
                   :else                  (apply concat (vals (or universe {}))))]
    {:excludes (->> (concat defaults excludes) distinct vec)
     :includes (->> includes distinct vec)}))

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
