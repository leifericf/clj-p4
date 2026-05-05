(ns clj-p4.integration.t9803-shell-metachar-filenames-test
  "Mirror of git-p4's `t/t9803-git-p4-shell-metachars.sh`: filenames
   containing whitespace and `$` survive the depot → git path translation
   without expansion or quoting damage."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9803-spaces-and-dollar-in-filenames
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9803") "metachar.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        paths  (set (map :path (ga/ls-tree target "refs/heads/main")))]
    (testing "spaces survive in both the directory and file segment"
      (is (contains? paths "src/oddly named/file with spaces.txt")))
    (testing "literal $ is not expanded as a shell variable"
      (is (contains? paths "src/oddly named/$dollar.txt")))
    (testing "content of the space-named file round-trips"
      (is (= "spaces are fine\n"
             (ga/cat-blob-string target "refs/heads/main"
                                 "src/oddly named/file with spaces.txt"))))))
