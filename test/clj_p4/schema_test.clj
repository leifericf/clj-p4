(ns clj-p4.schema-test
  "Pin the malli schemas in `clj-p4.schema`. Two-part: structural
   conformance against representative records, and decode-from-wire
   tests against representative string-shaped inputs."
  (:require [clj-p4.schema :as s]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]))

;; ---------------- Atomic schemas ----------------

(deftest action-enum-test
  (testing "every wire-format action token decodes to a valid keyword"
    (doseq [[wire kw] [["add"          :add]
                       ["edit"         :edit]
                       ["delete"       :delete]
                       ["branch"       :branch]
                       ["integrate"    :integrate]
                       ["purge"        :purge]
                       ["move/add"     :move/add]
                       ["move/delete"  :move/delete]]]
      (is (= kw (m/decode s/action wire s/record-transformer))
          (str wire " should decode to " kw))
      (is (m/validate s/action kw)
          (str kw " should validate against the action schema"))))
  (testing "rejects unknown actions"
    (is (not (m/validate s/action :unknown-action)))))

(deftest stream-type-enum-test
  (testing "every stream-type token decodes correctly"
    (doseq [[wire kw] [["mainline"    :mainline]
                       ["development" :development]
                       ["release"     :release]
                       ["virtual"     :virtual]
                       ["task"        :task]
                       ["sparsedev"   :sparsedev]
                       ["sparserel"   :sparserel]]]
      (is (= kw (m/decode s/stream-type wire s/record-transformer))))))

(deftest epoch-ms-decoder-test
  (testing "epoch-seconds string decodes to epoch-ms long (× 1000)"
    (is (= 1700000000000
           (m/decode s/epoch-ms "1700000000" s/record-transformer))))
  (testing "already-decoded numbers pass through unchanged"
    (is (= 42 (m/decode s/epoch-ms 42 s/record-transformer))))
  (testing "non-digit strings decode to nil so downstream null-safe code keeps working"
    (is (nil? (m/decode s/epoch-ms "garbage" s/record-transformer)))))

(deftest unicode-flag-decoder-test
  (testing "literal 'enabled' decodes to true"
    (is (true? (m/decode s/unicode-flag "enabled" s/record-transformer))))
  (testing "anything else decodes to false"
    (is (false? (m/decode s/unicode-flag "" s/record-transformer)))
    (is (false? (m/decode s/unicode-flag "disabled" s/record-transformer)))
    (is (false? (m/decode s/unicode-flag nil s/record-transformer))))
  (testing "already-decoded booleans pass through"
    (is (true? (m/decode s/unicode-flag true s/record-transformer)))
    (is (false? (m/decode s/unicode-flag false s/record-transformer)))))

(deftest depot-path-test
  (testing "valid depot paths"
    (doseq [p ["//depot/main/..."
               "//stream/main"
               "//d/f"]]
      (is (m/validate s/depot-path p) (str p " should validate"))))
  (testing "invalid depot paths"
    (doseq [p ["depot/foo" "/single" "//../escape" 42]]
      (is (not (m/validate s/depot-path p)) (str p " should not validate")))))

;; ---------------- Record schemas ----------------

(deftest changelist-record-conformance-test
  (testing "minimal record validates"
    (is (m/validate s/changelist-record
                    {:p4/change  12345
                     :p4/user    "alice"
                     :p4/client  nil
                     :p4/desc    "x"
                     :p4/status  :submitted})))
  (testing "full record with files validates"
    (is (m/validate s/changelist-record
                    {:p4/change  12345
                     :p4/user    "alice"
                     :p4/client  "ws"
                     :p4/desc    "fix"
                     :p4/status  :submitted
                     :p4/stream  "//s/main"
                     :p4/time    1700000000000
                     :p4/files   [{:rev/depot  "//d/f"
                                   :rev/action :edit
                                   :rev/rev    7}]})))
  (testing "rejects bad change number"
    (is (not (m/validate s/changelist-record
                         {:p4/change  -1
                          :p4/user    "alice"
                          :p4/client  nil
                          :p4/desc    "x"
                          :p4/status  :submitted})))))

