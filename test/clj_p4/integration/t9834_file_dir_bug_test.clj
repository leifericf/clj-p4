(ns clj-p4.integration.t9834-file-dir-bug-test
  "Mirror of git-p4's `t/t9834-git-p4-file-dir-bug.sh`: a depot path
   that is a file in one CL, deleted in another, and re-created as a
   directory containing children must round-trip into git cleanly at
   each revision. Catches importers that confuse a file entry and a
   directory entry living at the same path across history.

   The fixture submits three CLs around `src/becomes-dir`:
   - 9834a: regular file with content `\"I am a regular file\\n\"`
   - 9834b: file deleted
   - 9834c: directory with `inside.txt` underneath."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9834-path-as-file-then-dir-round-trips
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9834") "fdir.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        sha-a  (ga/find-commit-sha target "refs/heads/main" "t9834a")
        sha-b  (ga/find-commit-sha target "refs/heads/main" "t9834b")
        sha-c  (ga/find-commit-sha target "refs/heads/main" "t9834c")]
    (testing "every fixture CL produced an import commit"
      (is (string? sha-a))
      (is (string? sha-b))
      (is (string? sha-c)))
    (testing "9834a: src/becomes-dir is a regular file with the seeded content"
      (let [tree (ga/ls-tree target sha-a)
            entry (some #(when (= "src/becomes-dir" (:path %)) %) tree)]
        (is (some? entry))
        (is (= "100644" (:mode entry)))
        (is (= "I am a regular file\n"
               (ga/cat-blob-string target sha-a "src/becomes-dir")))))
    (testing "9834b: src/becomes-dir is gone (no entry under that name)"
      (let [tree  (ga/ls-tree target sha-b)
            paths (set (map :path tree))]
        (is (not (contains? paths "src/becomes-dir")))
        (is (not-any? #(re-find #"^src/becomes-dir/" %) paths))))
    (testing "9834c: src/becomes-dir is a directory with inside.txt"
      (let [tree  (ga/ls-tree target sha-c)
            paths (set (map :path tree))]
        (is (not (contains? paths "src/becomes-dir"))
            "no file entry at the bare path")
        (is (contains? paths "src/becomes-dir/inside.txt"))
        (is (= "now under a dir\n"
               (ga/cat-blob-string target sha-c
                                   "src/becomes-dir/inside.txt")))))))
