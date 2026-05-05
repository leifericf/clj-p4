#!/usr/bin/env bb
;; Seed the non-unicode p4d with a depot whose changelist descriptions
;; deliberately contain CP-1252 / Latin-1 byte sequences. Used by
;; `t9835_metadata_encoding_test` to exercise the three decoding
;; strategies in `clj-p4.marshal/metadata-decoder`.
(ns seed
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]))

(def p4port    (System/getenv "P4PORT"))
(def p4user    (System/getenv "P4USER"))
(def p4passwd  (System/getenv "P4PASSWD"))
(def conn-args ["-p" (str "tcp:localhost:" p4port) "-u" p4user "-P" p4passwd])

(def workspace "clj_p4_seed_mixed")
(def wroot     "/p4/ws_mixed")
(def ws-args   ["-c" workspace])

(defn check! [{:keys [exit out err] :as result} label]
  (when-not (zero? exit)
    (binding [*out* *err*]
      (println (str "[seed-mixed] " label " failed (exit " exit ")"))
      (when (and out (seq (str out))) (println "stdout:" out))
      (when (and err (seq (str err))) (println "stderr:" err)))
    (throw (ex-info (str label " failed") {:exit exit})))
  result)

(defn p4! [& args]
  (check! (apply proc/shell {:out :string :err :string :continue true}
                 "p4" (concat conn-args args))
          (str "p4 " (str/join " " args))))

(defn p4-stdin! [^bytes stdin-bytes & args]
  ;; Feed raw bytes via stdin so non-UTF-8 description bytes survive.
  (check! (apply proc/shell {:in (java.io.ByteArrayInputStream. stdin-bytes)
                             :out :string :err :string :continue true}
                 "p4" (concat conn-args args))
          (str "p4 " (str/join " " args))))

(defn pp! [& args]
  (apply p4! (concat ws-args args)))

(defn write-text! [path content]
  (fs/create-dirs (fs/parent path))
  (spit (fs/file path) content))

(defn b [& xs]
  (byte-array (map unchecked-byte xs)))

;; --- login (admin password is plain ASCII) ---------------------------------
(check! (apply proc/shell {:in (str p4passwd "\n") :out :string :err :string
                           :continue true}
               "p4" (concat conn-args ["login"]))
        "p4 login")

;; --- depot + workspace -----------------------------------------------------
(p4-stdin! (.getBytes "Depot: depot\nType: local\nMap: depot/...\n" "UTF-8")
           "depot" "-i")

(fs/create-dirs wroot)

(p4-stdin! (.getBytes (str "Client: " workspace "\n"
                            "Owner: admin\n"
                            "Root: " wroot "\n"
                            "Options: noallwrite noclobber nocompress unlocked nomodtime normdir\n"
                            "LineEnd: local\n"
                            "View:\n"
                            "\t//depot/... //" workspace "/...\n")
                      "UTF-8")
           "client" "-i")

;; --- seed CL: pure ASCII description ---------------------------------------
;; Smoke baseline: clean UTF-8/ASCII commit so the strict decoder has
;; something to chew on.
(write-text! (str wroot "/ascii.txt") "ascii ok\n")
(pp! "add" (str wroot "/ascii.txt"))
(pp! "submit" "-d" "ascii baseline")

;; --- seed CL: description containing the CP-1252 right single quote -------
;; The byte 0x92 is CP-1252's "right single quotation mark" (U+2019).
;; In raw bytes: `it\x92s`. UTF-8 strict decoding fails (0x92 alone is
;; an invalid continuation byte). The :fallback strategy with
;; metadataFallbackEncoding=cp1252 should recover "it’s".
(write-text! (str wroot "/cp1252.txt") "cp1252 description\n")
(pp! "add" (str wroot "/cp1252.txt"))
(let [hdr   (.getBytes "Change: new\nClient: clj_p4_seed_mixed\nUser: admin\nStatus: new\nDescription:\n\t" "UTF-8")
      body  (b 0x69 0x74 0x92 0x73)              ; "it’s" CP-1252
      tail  (.getBytes "\n\nFiles:\n\t//depot/cp1252.txt\t# add\n" "UTF-8")
      spec  (byte-array (concat hdr body tail))]
  (p4-stdin! spec "-c" workspace "submit" "-i"))

;; --- seed CL: description in pure Latin-1 ---------------------------------
;; "señor" with ñ as the single byte 0xF1. UTF-8 strict fails; CP-1252
;; or ISO-8859-1 fallback both decode 0xF1 as ñ.
(write-text! (str wroot "/latin1.txt") "latin1 description\n")
(pp! "add" (str wroot "/latin1.txt"))
(let [hdr  (.getBytes "Change: new\nClient: clj_p4_seed_mixed\nUser: admin\nStatus: new\nDescription:\n\t" "UTF-8")
      body (b 0x73 0x65 0xf1 0x6f 0x72)          ; "señor" Latin-1
      tail (.getBytes "\n\nFiles:\n\t//depot/latin1.txt\t# add\n" "UTF-8")
      spec (byte-array (concat hdr body tail))]
  (p4-stdin! spec "-c" workspace "submit" "-i"))

(println "[seed-mixed] done. Latest changes:")
(p4! "changes" "-m5")