(deftest file-rev-decode-test
  (testing "wire-format strings decode to typed FileRev"
    (is (= {:rev/depot      "//d/f"
            :rev/action     :move/add
            :rev/rev        42
            :rev/size       1024
            :rev/digest     "abc"
            :rev/moved-file "//d/g"}
           (m/decode s/file-rev
                     {:rev/depot      "//d/f"
                      :rev/action     "move/add"
                      :rev/rev        "42"
                      :rev/size       "1024"
                      :rev/digest     "abc"
                      :rev/moved-file "//d/g"}
                     s/record-transformer)))))

(deftest stream-spec-conformance-test
  (testing "representative stream spec validates"
    (is (m/validate s/stream-spec
                    {:stream/name     "//s/main"
                     :stream/parent   nil
                     :stream/type     :mainline
                     :stream/paths    [[:share "src/..."]
                                       [:import "//d/lib/..." "lib/..."]]
                     :stream/remapped [["a" "b"]]
                     :stream/ignored  ["*.log"]
                     :stream/options  #{:noallwrite :locked}}))))

(deftest server-info-decode-test
  (testing "p4 info wire shape decodes"
    (is (= {:p4/server-version       "P4D/2024.2/..."
            :p4/server-uptime        "01:23:45"
            :p4/server-address       "1666"
            :p4/case-handling        :sensitive
            :p4/unicode?             true
            :p4/server-version-major 2024
            :p4/server-version-minor 2}
           (m/decode s/server-info
                     {:p4/server-version       "P4D/2024.2/..."
                      :p4/server-uptime        "01:23:45"
                      :p4/server-address       "1666"
                      :p4/case-handling        "sensitive"
                      :p4/unicode?             "enabled"
                      :p4/server-version-major "2024"
                      :p4/server-version-minor "2"}
                     s/record-transformer)))))

;; ---------------- Public API options ----------------

(deftest clone-options-test
  (testing "minimal valid options"
    (is (m/validate s/clone-options
                    {:conn   {:p4/port "localhost:1666"}
                     :target "/tmp/r"
                     :source "//s/main"})))
  (testing "extra unrecognised keys allowed (open map)"
    (is (m/validate s/clone-options
                    {:conn        {:p4/port "localhost:1666"}
                     :target      "/tmp/r"
                     :source      "//s/main"
                     :totally-new "ok"})))
  (testing "missing :conn — humanized error names the field"
    (let [bad {:target "/tmp/r" :source "//s/main"}]
      (is (not (m/validate s/clone-options bad)))
      (is (contains? (me/humanize (m/explain s/clone-options bad))
                     :conn))))
  (testing ":fetch-parallelism must be positive"
    (is (not (m/validate s/clone-options
                         {:conn   {:p4/port "localhost:1666"}
                          :target "/tmp/r"
                          :source "//s/main"
                          :fetch-parallelism 0})))))

(deftest sync-options-test
  (testing ":since accepts a positive change number or nil"
    (is (m/validate s/sync-options
                    {:conn   {:p4/port "localhost:1666"}
                     :target "/tmp/r"
                     :source "//s/main"
                     :since  100}))
    (is (m/validate s/sync-options
                    {:conn   {:p4/port "localhost:1666"}
                     :target "/tmp/r"
                     :source "//s/main"
                     :since  nil}))))

(deftest connection-spec-test
  (testing "minimal: just :p4/port"
    (is (m/validate s/connection-spec {:p4/port "localhost:1666"})))
  (testing "rejects missing :p4/port"
    (is (not (m/validate s/connection-spec {:p4/user "alice"}))))
  (testing "accepts retry/timeout knobs"
    (is (m/validate s/connection-spec
                    {:p4/port             "localhost:1666"
                     :p4/timeout-ms       30000
                     :p4/retries          3
                     :p4/retry-backoff-ms 250}))))
