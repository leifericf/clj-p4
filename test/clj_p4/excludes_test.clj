(ns clj-p4.excludes-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.excludes :as ex]))

(deftest exclude-patterns-test
  (testing "resource patterns plus extras"
    (is (= {:excludes ["*.exe" "*.dll" "*.zip" "*.bak"]
            :includes []}
           (ex/exclude-patterns
            {:resource {:executables ["*.exe" "*.dll"]
                        :archives    ["*.zip"]}
             :excludes ["*.bak"]}))))

  (testing "no-default-excludes? drops resource"
    (is (= {:excludes ["*.bak"]
            :includes []}
           (ex/exclude-patterns
            {:resource {:executables ["*.exe"]}
             :no-default-excludes? true
             :excludes ["*.bak"]}))))

  (testing ":includes is preserved orthogonally — set-difference at match time"
    (is (= {:excludes ["*.exe" "*.dll" "*.bak"]
            :includes ["*.exe"]}
           (ex/exclude-patterns
            {:resource {:executables ["*.exe" "*.dll"]}
             :excludes ["*.bak"]
             :includes ["*.exe"]}))))

  (testing "path-level carve-out: Content/keep/ stays in :includes"
    (is (= {:excludes ["Content/"]
            :includes ["Content/keep/"]}
           (ex/exclude-patterns
            {:no-default-excludes? true
             :excludes ["Content/"]
             :includes ["Content/keep/"]}))))

  (testing "dedupe per list"
    (is (= {:excludes ["*.exe"] :includes []}
           (ex/exclude-patterns
            {:resource {:a ["*.exe"] :b ["*.exe"]}
             :excludes ["*.exe"]})))
    (is (= {:excludes [] :includes ["*.psd"]}
           (ex/exclude-patterns
            {:no-default-excludes? true
             :includes ["*.psd" "*.psd"]})))))

(deftest pattern->re-test
  (testing "*.ext at any depth"
    (let [re (ex/pattern->re "*.exe")]
      (is (re-find re "foo.exe"))
      (is (re-find re "src/bin/foo.exe"))
      (is (not (re-find re "foo.exec")))))

  (testing "/anchored only at root"
    (let [re (ex/pattern->re "/build")]
      (is (re-find re "build"))
      (is (not (re-find re "src/build")))))

  (testing "trailing slash directory match"
    (let [re (ex/pattern->re "node_modules/")]
      (is (re-find re "node_modules"))
      (is (re-find re "node_modules/foo/bar.js"))
      (is (re-find re "src/node_modules/foo.js"))
      (is (not (re-find re "src/node_modules.txt")))))

  (testing "P4 ellipsis"
    (let [re (ex/pattern->re "build/...")]
      (is (re-find re "build/x/y.o")))))

(deftest pattern->re-rejects-degenerate-slash-test
  (testing "the pattern \"/\" raises ex-info, not a JVM range exception"
    (let [e (try (ex/pattern->re "/") :no-throw
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= :invalid-pattern (:clj-p4/error (ex-data e))))
      (is (= "/" (:pattern (ex-data e)))))))

(deftest matches-any?-test
  (let [compiled (ex/compile-patterns ["*.exe" "*.dll" "build/..."])]
    (is (ex/matches-any? compiled "foo.exe"))
    (is (ex/matches-any? compiled "src/lib.dll"))
    (is (ex/matches-any? compiled "build/output/foo.o"))
    (is (not (ex/matches-any? compiled "src/foo.cpp")))))

(deftest matching-pattern-test
  (let [compiled (ex/compile-patterns ["*.exe" "*.dll"])]
    (is (= "*.exe" (ex/matching-pattern compiled "foo.exe")))
    (is (= "*.dll" (ex/matching-pattern compiled "lib/util.dll")))
    (is (nil? (ex/matching-pattern compiled "foo.cpp")))))

