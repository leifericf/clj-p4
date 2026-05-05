(ns clj-p4.parse.semantic-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.parse.semantic :as ps]))

(deftest parse-stream-spec-test
  (let [record {"Stream"     "//stream/main"
                "Parent"     "//stream/mainline"
                "Type"       "development"
                "Paths0"     "share src/..."
                "Paths1"     "isolate build/..."
                "Paths2"     "import //depot/lib/... lib/..."
                "Paths3"     "exclude tmp/..."
                "Remapped0"  "src/old/... src/new/..."
                "Ignored0"   "*.bak"
                "Ignored1"   "tmp/"
                "Options"    "ownersubmit unlocked no-toparent no-fromparent"
                "Update"     "2026/04/01 12:00:00"}
        spec   (ps/parse-stream-spec record)]
    (is (= "//stream/main"      (:stream/name spec)))
    (is (= "//stream/mainline"  (:stream/parent spec)))
    (is (= :development         (:stream/type spec)))
    (is (= [[:share   "src/..."]
            [:isolate "build/..."]
            [:import  "//depot/lib/..." "lib/..."]
            [:exclude "tmp/..."]]
           (:stream/paths spec)))
    (is (= [["src/old/..." "src/new/..."]] (:stream/remapped spec)))
    (is (= ["*.bak" "tmp/"]                (:stream/ignored spec)))
    (is (= #{:ownersubmit :unlocked :no-toparent :no-fromparent}
           (:stream/options spec)))
    (is (= "2026/04/01 12:00:00" (:stream/updated spec)))))

(deftest parse-stream-spec-recognises-all-stream-types-test
  (testing "every stream type p4d emits maps to a known keyword"
    (doseq [[type-str expected-kw] [["mainline"    :mainline]
                                    ["development" :development]
                                    ["release"     :release]
                                    ["virtual"     :virtual]
                                    ["task"        :task]
                                    ["sparsedev"   :sparsedev]
                                    ["sparserel"   :sparserel]]]
      (is (= expected-kw
             (:stream/type
              (ps/parse-stream-spec
               {"Stream"  "//stream/x"
                "Type"    type-str
                "Options" ""})))
          (str "type=" type-str " should map to " expected-kw)))))

