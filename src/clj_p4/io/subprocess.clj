(ns clj-p4.io.subprocess
  "Subprocess invocation. Bytes in, bytes out — no domain knowledge.

   Two shapes:
   - `run!`     — collect output of a one-shot process.
   - `stream!`  — start a long-lived process and return a handle with
                  raw `:in`/`:out`/`:err` streams the caller drives."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream
                    InputStream
                    OutputStream)
           (java.util.concurrent TimeUnit)))

(defn- ^Process start-process
  "Start a subprocess via `ProcessBuilder`. Stdin / stdout / stderr are
   piped (the caller drains them). `:env` extends the inherited env;
   `:cwd` sets the working directory."
  [argv {:keys [env cwd]}]
  (let [pb (ProcessBuilder. ^java.util.List (vec argv))]
    (when cwd
      (.directory pb (io/file cwd)))
    (when env
      (let [m (.environment pb)]
        (doseq [[k v] env] (.put m (str k) (str v)))))
    (.start pb)))

(defn- write-stdin!
  "Write `bytes` to the process's stdin and close it."
  [^Process proc ^bytes bytes]
  (with-open [^OutputStream out (.getOutputStream proc)]
    (.write out bytes)))

(defn- run-once!
  [argv {:keys [stdin-bytes timeout-ms] :as opts}]
  (let [start    (System/currentTimeMillis)
        out-baos (ByteArrayOutputStream.)
        err-baos (ByteArrayOutputStream.)
        proc     (start-process argv opts)
        out-pump (future (with-open [^InputStream s (.getInputStream proc)]
                           (io/copy s out-baos)))
        err-pump (future (with-open [^InputStream s (.getErrorStream proc)]
                           (io/copy s err-baos)))]
    (try
      (when stdin-bytes (write-stdin! proc stdin-bytes))
      (when-not stdin-bytes (.close (.getOutputStream proc)))
      (let [exit (if timeout-ms
                   (do (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)
                       (when (.isAlive proc)
                         (.destroyForcibly proc)
                         (throw (ex-info "subprocess timed out"
                                         {:clj-p4/error :proc-timeout
                                          :argv         argv
                                          :timeout-ms   timeout-ms})))
                       (.exitValue proc))
                   (.waitFor proc))]
        @out-pump
        @err-pump
        {:exit         exit
         :stdout-bytes (.toByteArray out-baos)
         :stderr       (String. (.toByteArray err-baos) "UTF-8")
         :elapsed-ms   (- (System/currentTimeMillis) start)})
      (catch InterruptedException e
        (.destroyForcibly proc)
        (throw e)))))

(def ^:private transient-stderr-re
  #"(?i)connection|reset|timeout|temporarily|broken pipe|RPC")

(defn- transient-failure?
  "True if `result` looks like a transient network/server failure worth
   retrying (non-zero exit + stderr that mentions a known-flaky pattern)."
  [{:keys [exit stderr]}]
  (and (not (zero? exit))
       stderr
       (boolean (re-find transient-stderr-re stderr))))

(defn run!
  "Run `argv` to completion. Returns
   `{:exit :stdout-bytes :stderr :elapsed-ms}`. Stdout is left as raw bytes
   (P4 `-G` output is binary); stderr is decoded as UTF-8.

   Options:
     :stdin-bytes      byte-array fed to stdin.
     :env              map of env-var overrides (merged into inherited env).
     :timeout-ms       kill the process after the given duration; throws ex-info.
     :cwd              working directory.
     :retries          retry the call up to N times on transient failure.
                       Default 0 (no retries).
     :retry-backoff-ms initial backoff in ms; doubled each attempt. Default 100."
  ([argv] (run! argv {}))
  ([argv {:keys [retries retry-backoff-ms]
          :or   {retries          0
                 retry-backoff-ms 100}
          :as   opts}]
   (let [opts' (dissoc opts :retries :retry-backoff-ms)]
     (loop [attempt 0]
       (let [outcome (try {:result (run-once! argv opts')}
                          (catch java.io.IOException e {:throw e}))]
         (cond
           (and (:throw outcome) (>= attempt retries))
           (throw (:throw outcome))

           (:throw outcome)
           (do (Thread/sleep (long (* retry-backoff-ms (Math/pow 2 attempt))))
               (recur (inc attempt)))

           (and (transient-failure? (:result outcome)) (< attempt retries))
           (do (Thread/sleep (long (* retry-backoff-ms (Math/pow 2 attempt))))
               (recur (inc attempt)))

           :else
           (:result outcome)))))))

(defn run-checked!
  "Like `run!` but throws `ex-info` on non-zero exit."
  ([argv] (run-checked! argv {}))
  ([argv opts]
   (let [{:keys [exit stderr] :as result} (run! argv opts)]
     (when-not (zero? exit)
       (throw (ex-info (str "subprocess " (first argv) " failed: "
                            (str/trim (or stderr "")))
                       {:clj-p4/error :proc-failed
                        :argv         argv
                        :exit         exit
                        :stderr       stderr})))
     result)))

(defn stream!
  "Start a long-lived subprocess. Returns a handle:
     {:proc <java.lang.Process>
      :in   OutputStream  ; write here to feed stdin
      :out  InputStream   ; read here for stdout
      :err  InputStream}

   The caller is responsible for closing `:in` to signal EOF, and for calling
   `stream-close!` to wait for exit."
  ([argv] (stream! argv {}))
  ([argv opts]
   (let [proc (start-process argv opts)]
     {:proc proc
      :in   (.getOutputStream proc)
      :out  (.getInputStream proc)
      :err  (.getErrorStream proc)
      :argv argv})))

(defn stream-close!
  "Close stdin (if still open), wait for the process, return
   `{:exit :stderr}`."
  [{:keys [^Process proc ^OutputStream in ^InputStream err]}]
  (try (when in (.close in)) (catch Exception _))
  (let [err-baos (ByteArrayOutputStream.)
        err-pump (future (try (io/copy err err-baos) (catch Exception _)))
        exit     (.waitFor proc)]
    @err-pump
    {:exit   exit
     :stderr (String. (.toByteArray err-baos) "UTF-8")}))
