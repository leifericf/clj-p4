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

(def ^:private read-only-subcommands
  "p4 subcommands that read state without mutating the depot."
  #{"info" "streams" "changes" "describe" "print" "files" "fstat"
    "integrated" "labels" "users" "protects" "where" "dirs" "sizes"})

(def ^:private metadata-write-subcommands
  "p4 subcommands that modify server metadata (specs, clients) but never
   touch depot file state. Each maps to the set of allowed first flags;
   `client -i` and `client -d` are necessary for ephemeral-client lifecycle."
  {"stream" #{"-o"}
   "client" #{"-o" "-i" "-d"}
   "label"  #{"-o"}
   "user"   #{"-o"}})

(defn- assert-read-only!
  "Throw `:clj-p4/error :write-direction-refused` if `cmd-args` invokes a
   p4 subcommand outside the read-only / metadata-only allowlist."
  [cmd-args]
  (let [sub        (first cmd-args)
        rest-args  (rest cmd-args)]
    (cond
      (contains? read-only-subcommands sub)
      :ok

      (contains? metadata-write-subcommands sub)
      (let [allowed (get metadata-write-subcommands sub)
            flag    (first rest-args)]
        (when-not (contains? allowed flag)
          (throw (ex-info (str "p4 " sub " " (or flag "<no-flag>")
                               " refused: only " allowed " allowed")
                          {:clj-p4/error :write-direction-refused
                           :subcommand   sub
                           :flag         flag
                           :argv         (vec cmd-args)}))))

      :else
      (throw (ex-info (str "p4 " sub " refused: not in read-only allowlist")
                      {:clj-p4/error :write-direction-refused
                       :subcommand   sub
                       :argv         (vec cmd-args)})))))

(defn- env-with-ticket
  "Inject `P4PASSWD=<ticket>` into the env map so the subprocess can
   authenticate without an interactive login."
  [conn]
  (when-let [t (:p4/ticket conn)]
    {"P4PASSWD" t}))

(defn- run-p4!
  "Invoke `p4` with `mode-flag` (e.g. `[\"-G\"]` or `[\"-Mj\"]`) followed by
   `cmd-args`. Returns raw stdout-bytes; throws on non-zero exit. Refuses
   any subcommand outside the read-only / metadata-only allowlist.

   Honours `:p4/retries` and `:p4/retry-backoff-ms` on the conn for
   retry-on-transient-failure (see `clj-p4.shell.proc/run!`)."
  ([conn mode-flag cmd-args]
   (run-p4! conn mode-flag cmd-args nil))
  ([conn mode-flag cmd-args stdin-bytes]
   (assert-read-only! cmd-args)
   (let [argv (concat ["p4"] mode-flag (conn-args conn) cmd-args)
         opts (cond-> {:env        (env-with-ticket conn)
                       :timeout-ms (:p4/timeout-ms conn)}
                (:p4/retries conn)          (assoc :retries (:p4/retries conn))
                (:p4/retry-backoff-ms conn) (assoc :retry-backoff-ms
                                                   (:p4/retry-backoff-ms conn))
                stdin-bytes                 (assoc :stdin-bytes stdin-bytes))
         {:keys [stdout-bytes]} (proc/run-checked! (vec argv) opts)]
     stdout-bytes)))

(defn- decode
  "Decode `bytes` as a record seq using the given wire mode (`:G` or `:Mj`)."
  [mode ^bytes bytes]
  (let [is (ByteArrayInputStream. bytes)]
    (case mode
      :G  (marshal/decode-marshal-records is)
      :Mj (marshal/decode-json-records is))))

(defn- with-mode-flag
  "Mode flag to send to `p4` so its output uses tagged record form.
   `-G` is Python marshal (field-by-field). `-Mj -ztag` is JSON
   (field-by-field) — `-Mj` alone gives command-output form, not records,
   so `-ztag` is mandatory."
  [mode]
  (case mode :G ["-G"] :Mj ["-Mj" "-ztag"]))

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
   parent-first vector of StreamSpecs (oldest ancestor first).

   Throws `ex-info` with `:clj-p4/error :stream-chain-cycle` if the
   server reports a cyclic Parent — defensive against malformed metadata
   that would otherwise loop forever and run unbounded RPCs."
  [conn stream-name & {:keys [mode] :or {mode :G}}]
  (loop [name stream-name, acc (), seen #{}]
    (when (contains? seen name)
      (throw (ex-info (str "stream chain cycle detected at " name)
                      {:clj-p4/error :stream-chain-cycle
                       :cycle-at      name
                       :seen          seen})))
    (let [s      (stream-spec conn name :mode mode)
          parent (:stream/parent s)]
      (if (or (nil? parent) (str/blank? parent) (= "none" parent))
        (vec (cons s acc))
        (recur parent (cons s acc) (conj seen name))))))

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

(defn labels
  "`p4 labels` → seq of `{:label/name :label/owner :label/desc :label/update}`."
  [conn & {:keys [mode] :or {mode :G}}]
  (let [recs (->> (run-p4! conn (with-mode-flag mode) ["labels"])
                  (decode mode))]
    (mapv (fn [r]
            {:label/name   (get r "label")
             :label/owner  (get r "Owner")
             :label/desc   (get r "Description")
             :label/update (get r "Update")
             :label/access (get r "Access")
             :label/options (get r "Options")})
          recs)))

(defn sizes-summary
  "`p4 sizes -as <path>` → totals across every file at the path.
   Returns `{:p4/file-count :p4/total-bytes}`. The `-s` flag asks p4 for
   a single summary record rather than per-file rows; we sum across
   records in case multiple come back."
  [conn path & {:keys [mode] :or {mode :G}}]
  (let [recs (->> (run-p4! conn (with-mode-flag mode) ["sizes" "-as" path])
                  (decode mode))]
    (reduce (fn [acc r]
              (let [files (parse-long (or (get r "fileCount") "0"))
                    bytes (parse-long (or (get r "fileSize") "0"))]
                (-> acc
                    (update :p4/file-count + files)
                    (update :p4/total-bytes + bytes))))
            {:p4/file-count 0 :p4/total-bytes 0}
            recs)))

(defn label-spec
  "`p4 label -o <name>` → `{:label/name :label/revision :label/desc ...}`.
   `:label/revision` is the raw `Revision:` string verbatim — callers
   parse it (formats vary: `@<n>`, `<n>`, depot-path-with-revision)."
  [conn label-name & {:keys [mode] :or {mode :G}}]
  (let [r (-> (run-p4! conn (with-mode-flag mode) ["label" "-o" label-name])
              (->> (decode mode))
              first)]
    {:label/name      (get r "Label")
     :label/owner     (get r "Owner")
     :label/desc      (get r "Description")
     :label/revision  (get r "Revision")
     :label/update    (get r "Update")
     :label/access    (get r "Access")
     :label/options   (get r "Options")}))

(defn describe
  "`p4 describe -s <change>` → ChangelistRecord with `:p4/files` populated."
  [conn change & {:keys [mode] :or {mode :G}}]
  (-> (run-p4! conn (with-mode-flag mode)
               ["describe" "-s" (str change)])
      (->> (decode mode))
      first
      ps/parse-describe))

(defn print-bytes!
  "`p4 print -q <depot-path>@<rev>` → raw file bytes written to
   `out-stream`. `-q` skips the header. No `-G`/`-Mj` here — file content
   is binary and consumed directly.

   `+k`/`+ko` keyword-flagged files: callers should pass
   `:keyword-expand? false` to use `-k` (no expansion) so the same bytes
   round-trip on subsequent syncs without re-dirtying.

   Uses one-shot `run!` rather than `stream!` because `p4 print` does not
   read stdin; an `:in :stream` pipe would never be closed and the
   subprocess could block on its parent's EOF."
  [conn depot-rev out-stream & {:keys [keyword-expand?]
                                :or   {keyword-expand? true}}]
  (assert-read-only! ["print"])
  (let [argv (cond-> (into ["p4"] (conn-args conn))
               true                   (into ["print" "-q"])
               (not keyword-expand?)  (conj "-k")
               true                   (conj depot-rev))
        env  (env-with-ticket conn)
        opts (cond-> {:env env :timeout-ms (:p4/timeout-ms conn)}
               (:p4/retries conn)          (assoc :retries (:p4/retries conn))
               (:p4/retry-backoff-ms conn) (assoc :retry-backoff-ms
                                                  (:p4/retry-backoff-ms conn)))
        {:keys [exit stdout-bytes stderr]}
        (proc/run! argv opts)]
    (when-not (zero? exit)
      (throw (ex-info (str "p4 print failed: " (str/trim (or stderr "")))
                      {:clj-p4/error :p4-print-failed
                       :depot-rev    depot-rev
                       :exit         exit
                       :stderr       stderr})))
    (.write ^java.io.OutputStream out-stream ^bytes stdout-bytes)
    {:depot-rev depot-rev :exit exit :bytes-written (alength ^bytes stdout-bytes)}))

(defn- uuid8 []
  (subs (.toString (java.util.UUID/randomUUID)) 0 8))

(defn- format-client-spec
  "Render a minimal client spec as Perforce form-text. Server fills in
   `View:` from `Stream:` when both are absent."
  [{:keys [client owner root stream]}]
  (str "Client: "  client                                 "\n"
       "Owner: "   (or owner "clj-p4")                    "\n"
       "Root: "    root                                   "\n"
       "Stream: "  stream                                 "\n"
       "Options: " "noallwrite noclobber locked"          "\n"
       "LineEnd: " "local"                                "\n"))

(defn- parse-client-view-lines
  "Pull the `View:` block out of a `p4 client -o` form-text response,
   one entry per indented line. The block ends at the first non-indented
   line (next field header or EOF)."
  [^String spec-text]
  (let [lines (str/split-lines spec-text)]
    (loop [in-view? false, out [], xs lines]
      (if-let [line (first xs)]
        (cond
          (and (not in-view?) (re-matches #"View:\s*" line))
          (recur true out (rest xs))

          (not in-view?)
          (recur false out (rest xs))

          (re-matches #"\s+\S.*" line)
          (recur true (conj out (str/trim line)) (rest xs))

          :else
          (vec out))
        (vec out)))))

(defn create-stream-client!
  "Create an ephemeral P4 client bound to `stream-name` via `p4 client -i`.
   Returns `{:client/name :client/root :client/view-lines}` where
   `:client/view-lines` is the server-filled `View:` block (one entry per
   string). The client is created with `Options: noallwrite noclobber
   locked` so the server itself refuses any depot-mutating operation
   routed through it.

   Caller is responsible for tearing the client down with
   `delete-stream-client!` (or `with-ephemeral-client`)."
  [conn stream-name & {:keys [name-prefix root-prefix mode]
                       :or   {name-prefix "clj-p4-"
                              root-prefix "/tmp/clj-p4-eph-"
                              mode :G}}]
  (let [u           (uuid8)
        client-name (str name-prefix u)
        root        (str root-prefix u)
        spec        (format-client-spec {:client client-name
                                         :owner  (:p4/user conn)
                                         :root   root
                                         :stream stream-name})]
    (run-p4! conn [] ["client" "-i"] (.getBytes ^String spec "UTF-8"))
    (let [back (run-p4! conn [] ["client" "-o" client-name])
          spec-text (String. ^bytes back "UTF-8")]
      {:client/name       client-name
       :client/root       root
       :client/view-lines (parse-client-view-lines spec-text)})))

(defn- format-classic-client-spec
  "Render a generic (non-stream) ephemeral client spec mapping
   `depot-glob` to the client root. Used for classic depot paths that
   live outside any stream depot."
  [{:keys [client owner root depot-glob]}]
  (str "Client: "  client                                 "\n"
       "Owner: "   (or owner "clj-p4")                    "\n"
       "Root: "    root                                   "\n"
       "Options: " "noallwrite noclobber locked"          "\n"
       "LineEnd: " "local"                                "\n"
       "View:\n"
       "\t" depot-glob " //" client "/...\n"))

(defn create-classic-client!
  "Like `create-stream-client!` but for a classic (non-stream) depot
   path: creates a generic ephemeral client whose `View:` maps `depot-path`
   (with a trailing `/...` if absent) to the client root. Returns the
   same shape as `create-stream-client!`."
  [conn depot-path & {:keys [name-prefix root-prefix]
                      :or   {name-prefix "clj-p4-"
                             root-prefix "/tmp/clj-p4-eph-"}}]
  (let [u           (uuid8)
        client-name (str name-prefix u)
        root        (str root-prefix u)
        depot-glob  (cond-> depot-path
                      (not (str/ends-with? depot-path "/..."))
                      (str "/..."))
        spec        (format-classic-client-spec {:client     client-name
                                                 :owner      (:p4/user conn)
                                                 :root       root
                                                 :depot-glob depot-glob})]
    (run-p4! conn [] ["client" "-i"] (.getBytes ^String spec "UTF-8"))
    (let [back (run-p4! conn [] ["client" "-o" client-name])
          spec-text (String. ^bytes back "UTF-8")]
      {:client/name       client-name
       :client/root       root
       :client/view-lines (parse-client-view-lines spec-text)})))

(defn delete-stream-client!
  "Tear down an ephemeral P4 client created by `create-stream-client!`.
   Metadata-only — does not touch any depot file. Idempotent: a missing
   client returns silently."
  [conn client-name]
  (try
    (run-p4! conn [] ["client" "-d" client-name])
    nil
    (catch Exception _ nil)))

(defn with-ephemeral-client
  "Create an ephemeral client bound to `stream-name`, call
   `(f conn-with-client client-info)`, and tear the client down in a
   `finally` block. Returns `f`'s return value.

   `conn-with-client` is `conn` with `:p4/client` populated. `client-info`
   is the map returned by `create-stream-client!` (carries
   `:client/view-lines`)."
  [conn stream-name f]
  (let [{:client/keys [name] :as info} (create-stream-client! conn stream-name)]
    (try
      (f (assoc conn :p4/client name) info)
      (finally
        (delete-stream-client! conn name)))))

(defn with-classic-client
  "Like `with-ephemeral-client` but creates a non-stream client whose
   `View:` maps `depot-path` to the client root. For classic
   (non-stream) depot paths."
  [conn depot-path f]
  (let [{:client/keys [name] :as info} (create-classic-client! conn depot-path)]
    (try
      (f (assoc conn :p4/client name) info)
      (finally
        (delete-stream-client! conn name)))))
