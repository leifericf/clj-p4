(ns clj-p4.audit
  "Cross-check a clj-p4 clone against the live P4 server.

   Two harnesses are available:

   - **`audit-tip`** (since 0.7.0): cheap count + total-bytes
     comparison at git tip vs. `p4 sizes -as <source>/...@<change>`.
     One server RPC + one `git ls-tree` per call. Catches
     order-of-magnitude failures, NOT subtle byte corruption.
   - **`audit-deep!`** (since 0.14.0): walks every commit (or a
     sample) and compares each file's bytes against `p4 print -k` of
     the source depot path at the matching changelist. Returns on
     first divergence with the offending commit, path, and digests.
     Many-orders-of-magnitude more expensive RPC-wise; the right tool
     when `audit-tip` flagged a mismatch and you need to find it."
  (:require [clj-p4.api :as api]
            [clj-p4.io.p4 :as p4]
            [clj-p4.io.subprocess :as proc]
            [clj-p4.schemas :as schemas]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.io ByteArrayOutputStream)
           (java.security MessageDigest)))

(defn- validate-options!
  [schema-val args op-name]
  (when-not (m/validate schema-val args)
    (let [explanation (m/explain schema-val args)]
      (throw (ex-info (str op-name " options invalid: "
                           (me/humanize explanation))
                      {:clj-p4/error :invalid-options
                       :op           op-name
                       :explanation  explanation
                       :args         args})))))

(defn- git-tree-summary
  "Return `{:git/file-count :git/total-bytes}` for `ref` in `target`.
   Reads `git ls-tree -r --long <ref>` and sums the size column."
  [target ref]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked! ["git" "-C" (str target)
                            "ls-tree" "-r" "--long" ref])
        text (String. ^bytes stdout-bytes "UTF-8")
        rows (->> (str/split-lines text)
                  (remove str/blank?))]
    (reduce (fn [acc row]
              ;; row format: <mode> <type> <sha>\t<size>\t<path>
              ;; tab-separated where the size precedes the path.
              (let [parts (str/split row #"\s+")
                    size  (some-> (nth parts 3 nil) parse-long)]
                (cond-> (update acc :git/file-count inc)
                  size (update :git/total-bytes + size))))
            {:git/file-count 0 :git/total-bytes 0}
            rows)))

(defn audit-tip
  "Fast sanity check: tree summary at git tip vs. `p4 sizes` at the
   matching changelist. Returns
     {:ok? boolean
      :git {:git/file-count N :git/total-bytes M}
      :p4  {:p4/file-count N :p4/total-bytes M}
      :change <int>}.

   `:source` is the depot path that was cloned (e.g. `\"//stream/main\"`
   or `\"//depot/main\"`). The library appends `/...` if needed.

   The check is not a substitute for byte-level comparison: P4 stores
   metadata (e.g. RCS keywords expanded vs. raw) that may shift sizes
   on individual files even when content is correct. A small disagreement
   is a hint, not a verdict."
  [{:keys [conn target source ref]
    :or   {ref "refs/heads/main"}
    :as   args}]
  (validate-options! schemas/audit-tip-options args "audit-tip")
  (let [{:keys [last-change]} (api/repo-state target :ref ref)
        _ (when-not last-change
            (throw (ex-info "audit-tip: no [git-p4: ...] trailer in tip"
                            {:clj-p4/error :no-trailer
                             :target       target
                             :ref          ref})))
        path  (cond-> source
                (not (str/ends-with? source "/..."))
                (str "/..."))
        p4sum (p4/sizes-summary conn (str path "@" last-change))
        gsum  (git-tree-summary target ref)]
    {:ok?    (and (= (:git/file-count gsum) (:p4/file-count p4sum))
                  (= (:git/total-bytes gsum) (:p4/total-bytes p4sum)))
     :git    gsum
     :p4     p4sum
     :change last-change}))

(defn- sha256-hex
  "SHA-256 of `bytes` as a lowercase hex string. Pure JVM."
  [^bytes bytes]
  (let [md (MessageDigest/getInstance "SHA-256")
        ds (.digest md bytes)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) ds))))

(defn- commits-with-trailers
  "Walk `ref` newest-to-oldest. Return `[{:sha :change} ...]` for every
   commit whose message carries the `[git-p4: ... change = N]` trailer.
   Manual commits without a trailer are skipped silently."
  [target ref]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked! ["git" "-C" (str target)
                            "log" "--pretty=%H%n%B%n--END--%n" ref])
        text (String. ^bytes stdout-bytes "UTF-8")]
    (->> (str/split text #"--END--\n+")
         (keep (fn [chunk]
                 (let [lines (str/split-lines chunk)
                       sha   (str/trim (or (first lines) ""))
                       msg   (str/join "\n" (rest lines))]
                   (when-not (str/blank? sha)
                     (when-let [[_ ch] (re-find
                                        #"\[git-p4:[^\]]*?change\s*=\s*(\d+)\s*\]"
                                        msg)]
                       {:sha sha :change (parse-long ch)})))))
         (filter some?)
         vec)))

