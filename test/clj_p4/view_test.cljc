(ns clj-p4.view-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.view :as view]))

(deftest glob->tokens-test
  (is (= [[:literal "src/"] :ellipsis]
         (view/glob->tokens "src/...")))

  (is (= [[:literal "lib/"] :star [:literal ".cpp"]]
         (view/glob->tokens "lib/*.cpp")))

  (is (= [[:literal "x/"] [:percent 1] [:literal "/"] [:percent 2]]
         (view/glob->tokens "x/%1/%2"))))

(deftest glob->re-test
  (testing "ellipsis matches anything"
    (let [re (view/glob->re "src/...")]
      (is (= ["src/foo/bar.cpp" "foo/bar.cpp"]
             (re-matches re "src/foo/bar.cpp")))))

  (testing "star matches non-slash"
    (let [re (view/glob->re "lib/*.cpp")]
      (is (re-matches re "lib/foo.cpp"))
      (is (not (re-matches re "lib/sub/foo.cpp"))))))

(deftest subst-tokens-test
  (let [tokens (view/glob->tokens "src/...")]
    (is (= "src/foo/bar.cpp" (view/subst-tokens tokens ["foo/bar.cpp"]))))

  (let [tokens (view/glob->tokens "x/%1/%2")]
    (is (= "x/a/b" (view/subst-tokens tokens ["a" "b"])))))

(deftest map-depot->local-share-test
  (let [chain [{:stream/name    "//stream/main"
                :stream/parent  nil
                :stream/options #{}
                :stream/paths   [[:share "src/..."]
                                 [:isolate "build/..."]
                                 [:exclude "src/secret/..."]]
                :stream/remapped []
                :stream/ignored  []}]
        view  (view/effective-view chain)]

    (is (= "src/foo/bar.cpp"
           (view/map-depot->local view "//stream/main/src/foo/bar.cpp")))

    (is (= "build/output.o"
           (view/map-depot->local view "//stream/main/build/output.o")))

    (is (= ::view/excluded
           (view/map-depot->local view "//stream/main/src/secret/key.txt")))

    (is (= ::view/no-match
           (view/map-depot->local view "//other/repo/foo.cpp")))))

(deftest map-depot->local-import-test
  (let [chain [{:stream/name    "//stream/main"
                :stream/options #{}
                :stream/paths   [[:share "src/..."]
                                 [:import "lib/..." "//depot/lib/..."]]
                :stream/remapped []
                :stream/ignored  []}]
        view  (view/effective-view chain)]

    (is (= "lib/util.h"
           (view/map-depot->local view "//depot/lib/util.h")))

    (is (= "src/main.cpp"
           (view/map-depot->local view "//stream/main/src/main.cpp")))))

(deftest map-depot->local-ignore-test
  (let [chain [{:stream/name    "//stream/main"
                :stream/options #{}
                :stream/paths   [[:share "src/..."]]
                :stream/remapped []
                :stream/ignored  ["src/...bak"]}]
        view  (view/effective-view chain)]
    (is (= "src/foo.cpp"
           (view/map-depot->local view "//stream/main/src/foo.cpp")))
    (is (= ::view/ignored
           (view/map-depot->local view "//stream/main/src/foo.bak")))))

(deftest merge-streams-test
  (testing "child overrides parent on same view-path"
    (let [parent {:stream/name    "//stream/parent"
                  :stream/options #{}
                  :stream/paths   [[:share "src/..."]
                                   [:share "docs/..."]]
                  :stream/remapped []
                  :stream/ignored  []}
          child  {:stream/name    "//stream/child"
                  :stream/options #{}
                  :stream/paths   [[:isolate "src/..."]
                                   [:exclude "src/test/..."]]
                  :stream/remapped []
                  :stream/ignored  []}
          merged (view/merge-streams parent child)]

      (is (= [[:share   "docs/..."]
              [:isolate "src/..."]
              [:exclude "src/test/..."]]
             (:stream/paths merged)))))

  (testing ":no-fromparent option discards parent paths"
    (let [parent {:stream/name "//stream/p" :stream/options #{}
                  :stream/paths [[:share "p/..."]]
                  :stream/remapped [] :stream/ignored []}
          child  {:stream/name "//stream/c" :stream/options #{:no-fromparent}
                  :stream/paths [[:share "c/..."]]
                  :stream/remapped [] :stream/ignored []}
          merged (view/merge-streams parent child)]
      (is (= [[:share "c/..."]] (:stream/paths merged))))))

(deftest map-depot->local-parent-chain-test
  (let [parent {:stream/name    "//stream/parent"
                :stream/options #{}
                :stream/paths   [[:share "src/..."]
                                 [:share "docs/..."]]
                :stream/remapped []
                :stream/ignored  []}
        child  {:stream/name    "//stream/child"
                :stream/options #{}
                :stream/paths   [[:exclude "src/private/..."]]
                :stream/remapped []
                :stream/ignored  []}
        view   (view/effective-view [parent child])]

    (is (= "src/main.cpp"
           (view/map-depot->local view "//stream/child/src/main.cpp")))

    (is (= ::view/excluded
           (view/map-depot->local view "//stream/child/src/private/secret.cpp")))))

(deftest map-depot->local-remap-test
  (let [chain [{:stream/name    "//stream/main"
                :stream/options #{}
                :stream/paths   [[:share "src/..."]]
                :stream/remapped [["src/old/..." "src/new/..."]]
                :stream/ignored  []}]
        view  (view/effective-view chain)]
    (is (= "src/new/foo.cpp"
           (view/map-depot->local view "//stream/main/src/old/foo.cpp")))
    (is (= "src/main.cpp"
           (view/map-depot->local view "//stream/main/src/main.cpp")))))
