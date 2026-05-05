(ns clj-p4.integration.t9811-label-import-test
  "Mirror of git-p4's `t/t9811-git-p4-label-import.sh`: when
   `:emit-labels? true` is passed to `clone!`, every Perforce label
   pinned to an imported changelist becomes a git tag. The tag's
   commit is whichever import commit corresponds to the label's
   `Revision: @<CL>`.

   The fixture seeds one label `t9811_release_1` at the head CL.
   Without `:emit-labels?`, clone produces no tags. With it, the
   label appears at the SHA of the head import commit."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]
            [clj-p4.io.subprocess :as proc]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d f))))

(defn- list-tags [target]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked! ["git" "-C" target "tag" "--list"])]
    (->> (String. ^bytes stdout-bytes "UTF-8")
         str/split-lines
         (remove str/blank?)
         set)))

(defn- tag-commit-sha [target tag]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked!
         ["git" "-C" target "rev-list" "-n" "1" (str "refs/tags/" tag)])]
    (str/trim (String. ^bytes stdout-bytes "UTF-8"))))

(deftest ^:integration t9811-default-clone-emits-no-tags
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9811-no") "no.git"))]
    (api/clone! {:conn conn :source "//stream/main" :target target})
    (is (empty? (list-tags target))
        "without :emit-labels? clone must not synthesise any git tags")))

(deftest ^:integration t9811-emit-labels-imports-p4-labels-as-git-tags
  (let [conn   (fix/admin-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9811-yes") "yes.git"))]
    (api/clone! {:conn conn :source "//stream/main" :target target
                 :emit-labels? true})
    (testing "the seeded label lands as a git tag"
      (is (contains? (list-tags target) "t9811_release_1")))
    (testing "the tag points at the import commit for the label's @CL"
      ;; The seed creates the label with `Revision: @<head>` at the
      ;; moment of seeding — that's the alice CL ("t9828: alice's
      ;; change"). Other tests in this fixture session may push more
      ;; commits onto refs/heads/main, so don't assume head == labelled.
      (let [labelled (ga/find-commit-sha target "refs/heads/main"
                                         "alice's change")
            tag-sha  (tag-commit-sha target "t9811_release_1")]
        (is (some? labelled))
        (is (= labelled tag-sha))))))