(defn- list-tree
  "Return `[{:sha :path} ...]` for every blob under `commit-sha`'s tree.
   Output rows are `<mode> <type> <sha>\\t<path>` (tabs separate path)."
  [target commit-sha]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked! ["git" "-C" (str target)
                            "ls-tree" "-r" commit-sha])
        text (String. ^bytes stdout-bytes "UTF-8")]
    (->> (str/split-lines text)
         (remove str/blank?)
         (keep (fn [row]
                 (let [[meta path] (str/split row #"\t" 2)
                       [_ _ sha]   (str/split (or meta "") #"\s+")]
                   (when (and sha path)
                     {:sha sha :path path}))))
         vec)))

(defn- git-blob-bytes
  "Return raw bytes of `blob-sha` in the bare repo at `target`."
  [target blob-sha]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked! ["git" "-C" (str target)
                            "cat-file" "blob" blob-sha])]
    stdout-bytes))

(defn- p4-print-bytes
  "Run `p4 print -k <depot>@<change>` (no RCS keyword expansion so bytes
   round-trip what was written at clone time) and return raw bytes."
  [conn depot-path change]
  (let [baos (ByteArrayOutputStream.)]
    (p4/print-bytes! conn (str depot-path "@" change) baos
                     :keyword-expand? false)
    (.toByteArray baos)))

(defn- pick-sample
  "Sample `n` commits from `commits` (newest-first). `:all` returns the
   whole vector. For `:n` (a positive integer), takes a deterministic
   evenly-spaced slice — first commit + last commit + intermediates —
   so the sample covers history rather than clustering at one end."
  [commits n]
  (let [total (count commits)]
    (cond
      (= n :all)            commits
      (zero? total)         []
      (>= (or n 0) total)   commits
      :else                 (let [step (max 1 (quot total n))]
                              (vec (take n (take-nth step commits)))))))

(defn audit-deep!
  "Walk `ref`'s commits (newest-first; default `refs/heads/main`),
   sample `:sample` of them (default `10`; `:all` for every commit),
   and for each commit's git tree compare each file's bytes against
   `p4 print -k <source-depot-path>@<change>`. Returns on first
   divergence — not after a full pass — because a single bad file is
   already enough evidence to investigate.

   Returns:
   `{:ok? true :commits-checked N :files-checked M}` — agreement
   `{:ok? false :divergence {:commit :path :change :git-sha :p4-sha
                             :git-bytes :p4-bytes}
     :commits-checked N :files-checked M}` — first mismatch wins.

   `:source` is the source depot path the clone was made from; the
   library prepends `//` if missing and treats files in the git tree
   as paths relative to whichever `View:` mapped them at clone time.
   For an auto-view clone, the local path equals what the server
   resolved minus the client prefix.

   This is many orders of magnitude more expensive than `audit-tip`.
   Use `:sample 1` to spot-check, `:sample :all` only when investigating
   a known mismatch."
  [{:keys [conn target source ref sample]
    :or   {ref    "refs/heads/main"
           sample 10}
    :as   args}]
  (validate-options! schemas/audit-deep-options args "audit-deep!")
  (let [commits   (commits-with-trailers target ref)
        sampled   (pick-sample commits sample)
        ;; A simple convention: source paths in git use the same
        ;; relative path as the depot path under `<source>/`.
        depot-of  (fn [path] (str source "/" path))
        result
        (loop [cs sampled, files-checked 0, commits-checked 0]
          (if-let [{:keys [sha change]} (first cs)]
            (let [tree (list-tree target sha)
                  div  (some
                        (fn [{git-sha :sha path :path}]
                          (let [git-bytes (git-blob-bytes target git-sha)
                                {:keys [p4-bytes p4-error]}
                                (try
                                  {:p4-bytes (p4-print-bytes conn
                                                             (depot-of path)
                                                             change)}
                                  (catch Exception e
                                    {:p4-error (ex-message e)}))]
                            (when (or p4-error
                                      (not (java.util.Arrays/equals
                                            ^bytes git-bytes ^bytes p4-bytes)))
                              {:commit    sha
                               :path      path
                               :change    change
                               :reason    (if p4-error
                                            :p4-print-failed
                                            :byte-mismatch)
                               :p4-error  p4-error
                               :git-sha   (sha256-hex git-bytes)
                               :p4-sha    (some-> p4-bytes sha256-hex)
                               :git-bytes git-bytes
                               :p4-bytes  p4-bytes})))
                        tree)]
              (if div
                {:ok?              false
                 :divergence       div
                 :commits-checked  (inc commits-checked)
                 :files-checked    (+ files-checked (count tree))}
                (recur (rest cs)
                       (+ files-checked (count tree))
                       (inc commits-checked))))
            {:ok?             true
             :commits-checked commits-checked
             :files-checked   files-checked}))]
    result))
