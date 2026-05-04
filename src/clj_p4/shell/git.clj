(ns clj-p4.shell.git
  "Git as a host-bound dependency. Wraps `git init --bare` for repo
   creation and `git fast-import` as a single long-lived subprocess for
   writes — same protocol git-p4 itself uses internally.

   The `--export-marks=…` + `--import-marks-if-exists=…` pair gives
   resume-on-crash for free: marks file is rewritten at every commit and
   re-loaded on restart."
  (:require [clj-p4.shell.proc :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io OutputStream)))

(defn init-bare!
  "Create a new bare git repo at `target-dir`. Returns the path."
  [target-dir]
  (proc/run-checked! ["git" "init" "--bare" (str target-dir)])
  (str target-dir))

(defn fast-import-start
  "Spawn `git fast-import` rooted at `target-dir`. Returns a handle:
     {:proc :in :out :err :argv :marks-file}

   Options:
     :marks-file   path the subprocess writes/reads marks to/from.
                   Enables resume across restarts.
     :extra-args   additional args appended after defaults."
  ([target-dir] (fast-import-start target-dir {}))
  ([target-dir {:keys [marks-file extra-args]}]
   (let [argv (cond-> ["git" "-C" (str target-dir) "fast-import"
                       "--quiet" "--force"]
                marks-file (into [(str "--export-marks=" marks-file)
                                  (str "--import-marks-if-exists=" marks-file)])
                extra-args (into extra-args))
         handle (proc/stream! argv)]
     (assoc handle :marks-file marks-file))))

(defn- write-bytes! [handle ^bytes bs]
  (let [^OutputStream o (:in handle)]
    (.write o bs)))

(defn- write-line! [handle ^String s]
  (write-bytes! handle (.getBytes (str s "\n") "UTF-8")))

(defn- write-data-block!
  "Emit a fast-import `data <N>\\n<bytes>\\n` block."
  [handle ^bytes payload]
  (write-line! handle (str "data " (alength payload)))
  (write-bytes! handle payload)
  (write-bytes! handle (.getBytes "\n" "UTF-8")))

(defn blob!
  "Write a blob to the fast-import stream. Returns the mark."
  [handle mark ^bytes bytes]
  (write-line! handle "blob")
  (write-line! handle (str "mark :" mark))
  (write-data-block! handle bytes)
  mark)

(defn- format-committer
  "`committer Name <email> <epoch-secs> <tz>` line."
  [{:keys [name email time-ms tz]}]
  (let [secs (long (/ (or time-ms 0) 1000))]
    (str "committer " (or name "clj-p4") " <" (or email "clj-p4@local") "> "
         secs " " (or tz "+0000"))))

(defn- file-op-line
  "Render one file op for emit-commit's :files vector.
   Shapes:
     {:op :M  :mode \"100644\" :mark M :path \"a.cpp\"}    ; modify (blob via mark)
     {:op :M  :mode \"100644\" :inline true :bytes <bytes> :path \"a.cpp\"}
     {:op :D  :path \"a.cpp\"}
     {:op :R  :from \"old\"   :to \"new\"}
     {:op :C  :from \"src\"   :to \"dst\"}"
  [{:keys [op mode mark path inline from to] :as f}]
  (case op
    :M (cond
         inline   (str "M " mode " inline " path)
         mark     (str "M " mode " :" mark " " path)
         :else    (throw (ex-info "modify needs :mark or :inline" {:op f})))
    :D (str "D " path)
    :R (str "R " from " " to)
    :C (str "C " from " " to)))

(defn emit-commit!
  "Emit a single commit on `ref` (e.g. `\"refs/heads/main\"`). Required:

     {:ref \"refs/heads/main\"
      :mark   <int>            ; commit mark (use the changelist number)
      :committer {:name :email :time-ms :tz}
      :message <string>
      :files   [<file-op-map> …]}

   Optional:
     :from   parent — `:<mark>` or sha1 (for non-first commits)
     :merge  vector of additional parents."
  [handle {:keys [ref mark committer message files from merge]}]
  (write-line! handle (str "commit " ref))
  (write-line! handle (str "mark :" mark))
  (write-line! handle (format-committer committer))
  (write-data-block! handle (.getBytes ^String (or message "") "UTF-8"))
  (when from
    (write-line! handle (str "from " from)))
  (doseq [m (or merge [])]
    (write-line! handle (str "merge " m)))
  (doseq [f files]
    (write-line! handle (file-op-line f))
    (when (and (= :M (:op f)) (:inline f))
      (write-data-block! handle (:bytes f))))
  (write-bytes! handle (.getBytes "\n" "UTF-8")))

(defn checkpoint!
  "Force fast-import to flush its current state and rewrite the marks file."
  [handle]
  (write-line! handle "checkpoint"))

(defn- format-tagger
  [{:keys [name email time-ms tz]}]
  (let [secs (long (/ (or time-ms 0) 1000))]
    (str "tagger " (or name "clj-p4") " <" (or email "clj-p4@local") "> "
         secs " " (or tz "+0000"))))

(defn emit-tag!
  "Emit an annotated tag through fast-import. `from` is `:<mark>` or a
   ref/SHA. `tagger` shape mirrors `:committer` on `emit-commit!`."
  [handle {:keys [name from tagger message]}]
  (write-line! handle (str "tag " name))
  (write-line! handle (str "from " from))
  (write-line! handle (format-tagger tagger))
  (write-data-block! handle (.getBytes ^String (or message "") "UTF-8")))

(defn fast-import-close!
  "Send `done`, close stdin, wait for exit. Returns `{:exit :stderr}`."
  [handle]
  (write-line! handle "done")
  (proc/stream-close! handle))

(defn notes-add!
  "Attach a git note to `ref` in `target-dir`'s notes-ref. Out-of-band
   from fast-import, since fast-import has no direct notes operation."
  [target-dir notes-ref ref message]
  (proc/run-checked! ["git" "-C" (str target-dir)
                      "notes" "--ref" notes-ref
                      "add" "-m" message ref]))

(defn rev-list
  "Run `git rev-list <ref>` and return the seq of commit SHAs."
  [target-dir ref]
  (let [{:keys [stdout-bytes]} (proc/run-checked!
                                ["git" "-C" (str target-dir)
                                 "rev-list" ref])]
    (->> (String. ^bytes stdout-bytes "UTF-8")
         str/split-lines
         (remove str/blank?))))

(defn commit-count
  "Count commits reachable from `ref`."
  [target-dir ref]
  (count (rev-list target-dir ref)))
