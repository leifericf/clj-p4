(ns clj-p4.integration.git-assert
  "Git-side assertion helpers shared across the t98xx integration ports.
   Wraps `git ls-tree`, `git cat-file`, and a temp-dir factory so each
   test ns can stay focused on the scenario it's exercising."
  (:require [clj-p4.io.subprocess :as proc]
            [clojure.string :as str])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn tmp-dir
  "Create a fresh temp directory under the system tmp dir. Caller owns
   the path; we don't track or delete it (matches existing test idiom)."
  [prefix]
  (.toFile (Files/createTempDirectory
            prefix (make-array FileAttribute 0))))

(defn ls-tree
  "Parse `git ls-tree -r <ref>` into a vector of
   `{:mode :type :sha :path}` maps. Forces `core.quotePath=false` so
   non-ASCII paths come back raw instead of octal-escaped."
  [target ref]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked!
         ["git" "-C" (str target) "-c" "core.quotePath=false"
          "ls-tree" "-r" ref])
        text (String. ^bytes stdout-bytes "UTF-8")]
    (->> (str/split-lines text)
         (remove str/blank?)
         (mapv (fn [line]
                 (let [[meta path] (str/split line #"\t" 2)
                       [mode type sha] (str/split meta #" +" 3)]
                   {:mode mode :type type :sha sha :path path}))))))

(defn cat-blob-bytes
  "Read the blob at `<ref-or-sha>:<path>` (or the blob SHA directly when
   `path` is nil) and return its raw bytes."
  ([target sha]
   (:stdout-bytes
    (proc/run-checked!
     ["git" "-C" (str target) "cat-file" "blob" sha])))
  ([target ref path]
   (cat-blob-bytes target (str ref ":" path))))

(defn cat-blob-string
  "UTF-8 string view of `cat-blob-bytes`."
  ([target ref path] (cat-blob-string target ref path "UTF-8"))
  ([target ref path encoding]
   (String. ^bytes (cat-blob-bytes target ref path) encoding)))

(defn find-commit-sha
  "Return the SHA of the first commit reachable from `ref` whose subject
   line contains `subject-substring`. Nil if none."
  [target ref subject-substring]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked!
         ["git" "-C" (str target) "log" "--pretty=format:%H %s" ref])
        text (String. ^bytes stdout-bytes "UTF-8")]
    (some (fn [line]
            (let [[sha subject] (str/split line #" " 2)]
              (when (and subject (str/includes? subject subject-substring))
                sha)))
          (str/split-lines text))))
