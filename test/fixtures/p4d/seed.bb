#!/usr/bin/env bb
;; Seed a fresh p4d with stream depots and sample changelists exercising
;; the scenarios our integration tests rely on (read-side parity with
;; git-p4 t98xx).
(ns seed
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]))

(def p4port    (System/getenv "P4PORT"))
(def p4user    (System/getenv "P4USER"))
(def p4passwd  (System/getenv "P4PASSWD"))
(def conn-args ["-p" (str "tcp:localhost:" p4port) "-u" p4user "-P" p4passwd])

(def workspace "clj_p4_seed_main")
(def wroot     "/p4/ws_main")
(def ws-args   ["-c" workspace])

(defn check! [{:keys [exit out err] :as result} label]
  (when-not (zero? exit)
    (binding [*out* *err*]
      (println (str "[seed] " label " failed (exit " exit ")"))
      (when (and out (seq (str out))) (println "stdout:" out))
      (when (and err (seq (str err))) (println "stderr:" err)))
    (throw (ex-info (str label " failed") {:exit exit})))
  result)

(defn p4! [& args]
  (check! (apply proc/shell {:out :string :err :string :continue true}
                 "p4" (concat conn-args args))
          (str "p4 " (str/join " " args))))

(defn p4-stdin! [stdin-str & args]
  (check! (apply proc/shell {:in stdin-str :out :string :err :string :continue true}
                 "p4" (concat conn-args args))
          (str "p4 " (str/join " " args))))

(defn pp! [& args]
  (apply p4! (concat ws-args args)))

(defn pp-soft! [& args]
  ;; Same as pp! but tolerates non-zero exit (used for "best-effort" steps
  ;; like the case-fold test on case-insensitive servers).
  (apply proc/shell {:out :string :err :string :continue true}
         "p4" (concat conn-args ws-args args)))

(defn write-text! [path content]
  (fs/create-dirs (fs/parent path))
  (spit (fs/file path) content))

(defn write-bytes! [path bytes]
  (fs/create-dirs (fs/parent path))
  (fs/write-bytes (fs/path path) bytes))

(defn make-symlink! [link-path target]
  (fs/create-dirs (fs/parent link-path))
  (when (fs/exists? link-path {:nofollow-links true})
    (fs/delete link-path))
  (fs/create-sym-link link-path target))

;; --- login -----------------------------------------------------------------
(p4-stdin! (str p4passwd "\n") "login")

;; --- create stream depot + streams -----------------------------------------
(p4-stdin! "Depot: stream\nType: stream\nMap: stream/...\nStreamDepth: //stream/1\n"
           "depot" "-i")

(p4-stdin! "Stream: //stream/main
Owner: admin
Name: main
Parent: none
Type: mainline
Description: mainline stream for clj-p4 fixtures
Options: allsubmit unlocked toparent fromparent mergedown
ParentView: noinherit
Paths:
\tshare ...
"
           "stream" "-t" "mainline" "-i")

(p4-stdin! "Stream: //stream/dev
Owner: admin
Name: dev
Parent: //stream/main
Type: development
Description: development child of main
Options: allsubmit unlocked toparent fromparent mergedown
ParentView: inherit
Paths:
\tshare ...
"
           "stream" "-t" "development" "-P" "//stream/main" "-i")

(p4-stdin! "Stream: //stream/release
Owner: admin
Name: release
Parent: //stream/main
Type: release
Description: release child of main
Options: allsubmit unlocked toparent fromparent mergedown
ParentView: inherit
Paths:
\tshare ...
"
           "stream" "-t" "release" "-P" "//stream/main" "-i")

(p4-stdin! "Stream: //stream/virtual
Owner: admin
Name: virtual
Parent: //stream/main
Type: virtual
Description: read-only filtered view of //stream/main/src/...
Options: allsubmit unlocked notoparent nofromparent mergedown
ParentView: inherit
Paths:
\tshare src/...
"
           "stream" "-t" "virtual" "-P" "//stream/main" "-i")

;; --- workspace + populate //stream/main ------------------------------------
(fs/create-dirs wroot)

(p4-stdin! (str "Client: " workspace "\n"
                "Owner: admin\n"
                "Root: " wroot "\n"
                "Stream: //stream/main\n"
                "Options: noallwrite noclobber nocompress unlocked nomodtime normdir\n"
                "LineEnd: local\n"
                "View:\n")
           "client" "-S" "//stream/main" "-i")

;; --- change 1: hello world (basic clone smoke, t9800) ----------------------
(write-text! (str wroot "/src/hello.txt") "hello world\n")
(pp! "add" (str wroot "/src/hello.txt"))
(pp! "submit" "-d" "initial: hello world")

