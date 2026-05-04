(ns clj-p4.shell.proc
  "Subprocess invocation. Bytes in, bytes out — no domain knowledge.
   Works on both JVM and Babashka via `babashka.process`.

   Two shapes:
   - `run!`     — collect output of a one-shot process.
   - `stream!`  — start a long-lived process and return a handle with
                  raw `:in`/`:out`/`:err` streams the caller drives."
  (:require [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream
                    ByteArrayOutputStream
                    InputStream
                    OutputStream)
           (java.util.concurrent TimeUnit)))

(defn- bp-opts [argv {:keys [stdin-bytes env cwd]}]
  (cond-> {:cmd       argv
           :extra-env env
           :dir       cwd
           :out       :stream
           :err       :stream}
    stdin-bytes (assoc :in (ByteArrayInputStream. stdin-bytes))))

(defn run!
  "Run `argv` to completion. Returns
   `{:exit :stdout-bytes :stderr :elapsed-ms}`. Stdout is left as raw bytes
   (P4 `-G` output is binary); stderr is decoded as UTF-8.

   Options:
     :stdin-bytes  byte-array fed to stdin.
     :env          map of env-var overrides (merged into inherited env).
     :timeout-ms   kill the process after the given duration; throws ex-info.
     :cwd          working directory."
  ([argv] (run! argv {}))
  ([argv {:keys [timeout-ms] :as opts}]
   (let [start    (System/currentTimeMillis)
         out-baos (ByteArrayOutputStream.)
         err-baos (ByteArrayOutputStream.)
         proc     (bp/process (bp-opts argv opts))
         out-pump (future (with-open [^InputStream s (:out proc)]
                            (io/copy s out-baos)))
         err-pump (future (with-open [^InputStream s (:err proc)]
                            (io/copy s err-baos)))]
     (try
       (let [exit (if timeout-ms
                    (do (.waitFor ^Process (:proc proc)
                                  timeout-ms TimeUnit/MILLISECONDS)
                        (when (.isAlive ^Process (:proc proc))
                          (.destroyForcibly ^Process (:proc proc))
                          (throw (ex-info "subprocess timed out"
                                          {:clj-p4/error :proc-timeout
                                           :argv         argv
                                           :timeout-ms   timeout-ms})))
                        (.exitValue ^Process (:proc proc)))
                    (.waitFor ^Process (:proc proc)))]
         @out-pump
         @err-pump
         {:exit         exit
          :stdout-bytes (.toByteArray out-baos)
          :stderr       (String. (.toByteArray err-baos) "UTF-8")
          :elapsed-ms   (- (System/currentTimeMillis) start)})
       (catch InterruptedException e
         (.destroyForcibly ^Process (:proc proc))
         (throw e))))))

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
  ([argv {:keys [env cwd]}]
   (let [proc (bp/process {:cmd       argv
                           :extra-env env
                           :dir       cwd
                           :in        :stream
                           :out       :stream
                           :err       :stream})]
     {:proc (:proc proc)
      :in   (:in proc)
      :out  (:out proc)
      :err  (:err proc)
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
