(ns clj-p4.integration.t9822-unicode-test
  "Mirror of git-p4's `t/t9822-git-p4-path-encoding.sh`: a non-ASCII path
   and a UTF-8 file body both round-trip byte-for-byte. Charset is set on
   the connection (`:p4/charset :utf8`) by the fixture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9822-unicode-paths-and-contents
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9822") "unicode.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        path   "src/iñtërnâtiônàl/utf8.txt"
        paths  (set (map :path (ga/ls-tree target "refs/heads/main")))]
    (testing "non-ASCII path lands as UTF-8 in the tree"
      (is (contains? paths path)))
    (testing "UTF-8 content round-trips byte-for-byte"
      (is (= "héllo, 世界\n"
             (ga/cat-blob-string target "refs/heads/main" path))))))
