(ns clj-p4.integration.t9817-exclude-test
  "Mirror of git-p4's `t/t9817-git-p4-exclude.sh`: `:excludes` patterns
   drop matched paths from the imported tree but leave the rest intact.
   Compared against an unfiltered baseline clone of the same depot to
   prove the exclusion (and only the exclusion) caused the difference."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(defn- excluded? [path]
  (re-find #"^src/oddly named/" path))

(deftest ^:integration t9817-exclude-drops-matched-paths
  (let [conn      (fix/admin-conn-with-ticket)
        base-dir  (ga/tmp-dir "clj-p4-t9817")
        without   (str (io/file base-dir "without.git"))
        with      (str (io/file base-dir "with.git"))]
    (api/clone! {:conn conn :source "//stream/main" :target without})
    (api/clone! {:conn conn :source "//stream/main" :target with
                 :excludes ["src/oddly named/"]})
    (let [unfiltered (set (map :path (ga/ls-tree without "refs/heads/main")))
          filtered   (set (map :path (ga/ls-tree with    "refs/heads/main")))]
      (testing "baseline clone contains the soon-to-be-excluded subtree"
        (is (some excluded? unfiltered)))
      (testing "filtered clone drops every matched path"
        (is (not-any? excluded? filtered)))
      (testing "everything else is retained verbatim"
        (is (= (into #{} (remove excluded?) unfiltered) filtered))))))