(deftest binary-rev?-test
  (testing "Perforce types P4 classifies as binary"
    (is (ex/binary-rev? {:rev/type :binary}))
    (is (ex/binary-rev? {:rev/type :apple}))
    (is (ex/binary-rev? {:rev/type :resource})))

  (testing "Perforce types treated as text-like — kept"
    (is (not (ex/binary-rev? {:rev/type :text})))
    (is (not (ex/binary-rev? {:rev/type :utf8})))
    (is (not (ex/binary-rev? {:rev/type :utf16})))
    (is (not (ex/binary-rev? {:rev/type :unicode})))
    (is (not (ex/binary-rev? {:rev/type :symlink}))))

  (testing "missing :rev/type does not crash and is not binary"
    (is (not (ex/binary-rev? {}))))

  (testing "binary-rev-types is the canonical set"
    (is (= #{:binary :apple :resource} ex/binary-rev-types))))

(deftest categories-builtin-test
  (testing "binary-categories returns every key in the built-in resource"
    (let [cats (ex/binary-categories)]
      (is (set? cats))
      (is (contains? cats :images))
      (is (contains? cats :audio))
      (is (contains? cats :engine-assets))))

  (testing ":categories with a subset selects from the built-in resource"
    (let [{:keys [excludes]} (ex/exclude-patterns {:categories #{:images}})]
      (is (some #{"*.png"} excludes))
      (is (some #{"*.psd"} excludes))
      (is (not (some #{"*.wav"} excludes)))
      (is (not (some #{"*.exe"} excludes)))))

  (testing ":categories :all unions every category"
    (let [{:keys [excludes]} (ex/exclude-patterns {:categories :all})]
      (is (some #{"*.png"} excludes))     ; images
      (is (some #{"*.wav"} excludes))     ; audio
      (is (some #{"*.uasset"} excludes))  ; engine-assets
      (is (some #{"*.dll"} excludes))))   ; compiled

  (testing "text-form formats are deliberately absent from the curated list"
    (let [{:keys [excludes]} (ex/exclude-patterns {:categories :all})]
      (is (not (some #{"*.svg"} excludes)))
      (is (not (some #{"*.gltf"} excludes)))
      (is (not (some #{"*.dae"} excludes)))
      (is (not (some #{"*.obj"} excludes)))
      (is (not (some #{"*.unity"} excludes)))
      (is (not (some #{"*.prefab"} excludes)))))

  (testing ":categories with :includes leaves both lists populated"
    (let [{:keys [excludes includes]}
          (ex/exclude-patterns {:categories #{:images}
                                :includes ["*.png"]})]
      (is (some #{"*.psd"} excludes))
      (is (some #{"*.png"} excludes))
      (is (= ["*.png"] includes))))

  (testing ":categories with :excludes appends after built-ins"
    (let [{:keys [excludes]}
          (ex/exclude-patterns {:categories #{:images}
                                :excludes ["*.myfmt"]})]
      (is (some #{"*.png"} excludes))
      (is (some #{"*.myfmt"} excludes))))

  (testing "user-supplied :resource takes precedence over built-ins"
    (let [{:keys [excludes]}
          (ex/exclude-patterns {:resource   {:custom ["*.weird"]}
                                :categories :all})]
      (is (= ["*.weird"] excludes))))

  (testing ":categories + :excludes (Noumenon-style: drop *.obj on top of :all)"
    (let [{:keys [excludes]}
          (ex/exclude-patterns {:categories :all
                                :excludes   ["*.obj"]})]
      (is (some #{"*.png"} excludes))
      (is (some #{"*.obj"} excludes))
      (is (not (some #{"*.svg"} excludes)))))

  (testing ":categories + :includes preserves both lists for set-difference"
    (let [{:keys [excludes includes]}
          (ex/exclude-patterns {:categories :all
                                :includes   ["*.psd"]})]
      (is (some #{"*.png"} excludes))
      (is (some #{"*.psd"} excludes))
      (is (= ["*.psd"] includes)))))
