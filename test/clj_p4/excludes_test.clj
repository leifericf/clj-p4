(ns clj-p4.excludes-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.excludes :as ex]))

(deftest exclude-patterns-test
  (testing "resource patterns plus extras"
    (is (= ["*.exe" "*.dll" "*.zip" "*.bak"]
           (ex/exclude-patterns
            {:resource {:executables ["*.exe" "*.dll"]
                        :archives    ["*.zip"]}
             :extra-excludes ["*.bak"]}))))

  (testing "no-default-excludes? drops resource"
    (is (= ["*.bak"]
           (ex/exclude-patterns
            {:resource {:executables ["*.exe"]}
             :no-default-excludes? true
             :extra-excludes ["*.bak"]}))))

  (testing "includes whitelist"
    (is (= ["*.dll" "*.bak"]
           (ex/exclude-patterns
            {:resource {:executables ["*.exe" "*.dll"]}
             :extra-excludes ["*.bak"]
             :includes ["*.exe"]}))))

  (testing "dedupe"
    (is (= ["*.exe"]
           (ex/exclude-patterns
            {:resource {:a ["*.exe"] :b ["*.exe"]}
             :extra-excludes ["*.exe"]})))))

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
    (let [out (ex/exclude-patterns {:categories #{:images}})]
      (is (some #{"*.png"} out))
      (is (some #{"*.psd"} out))
      (is (not (some #{"*.wav"} out)))
      (is (not (some #{"*.exe"} out)))))

  (testing ":categories :all unions every category"
    (let [out (ex/exclude-patterns {:categories :all})]
      (is (some #{"*.png"} out))     ; images
      (is (some #{"*.wav"} out))     ; audio
      (is (some #{"*.uasset"} out))  ; engine-assets
      (is (some #{"*.dll"} out))))   ; compiled

  (testing "text-form formats are deliberately absent from the curated list"
    (let [out (ex/exclude-patterns {:categories :all})]
      (is (not (some #{"*.svg"} out)))     ; XML
      (is (not (some #{"*.gltf"} out)))    ; JSON
      (is (not (some #{"*.dae"} out)))     ; COLLADA XML
      (is (not (some #{"*.obj"} out)))     ; text Wavefront
      (is (not (some #{"*.unity"} out)))   ; Unity YAML
      (is (not (some #{"*.prefab"} out))))) ; Unity YAML

  (testing ":categories with :includes narrows the union"
    (let [out (ex/exclude-patterns {:categories #{:images}
                                    :includes ["*.png"]})]
      (is (some #{"*.psd"} out))
      (is (not (some #{"*.png"} out)))))

  (testing ":categories with :extra-excludes appends after built-ins"
    (let [out (ex/exclude-patterns {:categories #{:images}
                                    :extra-excludes ["*.myfmt"]})]
      (is (some #{"*.png"} out))
      (is (some #{"*.myfmt"} out))))

  (testing "user-supplied :resource takes precedence over built-ins"
    (let [out (ex/exclude-patterns {:resource   {:custom ["*.weird"]}
                                    :categories :all})]
      (is (= ["*.weird"] out))))

  (testing ":categories + :extra-excludes (Noumenon-style: drop *.obj on top of :all)"
    (let [out (ex/exclude-patterns {:categories     :all
                                    :extra-excludes ["*.obj"]})]
      (is (some #{"*.png"} out))     ; built-in still present
      (is (some #{"*.obj"} out))     ; explicit add wins
      (is (not (some #{"*.svg"} out))))) ; still text — not in built-ins

  (testing ":categories + :includes whitelists specific patterns from the union"
    (let [out (ex/exclude-patterns {:categories :all
                                    :includes   ["*.psd"]})]
      (is (some #{"*.png"} out))
      (is (not (some #{"*.psd"} out))))))
