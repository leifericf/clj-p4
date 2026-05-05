(ns clj-p4.integration.t9810-rcs-keywords-test
  "Mirror of git-p4's `t/t9810-git-p4-rcs.sh` (read side): files of type
   `text+k` and `text+ko` keep their RCS keyword markers literal in the
   imported tree, instead of carrying the server's per-checkout
   expansion (`$Id: //... $`). Verifies that `clj-p4.execute`'s
   `keyword-expand?` correctly passes `-k` to `p4 print` for these
   filetypes — without that, blob SHAs would shift on every sync."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(deftest ^:integration t9810-keyword-files-import-unexpanded
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9810") "rcs.git"))
        _      (api/clone! {:conn conn :source "//stream/main" :target target})
        kfile  (ga/cat-blob-string target "refs/heads/main" "src/kfile.txt")
        kofile (ga/cat-blob-string target "refs/heads/main" "src/kofile.txt")]
    (testing "+k file preserves the literal $Id$ / $Author$ markers"
      (is (str/includes? kfile "$Id$"))
      (is (str/includes? kfile "$Author$")))
    (testing "+k file does NOT carry an expanded `$Id: //... $` form"
      (is (not (re-find #"\$Id:[^$]*\$" kfile))
          (str "expanded keyword leaked into +k blob: " (pr-str kfile))))
    (testing "+ko file preserves the literal markers (selective subset)"
      (is (str/includes? kofile "$Id$"))
      (is (str/includes? kofile "$Author$"))
      (is (not (re-find #"\$Id:[^$]*\$" kofile))))))
