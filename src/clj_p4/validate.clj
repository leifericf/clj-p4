(ns clj-p4.validate
  "Cross-check a clj-p4 clone against the live P4 server.

   The simplest check this namespace performs is a *count + size
   summary*: walk the git tip's tree, sum file count and total bytes,
   then compare against `p4 sizes -as <stream>/...@<change>` at the
   changelist the tip resumes from. A clone in agreement here passes a
   first-pass sanity gate; deeper byte-level comparison is a
   bigger-hammer follow-up not yet implemented.

   JVM-only."
  (:require [clj-p4.api :as api]
            [clj-p4.shell.p4 :as p4]
            [clj-p4.shell.proc :as proc]
            [clojure.string :as str]))

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

(defn validate-tip
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
    :or   {ref "refs/heads/main"}}]
  (let [{:keys [last-change]} (api/repo-state target :ref ref)
        _ (when-not last-change
            (throw (ex-info "validate-tip: no [git-p4: ...] trailer in tip"
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
