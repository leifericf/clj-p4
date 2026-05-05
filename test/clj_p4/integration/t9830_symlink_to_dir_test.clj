(ns clj-p4.integration.t9830-symlink-to-dir-test
  "Mirror of git-p4's `t/t9830-git-p4-symlink-dir.sh`: a symlink pointing
   at a directory (rather than a file) imports as mode 120000 with the
   directory name as its blob content — same encoding git itself uses."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9830-symlink-to-directory
  (let [conn    (fix/admin-conn-with-ticket)
        target  (str (io/file (ga/tmp-dir "clj-p4-t9830") "dirlink.git"))
        _       (api/clone! {:conn conn :source "//stream/main" :target target})
        tree    (ga/ls-tree target "refs/heads/main")
        dirlink (some #(when (= "src/dirlink" (:path %)) %) tree)]
    (testing "dirlink entry exists and is encoded as a symlink"
      (is (some? dirlink))
      (is (= "120000" (:mode dirlink))))
    (testing "blob content is the directory target path"
      (is (= "oddly named"
             (ga/cat-blob-string target "refs/heads/main" "src/dirlink"))))))
