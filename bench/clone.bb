#!/usr/bin/env bb

;; bench/clone.bb — wall-clock + peak-memory comparison of three
;; P4 → Git importers cloning the same source. Designed to be run
;; locally against the test/fixtures/p4d Docker fixture; not in CI.
;;
;; Usage:
;;   bb bench/clone.bb [--source //stream/main] [--repeat 3]
;;
;; Each run clones into a fresh tmp dir, measures wall-clock + peak
;; RSS via /usr/bin/time -lp (macOS) or -v (Linux), and emits a
;; markdown row. Average across `--repeat` runs.
;;
;; The script does NOT pin tool versions — it uses whatever git-p4 /
;; p4-fusion / clj-p4 are on PATH. Record the versions in the
;; markdown header before publishing numbers.

(require '[babashka.process :as proc]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def ^:private default-source "//stream/main")
(def ^:private default-repeat 3)

(defn- parse-args [argv]
  (loop [acc {:source default-source :repeat default-repeat}
         xs argv]
    (case (first xs)
      "--source" (recur (assoc acc :source (second xs)) (drop 2 xs))
      "--repeat" (recur (assoc acc :repeat (parse-long (second xs))) (drop 2 xs))
      nil acc
      (do (println "unknown arg:" (first xs)) (System/exit 2)))))

(defn- mk-tmp [prefix]
  (str (fs/create-temp-dir {:prefix prefix})))

(defn- shell-time-prefix
  "Return /usr/bin/time argv prefix that captures wall + max-rss in
   parseable form. macOS uses -lp; GNU uses -v."
  []
  (if (= "Mac OS X" (System/getProperty "os.name"))
    ["/usr/bin/time" "-lp"]
    ["/usr/bin/time" "-v"]))

(defn- parse-time-output
  "Pull `real` (seconds) and max-rss (bytes) out of /usr/bin/time stderr.
   Returns nil if either is missing."
  [stderr]
  (let [real-re   #"real\s+(\d+\.?\d*)"
        gnu-re    #"Maximum resident set size \(kbytes\): (\d+)"
        macos-re  #"(\d+)\s+maximum resident set size"
        wall      (some-> (re-find real-re stderr) second parse-double)
        rss-bytes (or (some-> (re-find gnu-re stderr) second parse-long
                              (* 1024))
                      (some-> (re-find macos-re stderr) second parse-long))]
    (when (and wall rss-bytes)
      {:wall-secs wall :max-rss-bytes rss-bytes})))

(defn- run-and-time
  "Run argv with /usr/bin/time wrapping, return {:wall-secs :max-rss-bytes}
   or nil + the captured stderr on parse failure."
  [argv]
  (let [{:keys [exit err]} (apply proc/shell {:out :string :err :string
                                              :continue true}
                                  (concat (shell-time-prefix) argv))]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (println "FAILED" argv "\n" err))
      (System/exit 1))
    (or (parse-time-output err)
        (do (binding [*out* *err*]
              (println "could not parse time output:\n" err))
            nil))))

(defn- bench-git-p4
  [source]
  (let [target (mk-tmp "bench-git-p4-")]
    (try
      (run-and-time ["git" "p4" "clone" "--bare" source target])
      (finally (fs/delete-tree target)))))

(defn- bench-p4-fusion
  [source]
  (let [target (mk-tmp "bench-p4-fusion-")]
    (try
      (run-and-time ["p4-fusion" "--src" source "--path" target])
      (finally (fs/delete-tree target)))))

(defn- bench-clj-p4
  "Time a clj-p4 clone via `clojure -X`. The conn map is read from
   `P4PORT` / `P4USER` env vars; the operator can edit this fn to
   thread additional connection-spec keys (charset, retries, ticket)."
  [source]
  (let [target (mk-tmp "bench-clj-p4-")
        conn   {:p4/port (or (System/getenv "P4PORT") "localhost:1666")
                :p4/user (or (System/getenv "P4USER") "alice")}]
    (try
      (run-and-time
       ["clojure" "-X" "clj-p4.api/clone!"
        :conn   (pr-str conn)
        :source (pr-str source)
        :target (pr-str target)])
      (finally (fs/delete-tree target)))))

(defn- mean [xs] (/ (reduce + xs) (count xs)))

(defn- run-bench
  [name-str fn-bench source repeats]
  (println "==" name-str "==")
  (let [results (vec (for [i (range repeats)]
                       (do (println " run" (inc i) "/" repeats)
                           (fn-bench source))))]
    {:tool name-str
     :wall-mean-s (mean (map :wall-secs results))
     :rss-mean-mb (/ (mean (map :max-rss-bytes results))
                     1024.0 1024.0)
     :runs        (count results)}))

(defn- emit-md-row [{:keys [tool wall-mean-s rss-mean-mb runs]}]
  (println (format "| %-12s | %5d | %8.1f s | %7.1f MB |"
                   tool runs wall-mean-s rss-mean-mb)))

(defn -main [& argv]
  (let [{:keys [source repeat]} (parse-args argv)]
    (println "Source:" source)
    (println "Repeats:" repeat)
    (println)
    (println "| Tool         | Runs  | Wall      | Peak RSS  |")
    (println "| ------------ | ----: | --------: | --------: |")
    (doseq [r [(run-bench "git-p4"     bench-git-p4    source repeat)
               (run-bench "p4-fusion"  bench-p4-fusion source repeat)
               (run-bench "clj-p4"     bench-clj-p4    source repeat)]]
      (emit-md-row r))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
