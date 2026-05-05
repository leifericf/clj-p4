(ns clj-p4.integration.t9802-filetypes-test
  "Mirror of git-p4's `t/t9802-git-p4-filetypes.sh`: every filetype the
   seed exercises (binary, text+x, symlink, text+k, text+ko) round-trips
   into the imported tree with the expected mode and bytes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(defn- by-path [tree]
  (into {} (map (juxt :path identity)) tree))

(deftest ^:integration t9802-filetypes-render-correct-modes
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9802") "ftypes.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        tree   (by-path (ga/ls-tree target "refs/heads/main"))]
    (testing "binary file → mode 100644 with byte-exact content"
      (is (= "100644" (get-in tree ["src/img.bin" :mode])))
      (is (= [0x00 0x01 0x02 0x42 0x49 0x4e]
             (mapv #(bit-and 0xff %)
                   (vec (ga/cat-blob-bytes target "refs/heads/main"
                                           "src/img.bin"))))))
    (testing "+x flag → mode 100755"
      (is (= "100755" (get-in tree ["src/run.sh" :mode]))))
    (testing "symlink → mode 120000, blob == target path"
      (is (= "120000" (get-in tree ["src/link" :mode])))
      (is (= "hello.txt"
             (ga/cat-blob-string target "refs/heads/main" "src/link"))))
    (testing "+k / +ko stay as text mode 100644"
      (is (= "100644" (get-in tree ["src/kfile.txt" :mode])))
      (is (= "100644" (get-in tree ["src/kofile.txt" :mode]))))
    (testing "broken symlink target survives a later rename of the file
              it points at (p4-fusion bug #90 regression check)"
      ;; src/link was added in change 2 pointing at "hello.txt"; change 6
      ;; renamed src/hello.txt → src/greetings.txt. The symlink is not
      ;; auto-rewritten, so its blob content stays "hello.txt" even
      ;; though that file no longer exists in the tree.
      (is (= "120000" (get-in tree ["src/link" :mode])))
      (is (= "hello.txt"
             (ga/cat-blob-string target "refs/heads/main" "src/link"))
          "symlink content must be the original target, no rewrite"))))
