(ns clj-p4.integration.t9825-utf16-no-bom-test
  "Mirror of git-p4's `t/t9825-git-p4-handle-utf16-without-bom.sh`: the
   importer must not crash on a UTF-16 file that lacks a byte-order mark.
   The seed adds it via `pp-soft!` because some p4d configurations refuse
   the submit; we accept either outcome, but require the clone itself to
   succeed and produce a well-formed tree."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9825-utf16-without-bom-imports-cleanly
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9825") "utf16.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        tree   (ga/ls-tree target "refs/heads/main")
        utf16  (some #(when (= "src/utf16-no-bom.txt" (:path %)) %) tree)]
    (testing "clone itself produced a non-empty tree"
      (is (seq tree)))
    (when utf16
      (testing "utf16 file lands as a regular blob with non-empty bytes"
        (is (= "100644" (:mode utf16)))
        ;; Server-side transcoding for unicode-mode p4d turns a no-BOM file
        ;; into UTF-8 on read — pin only the byte count to avoid coupling
        ;; to that transformation.
        (is (pos? (count (ga/cat-blob-bytes target "refs/heads/main"
                                            "src/utf16-no-bom.txt"))))))))
