#!/usr/bin/env bb
;; Initialise /p4 on first run; create the admin user; start p4d.
(ns entrypoint
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]))

(def p4root      (System/getenv "P4ROOT"))
(def p4port      (System/getenv "P4PORT"))
(def p4user      (System/getenv "P4USER"))
(def p4passwd    (System/getenv "P4PASSWD"))
(def init-marker (str p4root "/.clj-p4-initialised"))
(def listen      (str "tcp:" p4port))
(def local       (str "tcp:localhost:" p4port))

(defn die! [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 1))

(defn p4d! [& args]
  (apply proc/shell {:continue true}
         "p4d" "-r" p4root "-L" (str p4root "/log") args))

(defn p4! [& args]
  (apply proc/shell {:continue true} "p4" "-p" local args))

(defn p4-stdin! [stdin-str & args]
  (apply proc/shell {:in stdin-str :continue true} "p4" "-p" local args))

(defn start-p4d-bg! []
  (p4d! "-p" listen "-d")
  (loop [tries 30]
    (let [{:keys [exit]} (proc/shell {:out :string :err :string :continue true}
                                     "p4" "-p" local "info")]
      (cond
        (zero? exit) :ok
        (zero? tries) (die! "p4d failed to come up")
        :else (do (Thread/sleep 500) (recur (dec tries)))))))

(defn stop-p4d! []
  (let [{:keys [exit]} (proc/shell {:out :string :err :string :continue true}
                                   "pgrep" "-x" "p4d")]
    (when (zero? exit)
      (p4! "-u" p4user "-P" p4passwd "admin" "stop")
      (Thread/sleep 1000))))

(defn first-run-init! []
  (println "[entrypoint] first-run initialisation")
  (p4d! "-xi") ; set unicode mode
  (start-p4d-bg!)
  ;; Create the admin user (no password yet).
  (p4-stdin! (str "User: " p4user "\n"
                  "Email: " p4user "@example.com\n"
                  "FullName: clj-p4 admin\n")
             "user" "-f" "-i")
  ;; Set the password via two-line stdin (matches `p4 passwd`'s prompts).
  (p4-stdin! (str p4passwd "\n" p4passwd "\n") "-u" p4user "passwd")
  (when (fs/executable? "/usr/local/bin/seed.bb")
    (println "[entrypoint] seeding fixtures")
    (let [{:keys [exit]} (proc/shell {:continue true} "/usr/local/bin/seed.bb")]
      (when-not (zero? exit)
        (binding [*out* *err*] (println "[entrypoint] seed.bb failed (continuing)")))))
  (stop-p4d!)
  (spit init-marker ""))

(defn serve! []
  (println (str "[entrypoint] starting p4d on " listen))
  (let [{:keys [exit]} @(proc/process ["p4d" "-p" listen
                                       "-r" p4root "-L" (str p4root "/log")]
                                      {:inherit true})]
    (System/exit exit)))

(defn seed! []
  (start-p4d-bg!)
  (proc/shell {:continue true} "/usr/local/bin/seed.bb")
  (stop-p4d!))

(fs/create-dirs p4root)
(when-not (fs/exists? init-marker)
  (first-run-init!))

(case (or (first *command-line-args*) "serve")
  "serve" (serve!)
  "seed"  (seed!)
  (let [{:keys [exit]} @(proc/process *command-line-args* {:inherit true})]
    (System/exit exit)))
