(ns clj-p4.shell.p4
  "One function per `p4` invocation. Each fn returns parsed Clojure data
   (a map or seq of maps) by piping `p4` stdout through
   `clj-p4.parse.marshal` (or the JSON path on 2024.1+ servers).

   The connection spec governs `-p`/`-u`/`-c`/`-C` argv assembly. Wire mode
   (`-G` vs `-Mj`) is selectable; auto-detection is the responsibility of
   the orchestration layer (call `info` first, then choose).

   JVM-only."
  (:require [clj-p4.parse.marshal :as marshal]
            [clj-p4.parse.semantic :as ps]
            [clj-p4.shell.proc :as proc]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream)))

(defn- conn-args
  "Build the leading `-p host -u user -c client -C charset` argv vector
   from a ConnectionSpec."
  [{:p4/keys [port user client charset] :as _conn}]
  (cond-> []
    port    (into ["-p" port])
    user    (into ["-u" user])
    client  (into ["-c" client])
    charset (into ["-C" (name charset)])))

(defn- env-with-ticket
  "Inject `P4PASSWD=<ticket>` into the env map so the subprocess can
   authenticate without an interactive login."
  [conn]
  (when-let [t (:p4/ticket conn)]
    {"P4PASSWD" t}))

(defn- run-p4!
  "Invoke `p4` with `mode-flag` (e.g. `[\"-G\"]` or `[\"-Mj\"]`) followed by
   `cmd-args`. Returns raw stdout-bytes; throws on non-zero exit."
  [conn mode-flag cmd-args]
  (let [argv (concat ["p4"] mode-flag (conn-args conn) cmd-args)
        opts {:env        (env-with-ticket conn)
              :timeout-ms (:p4/timeout-ms conn)}
        {:keys [stdout-bytes]} (proc/run-checked! (vec argv) opts)]
    stdout-bytes))

(defn- decode
  "Decode `bytes` as a record seq using the given wire mode (`:G` or `:Mj`)."
  [mode ^bytes bytes]
  (let [is (ByteArrayInputStream. bytes)]
    (case mode
      :G  (marshal/decode-marshal-records is)
      :Mj (marshal/decode-json-records is))))

(defn- with-mode-flag [mode]
  (case mode :G ["-G"] :Mj ["-Mj"]))

(defn p4-available?
  "True if a `p4` binary is on PATH and answers `p4 -V` cleanly. Cheap
   capability check; does not require server connectivity."
  []
  (try
    (zero? (:exit (proc/run! ["p4" "-V"] {:timeout-ms 5000})))
    (catch Exception _ false)))

(defn info
  "`p4 info` → server-info map (semantic-layer shape)."
  [conn & {:keys [mode] :or {mode :G}}]
  (-> (run-p4! conn (with-mode-flag mode) ["info"])
      (->> (decode mode))
      first
      ps/parse-info))

(defn stream-spec
  "`p4 stream -o //stream/x` → StreamSpec."
  [conn stream-name & {:keys [mode] :or {mode :G}}]
  (-> (run-p4! conn (with-mode-flag mode) ["stream" "-o" stream-name])
      (->> (decode mode))
      first
      ps/parse-stream-spec))

(defn stream-chain
  "Walk a stream's parent chain via repeated `p4 stream -o`. Returns a
   parent-first vector of StreamSpecs (oldest ancestor first)."
  [conn stream-name & {:keys [mode] :or {mode :G}}]
  (loop [name stream-name, acc ()]
    (let [s (stream-spec conn name :mode mode)
          parent (:stream/parent s)]
      (if (or (nil? parent) (str/blank? parent) (= "none" parent))
        (vec (cons s acc))
        (recur parent (cons s acc))))))

(defn changes
  "`p4 changes -l <path>` → seq of ChangelistRecord (without files).
   Supports `:max` to pass `-m N` and `:since` to filter `>=`-newer."
  [conn path & {:keys [mode max since]
                :or   {mode :G}}]
  (let [args (cond-> ["changes" "-l"]
               max   (into ["-m" (str max)])
               true  (conj (cond-> path
                             since (str "@>=" since))))]
    (->> (run-p4! conn (with-mode-flag mode) args)
         (decode mode)
         (mapv ps/parse-changelist))))

(defn describe
  "`p4 describe -s <change>` → ChangelistRecord with `:p4/files` populated."
  [conn change & {:keys [mode] :or {mode :G}}]
  (-> (run-p4! conn (with-mode-flag mode)
               ["describe" "-s" (str change)])
      (->> (decode mode))
      first
      ps/parse-describe))

(defn print-bytes!
  "`p4 print -q -o - <depot-path>@<rev>` → raw file bytes streamed to
   `out-stream`. The `-q` skips the header and `-o -` writes to stdout.
   No `-G`/`-Mj` here — file content is binary and consumed directly.

   `+k`/`+ko` keyword-flagged files: callers should pass
   `:keyword-expand? false` to use `-k` (no expansion) so the same bytes
   round-trip on subsequent syncs without re-dirtying."
  [conn depot-rev out-stream & {:keys [keyword-expand?]
                                :or   {keyword-expand? true}}]
  (let [argv (cond-> (concat ["p4"] (conn-args conn)
                             ["print" "-q" "-o" "-"])
               (not keyword-expand?) (concat ["-k"])
               true                   (concat [depot-rev]))
        env  (env-with-ticket conn)
        handle (proc/stream! (vec argv) {:env env})]
    (try
      (clojure.java.io/copy (:out handle) out-stream)
      (let [{:keys [exit stderr]} (proc/stream-close! handle)]
        (when-not (zero? exit)
          (throw (ex-info (str "p4 print failed: " (str/trim stderr))
                          {:clj-p4/error :p4-print-failed
                           :depot-rev    depot-rev
                           :exit         exit
                           :stderr       stderr})))
        {:depot-rev depot-rev :exit exit})
      (catch Throwable t
        (try (proc/stream-close! handle) (catch Exception _))
        (throw t)))))