(deftest parse-stream-spec-empty-options-test
  (let [spec (ps/parse-stream-spec {"Stream"  "//stream/x"
                                    "Type"    "mainline"
                                    "Options" ""})]
    (is (= #{} (:stream/options spec)))
    (is (= [] (:stream/paths spec)))
    (is (= [] (:stream/remapped spec)))))

(deftest parse-file-type-test
  (testing "base types"
    (is (= :text   (:rev/type (ps/parse-file-type "text"))))
    (is (= :binary (:rev/type (ps/parse-file-type "binary"))))
    (is (= :symlink (:rev/type (ps/parse-file-type "symlink")))))

  (testing "modifiers"
    (let [t (ps/parse-file-type "text+kx")]
      (is (= :text (:rev/type t)))
      (is (= #{:k :x} (:rev/flags t)))
      (is (= #{:k}    (:rev/keyword-flags t)))))

  (testing "ko supersedes k"
    (let [t (ps/parse-file-type "text+ko")]
      (is (= #{:ko} (:rev/keyword-flags t)))
      (is (= #{:ko} (:rev/flags t)))))

  (testing "Sn modifier"
    (let [t (ps/parse-file-type "binary+S5")]
      (is (contains? (:rev/flags t) :S5)))))

(deftest parse-describe-test
  (let [record {"change"     "94312"
                "user"       "bob"
                "client"     "bob_ws"
                "time"       "1701234567"
                "desc"       "Fix shadow bias."
                "status"     "submitted"
                "stream"     "//stream/main"
                "depotFile0" "//depot/main/src/foo.cpp"
                "rev0"       "17"
                "action0"    "edit"
                "type0"      "text"
                "digest0"    "ABC"
                "fileSize0"  "2348"
                "depotFile1" "//depot/main/src/bar.bin"
                "rev1"       "1"
                "action1"    "add"
                "type1"      "binary+x"
                "fileSize1"  "12"}
        cl     (ps/parse-describe record)]
    (is (= 94312                  (:p4/change cl)))
    (is (= "bob"                  (:p4/user cl)))
    (is (= :submitted             (:p4/status cl)))
    (is (= "//stream/main"        (:p4/stream cl)))
    (is (= 1701234567000          (:p4/time cl)))
    (is (= 2 (count (:p4/files cl))))
    (let [[f0 f1] (:p4/files cl)]
      (is (= "//depot/main/src/foo.cpp" (:rev/depot f0)))
      (is (= 17    (:rev/rev f0)))
      (is (= :edit (:rev/action f0)))
      (is (= :text (:rev/type f0)))
      (is (= "ABC" (:rev/digest f0)))
      (is (= 2348  (:rev/size f0)))

      (is (= :add    (:rev/action f1)))
      (is (= :binary (:rev/type f1)))
      (is (= #{:x}   (:rev/flags f1))))))

(deftest parse-info-test
  (let [info (ps/parse-info {"serverVersion" "P4D/LINUX26X86_64/2024.1/2596294 (2024/04/30)"
                             "serverAddress" "perforce.example.com:1666"
                             "caseHandling"  "sensitive"
                             "unicode"       "enabled"})]
    (is (= 2024  (:p4/server-version-major info)))
    (is (= 1     (:p4/server-version-minor info)))
    (is (= :sensitive (:p4/case-handling info)))
    (is (true? (:p4/unicode? info)))))

(deftest parse-files-list-test
  (let [files (ps/parse-files-list
               [{"depotFile" "//depot/foo" "rev" "3" "type" "text" "action" "edit"}
                {"depotFile" "//depot/bar" "rev" "1" "type" "binary+ko" "action" "add"}])]
    (is (= 2 (count files)))
    (is (= "//depot/foo" (:rev/depot (first files))))
    (is (= 3             (:rev/rev   (first files))))
    (is (= #{:ko}        (:rev/keyword-flags (second files))))))

(deftest unknown-action-decodes-to-nil-test
  (testing "wire actions outside the documented set decode to nil"
    (doseq [unknown ["archive" "modify" "lock" "weirdo" ""]]
      (let [[f] (ps/parse-files-list
                 [{"depotFile" "//d/x" "action" unknown "type" "text"}])]
        (is (nil? (:rev/action f))
            (str "unknown wire action " (pr-str unknown)
                 " should surface as nil, not " (pr-str (:rev/action f)))))))
  (testing "every documented wire action still decodes to its keyword"
    (doseq [[wire kw] [["add"         :add]
                       ["edit"        :edit]
                       ["delete"      :delete]
                       ["branch"      :branch]
                       ["integrate"   :integrate]
                       ["purge"       :purge]
                       ["move/add"    :move/add]
                       ["move/delete" :move/delete]]]
      (let [[f] (ps/parse-files-list
                 [{"depotFile" "//d/x" "action" wire "type" "text"}])]
        (is (= kw (:rev/action f)))))))

(deftest unparseable-numeric-fields-decode-to-nil-test
  (testing "non-digit `change` string"
    (let [cl (ps/parse-changelist {"change" "abc" "user" "x"
                                   "client" nil  "desc"   "y"
                                   "status" "submitted"})]
      (is (nil? (:p4/change cl))
          "malformed wire numeric must surface as nil, not the raw string")))

  (testing "non-digit `time` string"
    (let [cl (ps/parse-changelist {"change" "100" "user" "x"
                                   "client" nil  "desc"   "y"
                                   "status" "submitted"
                                   "time"   "garbage"})]
      (is (nil? (:p4/time cl)))))

  (testing "non-digit `rev` and `fileSize` in flat files-list records"
    (let [[f] (ps/parse-files-list
               [{"depotFile" "//d/x" "rev" "abc" "fileSize" "xyz"
                 "type" "text" "action" "edit"}])]
      (is (nil? (:rev/rev  f)))
      (is (nil? (:rev/size f)))))

  (testing "non-digit indexed `rev<n>` / `fileSize<n>` in describe records"
    (let [cl (ps/parse-describe
              {"change"     "100"
               "user"       "u"
               "client"     "c"
               "desc"       "d"
               "status"     "submitted"
               "depotFile0" "//d/x"
               "rev0"       "abc"
               "fileSize0"  "xyz"
               "type0"      "text"
               "action0"    "edit"})
          [f] (:p4/files cl)]
      (is (nil? (:rev/rev  f)))
      (is (nil? (:rev/size f))))))
