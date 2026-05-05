(ns clj-p4.integration.t9835-metadata-encoding-test
  "Mirror of git-p4's `t/t9835-git-p4-metadata-encoding.sh` and
   `t/t9836-git-p4-metadata-encoding-python3.sh`: when the source p4d
   is NOT in unicode mode, changelist descriptions and usernames may
   carry non-UTF-8 bytes (CP-1252, Latin-1, mixed). The importer must
   support three decoding strategies — `:strict`, `:fallback`, and
   `:passthrough` — selectable per-clone.

   The Python 2 / Python 3 split in upstream is irrelevant on the JVM,
   so a single namespace covers both ports."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-p4.api :as api]
            [clj-p4.integration.fixture :as fix]
            [clj-p4.integration.git-assert :as ga]
            [clj-p4.io.subprocess :as proc]))

(use-fixtures :once
  (fn [f] (when (fix/integration-enabled?) (fix/with-p4d-mixed f))))

(defn- subject-line
  "Last line of `git log -1 --format=%s <sha>`."
  [target sha]
  (let [{:keys [stdout-bytes]}
        (proc/run-checked! ["git" "-C" target "log" "-1" "--format=%s" sha])]
    (str/trim-newline (String. ^bytes stdout-bytes "UTF-8"))))

(defn- find-non-ascii-subject [target ref]
  ;; The fixture submits "ascii baseline" (CL 1) plus two bad-byte CLs.
  ;; Find the most recent non-baseline commit's subject.
  (let [{:keys [stdout-bytes]}
        (proc/run-checked! ["git" "-C" target "log" "--format=%H %s" ref])
        lines (->> (String. ^bytes stdout-bytes "UTF-8")
                   str/split-lines (remove str/blank?))]
    (some (fn [line]
            (let [[sha & rest] (str/split line #" " 2)
                  subj (first rest)]
              (when (and subj (not (str/includes? subj "ascii baseline")))
                {:sha sha :subject subj})))
          lines)))

(deftest ^:integration t9835-strict-rejects-non-utf8-metadata
  (let [conn   (fix/mixed-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9835-strict") "strict.git"))]
    (try
      (api/clone! {:conn conn :source "//depot/..." :target target})
      (is false "strict decoder must throw on bad metadata bytes")
      (catch clojure.lang.ExceptionInfo e
        (is (= :metadata-decode-failed
               (or (:clj-p4/error (ex-data e))
                   ;; The throw happens deep in marshal during a p4 -G read;
                   ;; clone! may wrap it. Walk causes if needed.
                   (some-> (ex-cause e) ex-data :clj-p4/error)))
            (str "expected :metadata-decode-failed, got: "
                 (pr-str (ex-data e))))))))

(deftest ^:integration t9835-fallback-cp1252-recovers-descriptions
  (let [conn   (fix/mixed-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9835-fb") "fb.git"))]
    (api/clone! {:conn conn :source "//depot/..." :target target
                 :metadata-decoding-strategy :fallback
                 :metadata-fallback-encoding "CP1252"})
    (let [{:keys [stdout-bytes]}
          (proc/run-checked!
           ["git" "-C" target "log" "--format=%s" "refs/heads/main"])
          all (String. ^bytes stdout-bytes "UTF-8")]
      (testing "CP-1252 right-single-quote `’` decoded correctly"
        (is (str/includes? all "it’s")))
      (testing "Latin-1 `ñ` is mojibake under cp1252 (0xf1 → ñ in cp1252 too)"
        ;; CP-1252 and Latin-1 agree on 0xa0–0xff, so `señor` decodes
        ;; cleanly with either fallback.
        (is (str/includes? all "señor"))))))

(deftest ^:integration t9835-passthrough-keeps-bytes-1to1
  (let [conn   (fix/mixed-conn-with-ticket)
        target (str (io/file (ga/tmp-dir "clj-p4-t9835-pt") "pt.git"))]
    (api/clone! {:conn conn :source "//depot/..." :target target
                 :metadata-decoding-strategy :passthrough})
    (let [{:keys [stdout-bytes]}
          (proc/run-checked!
           ["git" "-C" target "log" "--format=%s" "refs/heads/main"])
          ;; passthrough decodes bytes 1:1 via ISO-8859-1; git stores
          ;; the resulting string as UTF-8. Read raw bytes back to
          ;; verify each original byte is recoverable via UTF-8 decode
          ;; → ISO-8859-1 encode round-trip.
          out  (String. ^bytes stdout-bytes "UTF-8")]
      (testing "every original CL produces a commit message"
        (is (= 3 (count (->> (str/split-lines out)
                             (remove str/blank?))))))
      (testing "the CP-1252 byte 0x92 round-trips through git as U+0092"
        (is (some #(.contains ^String % (str (char 0x92)))
                  (str/split-lines out))))
      (testing "the Latin-1 byte 0xf1 round-trips as U+00f1"
        (is (some #(.contains ^String % (str (char 0xf1)))
                  (str/split-lines out)))))))