;; --- change 2: t9802 filetypes — binary, +x, symlink, +k, +ko --------------
(write-bytes! (str wroot "/src/img.bin")
              (byte-array (map unchecked-byte [0x00 0x01 0x02 0x42 0x49 0x4e])))
(pp! "add" "-t" "binary" (str wroot "/src/img.bin"))

(write-text! (str wroot "/src/run.sh") "#!/bin/sh\necho run\n")
(fs/set-posix-file-permissions (str wroot "/src/run.sh") "rwxr-xr-x")
(pp! "add" "-t" "text+x" (str wroot "/src/run.sh"))

(make-symlink! (str wroot "/src/link") "hello.txt")
(pp! "add" "-t" "symlink" (str wroot "/src/link"))

(write-text! (str wroot "/src/kfile.txt") "$Id$\n$Author$\ncontent\n")
(pp! "add" "-t" "text+k" (str wroot "/src/kfile.txt"))

(write-text! (str wroot "/src/kofile.txt") "$Id$\n$Author$\ncontent\n")
(pp! "add" "-t" "text+ko" (str wroot "/src/kofile.txt"))

(pp! "submit" "-d" "filetypes: binary, +x, symlink, +k, +ko")

;; --- change 3: t9803 special filenames — spaces, $, quotes -----------------
(write-text! (str wroot "/src/oddly named/file with spaces.txt") "spaces are fine\n")
(write-text! (str wroot "/src/oddly named/$dollar.txt") "dollar\n")
(pp! "add" (str wroot "/src/oddly named/file with spaces.txt"))
(pp! "add" (str wroot "/src/oddly named/$dollar.txt"))
(pp! "submit" "-d" "t9803: shell metachars in filenames")

;; --- change 4: t9822 unicode paths and contents ----------------------------
(write-text! (str wroot "/src/iñtërnâtiônàl/utf8.txt") "héllo, 世界\n")
(pp! "add" (str wroot "/src/iñtërnâtiônàl/utf8.txt"))
(pp! "submit" "-d" "t9822: unicode paths and contents")

;; --- change 5: t9825 utf16 file without BOM --------------------------------
(write-bytes! (str wroot "/src/utf16-no-bom.txt")
              (.getBytes "utf16 no bom\n" "UTF-16LE"))
(pp-soft! "add" "-t" "utf16" (str wroot "/src/utf16-no-bom.txt"))
(pp-soft! "submit" "-d" "t9825: utf16 without BOM")

;; --- t9802 UTF-16 with BOM + CRLF text (extends filetype coverage) -------
;; Paired with the no-BOM utf16 above so the t9802 test exercises both
;; ends of the UTF-16-byte-passthrough contract. The CRLF file uses the
;; default `text` type — clj-p4 must preserve CRLF byte-for-byte (no
;; implicit eol normalisation, matching upstream's contract for plain
;; text without `.gitattributes eol=` rules).
(write-bytes! (str wroot "/src/utf16-with-bom.txt")
              (byte-array (concat [(unchecked-byte 0xff) (unchecked-byte 0xfe)]
                                  (.getBytes "utf16 BOM\n" "UTF-16LE"))))
(pp-soft! "add" "-t" "utf16" (str wroot "/src/utf16-with-bom.txt"))
(write-bytes! (str wroot "/src/crlf.txt")
              (.getBytes "line one\r\nline two\r\n" "UTF-8"))
(pp! "add" "-t" "text" (str wroot "/src/crlf.txt"))
(pp-soft! "submit" "-d" "t9802: utf16+BOM and CRLF preservation")

;; --- change 6: t9814 rename pair (move/add + move/delete) -----------------
(pp! "edit" (str wroot "/src/hello.txt"))
(pp! "move" (str wroot "/src/hello.txt") (str wroot "/src/greetings.txt"))
(pp! "submit" "-d" "t9814: rename hello.txt -> greetings.txt")

;; --- change 7: t9826 keep-empty-commit ------------------------------------
;; (Original seed notes this is fiddly; the no-op edit/revert here is
;; a placeholder that exercises the path without committing.)
(pp-soft! "edit" (str wroot "/src/img.bin"))
(pp-soft! "revert" (str wroot "/src/img.bin"))

;; --- change 8: t9827 filetype change (text -> binary) ---------------------
(write-text! (str wroot "/src/morphing.txt") "now binary?\n")
(pp! "add" "-t" "text" (str wroot "/src/morphing.txt"))
(pp! "submit" "-d" "t9827a: morphing.txt as text")

