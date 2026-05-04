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

(deftest client-view->view-basic-test
  (testing "single include line maps depot path to local"
    (let [v (view/client-view->view ["//stream/main/src/... //virt-eph/src/..."]
                                    :stream-name "//stream/virt")]
      (is (= "//stream/virt" (:view/stream v)))
      (is (= "src/main.cpp"
             (view/map-depot->local v "//stream/main/src/main.cpp")))
      (is (= ::view/no-match
             (view/map-depot->local v "//stream/other/elsewhere.txt"))))))

(deftest client-view->view-exclude-test
  (testing "lines prefixed with - exclude matching paths"
    (let [v (view/client-view->view
             ["//stream/main/... //virt-eph/..."
              "-//stream/main/private/... //virt-eph/private/..."]
             :stream-name "//stream/virt")]
      (is (= "src/main.cpp"
             (view/map-depot->local v "//stream/main/src/main.cpp")))
      (is (= ::view/excluded
             (view/map-depot->local v "//stream/main/private/secret.txt"))))))

(deftest client-view->view-later-wins-test
  (testing "later view entries override earlier (P4 rule)"
    (let [v (view/client-view->view
             ["//stream/main/foo/... //virt-eph/foo/..."
              "//stream/main/foo/special/... //virt-eph/elsewhere/..."]
             :stream-name "//stream/virt")]
      (is (= "elsewhere/file.txt"
             (view/map-depot->local v "//stream/main/foo/special/file.txt"))))))

(deftest client-view->view-strips-client-prefix-test
  (testing "client name is stripped regardless of length / shape"
    (let [v (view/client-view->view
             ["//stream/main/src/... //a-very-long-client-name/src/..."]
             :stream-name "//stream/virt")]
      (is (= "src/x.cpp"
             (view/map-depot->local v "//stream/main/src/x.cpp"))))))

(deftest client-view->view-resolves-components-test
  (testing "Components: paths land in the client View block — auto-view sees them"
    ;; A real Components: spec on a stream causes p4d to add the
    ;; component's path to the client's auto-View. This input simulates
    ;; what `p4 client -i` would render: the host stream plus a shared
    ;; component dir.
    (let [v (view/client-view->view
             ["//stream/host/src/...      //eph/src/..."
              "//stream/shared/lib/...    //eph/lib/..."]
             :stream-name "//stream/host")]
      (is (= "src/main.cpp"
             (view/map-depot->local v "//stream/host/src/main.cpp")))
      (testing "the component path resolves through the auto-view"
        (is (= "lib/util.h"
               (view/map-depot->local v "//stream/shared/lib/util.h")))))))
