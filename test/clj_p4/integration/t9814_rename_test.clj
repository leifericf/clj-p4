(ns clj-p4.integration.t9814-rename-test
  "Mirror of git-p4's `t/t9814-git-p4-rename.sh`: a `p4 move` becomes a
   delete + add of identical bytes in fast-import, which `git log -M`
   re-detects as a rename."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]
            [clj-p4.shell.proc :as proc]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9814-p4-move-becomes-git-rename
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9814") "rename.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        paths  (set (map :path (ga/ls-tree target "refs/heads/main")))]
    (testing "rename target exists with the original content"
      (is (contains? paths "src/greetings.txt"))
      (is (= "hello world\n"
             (ga/cat-blob-string target "refs/heads/main"
                                 "src/greetings.txt"))))
    (testing "rename source is gone from HEAD"
      (is (not (contains? paths "src/hello.txt"))))
    (testing "git -M finds the rename across the move commit"
      (let [{:keys [stdout-bytes]}
            (proc/run-checked!
             ["git" "-C" target "log" "-M" "--diff-filter=R"
              "--name-status" "--pretty=format:" "refs/heads/main"])
            out (String. ^bytes stdout-bytes "UTF-8")]
        (is (re-find #"R\d+\s+src/hello\.txt\s+src/greetings\.txt" out)
            (str "expected rename detection in: " out))))))