(pp! "edit" "-t" "binary" (str wroot "/src/morphing.txt"))
(write-bytes! (str wroot "/src/morphing.txt")
              (byte-array (concat [0x00 0x01]
                                  (map int "now binary"))))
(pp! "submit" "-d" "t9827b: morphing.txt switched to binary")

;; --- change 9: t9834 case-folding paths (case-only difference) -------------
(write-text! (str wroot "/src/case.txt") "lower\n")
(pp! "add" (str wroot "/src/case.txt"))
(pp! "submit" "-d" "t9834a: lowercase case.txt")

;; --- change 10: t9821 paths differing only in case ------------------------
;; Adds an uppercase sibling next to change 9's lowercase file. Strict
;; because the docker fixture's p4d is Linux and case-sensitive — the
;; matching test asserts both variants always coexist.
(write-text! (str wroot "/src/Case.txt") "upper\n")
(pp! "add" (str wroot "/src/Case.txt"))
(pp! "submit" "-d" "t9821: uppercase Case.txt next to case.txt")

;; --- change N: t9812 P4-wildcard chars in filenames ----------------------
;; Files whose literal disk names contain `@`, `#`, `*`, `%`. p4 add -f
;; takes the *literal* on-disk name and stores the depot path in p4's
;; escaped wire form (`%40 %23 %2A %25`); the importer must unescape on
;; the way into git.
(write-text! (str wroot "/src/wild/at@x.txt")    "at\n")
(write-text! (str wroot "/src/wild/hash#x.txt")  "hash\n")
(write-text! (str wroot "/src/wild/star*x.txt")  "star\n")
(write-text! (str wroot "/src/wild/pct%x.txt")   "pct\n")
(pp! "add" "-f"
     (str wroot "/src/wild/at@x.txt")
     (str wroot "/src/wild/hash#x.txt")
     (str wroot "/src/wild/star*x.txt")
     (str wroot "/src/wild/pct%x.txt"))
(pp! "submit" "-d" "t9812: wildcard chars in filenames")

;; --- change 11: t9830 symlink pointing at a directory ---------------------
;; `src/dirlink` → `oddly named` (a directory created in change 3). Tests
;; that mode 120000 + the directory-name blob round-trip into git intact.
(make-symlink! (str wroot "/src/dirlink") "oddly named")
(pp! "add" "-t" "symlink" (str wroot "/src/dirlink"))
(pp! "submit" "-d" "t9830: symlink pointing at a directory")

;; --- change 12: large file > 1 MiB ----------------------------------------
;; Fixture only needs the file to be ≥ 1 MiB; content randomness is irrelevant.
(write-bytes! (str wroot "/src/large.bin")
              (byte-array (* 2 1024 1024) (byte 0x42)))
(pp! "add" "-t" "binary" (str wroot "/src/large.bin"))
(pp! "submit" "-d" "large binary (2 MiB)")

;; --- t9818 block-mode user + group ----------------------------------------
;; Creates a non-admin user `block_test` constrained by group
;; `clj_p4_block_test` with `MaxResults: 5`. The matching integration
;; test issues `p4 changes` against the seeded depot using this user;
;; without `:changes-block-size` the call exceeds MaxResults and the
;; server refuses, with `:changes-block-size 5` the request succeeds in
;; chunks. A real ≥40-CL fixture would be over-engineered — the seed
;; already has ≥10 changes, comfortably more than the limit.
(p4-stdin! "User: block_test\nEmail: block@example.com\nFullName: t9818 block-mode user\n"
           "user" "-f" "-i")
;; Admin sets the new user's password directly (no old-password challenge).
(p4-stdin! (str p4passwd "\n" p4passwd "\n") "passwd" "block_test")
;; MaxResults: 12 means any `p4 changes` whose result set exceeds 12
;; fails. The seed has 13 CLs on //stream/main, so an unbounded clone
;; via block_test reliably trips the limit. The matching test uses
;; block-size 5 to stay safely under it. (We can't set MaxResults too
;; low — the importer's ephemeral-client `changes -l -m 1` probe
;; touches client metadata that internally scans more rows than the
;; visible result count, and a too-tight cap rejects even the head
;; probe.)
(p4-stdin! (str "Group: clj_p4_block_test\n"
                "MaxResults: 12\n"
                "MaxScanRows: 1000\n"
                "Timeout: 43200\n"
                "Users:\n\tblock_test\n")
           "group" "-i")

(println "[seed] done. Latest change:")
(p4! "changes" "-m1")
