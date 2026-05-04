(ns clj-p4.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-p4.api :as api]
            [clj-p4.shell.git :as git]
            [clj-p4.shell.p4 :as p4]
            [clj-p4.shell.proc :as proc]))

(defn- tmp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-p4-api-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

(deftest repo-state-finds-trailer-behind-non-trailer-head-test
  (let [d (tmp-dir)
        target (str d)]
    (try
      (git/init-bare! target)
      (let [h (git/fast-import-start target)]
        (git/blob! h 1 (.getBytes "a"))
        (git/emit-commit! h
                          {:ref "refs/heads/main" :mark 5
                           :committer {:name "u" :email "u@x"
                                       :time-ms 0 :tz "+0000"}
                           :message "first\n\n[git-p4: depot-paths = \"//stream/main/\": change = 5]"
                           :files [{:op :M :mode "100644" :mark 1 :path "f1"}]})
        (git/blob! h 2 (.getBytes "b"))
        (git/emit-commit! h
                          {:ref "refs/heads/main" :mark 7
                           :committer {:name "u" :email "u@x"
                                       :time-ms 1000 :tz "+0000"}
                           :message "second\n\n[git-p4: depot-paths = \"//stream/main/\": change = 7]"
                           :from ":5"
                           :files [{:op :M :mode "100644" :mark 2 :path "f2"}]})
        (git/blob! h 3 (.getBytes "c"))
        (git/emit-commit! h
                          {:ref "refs/heads/main" :mark 99
                           :committer {:name "u" :email "u@x"
                                       :time-ms 2000 :tz "+0000"}
                           :message "manual rebase, no trailer"
                           :from ":7"
                           :files [{:op :M :mode "100644" :mark 3 :path "f3"}]})
        (git/fast-import-close! h))
      (is (= 7 (:last-change (api/repo-state target))))
      (finally (rm-rf d)))))

(deftest change-from-trailer-test
  (testing "matches a real git-p4 trailer"
    (is (= 7 (#'api/change-from-trailer
              "first\n\n[git-p4: depot-paths = \"//stream/main/\": change = 7]"))))

  (testing "ignores prose with `change = N` outside a trailer"
    (is (nil? (#'api/change-from-trailer "Fixed change = 42 in config")))
    (is (nil? (#'api/change-from-trailer "tweak: change = 99 was wrong"))))

  (testing "trailer with extra surrounding lines"
    (is (= 13 (#'api/change-from-trailer
               "subject line\n\nbody body\n\n[git-p4: depot-paths = \"//stream/main/\": change = 13]\n"))))

  (testing "nil and empty input"
    (is (nil? (#'api/change-from-trailer nil)))
    (is (nil? (#'api/change-from-trailer "")))))

(def ^:private mainline
  {:stream/name "//stream/main"
   :stream/parent nil
   :stream/type :mainline
   :stream/options #{}
   :stream/paths   [[:share "src/..."]]
   :stream/remapped []
   :stream/ignored  []})

(def ^:private info-2024
  {:p4/server-version-major 2024
   :p4/server-version-minor 1
   :p4/server-version "P4D/.../2024.1/..."})

(defn- describe-fixture
  "Build a ChangelistRecord fixture for change `n` with `n` files
   under `//stream/main/src/`."
  [n]
  {:p4/change n
   :p4/user   "alice"
   :p4/time   (* n 1000)
   :p4/desc   (str "change " n)
   :p4/stream "//stream/main"
   :p4/files  [{:rev/depot  (str "//stream/main/src/file" n ".cpp")
                :rev/rev    1
                :rev/action :add
                :rev/type   :text
                :rev/flags  #{}
                :rev/keyword-flags #{}}]})

(defn- print-bytes-stub
  [_conn depot-rev out & _]
  (.write ^java.io.OutputStream out
          (.getBytes (str "content " depot-rev "\n") "UTF-8")))

(deftest clone!-refuses-non-empty-target-test
  (let [d (tmp-dir)
        target (str (io/file d "repo"))]
    (try
      (.mkdirs (io/file target))
      (spit (io/file target "user-data.txt") "important user content")
      (with-redefs [p4/info         (constantly info-2024)
                    p4/stream-chain (constantly [mainline])
                    p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                    p4/describe     (constantly (describe-fixture 100))
                    p4/print-bytes! print-bytes-stub]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"target is not empty"
             (api/clone! {:conn   {:p4/port "h:1666"}
                          :stream "//stream/main"
                          :target target}))))
      (testing "user file is untouched"
        (is (= "important user content"
               (slurp (io/file target "user-data.txt")))))
      (testing "no bare-repo metadata leaked into the target"
        (is (not (.isFile (io/file target "HEAD"))))
        (is (not (.isDirectory (io/file target "objects")))))
      (finally (rm-rf d)))))

(deftest clone!-allowed-on-empty-target-test
  (let [d (tmp-dir)
        target (str (io/file d "fresh-empty-dir"))]
    (try
      (.mkdirs (io/file target))
      (with-redefs [p4/info         (constantly info-2024)
                    p4/stream-chain (constantly [mainline])
                    p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                    p4/describe     (constantly (describe-fixture 100))
                    p4/print-bytes! print-bytes-stub]
        (let [result (api/clone! {:conn   {:p4/port "h:1666"}
                                  :stream "//stream/main"
                                  :target target})]
          (is (= 1 (:commits result)))))
      (finally (rm-rf d)))))

(deftest clone!-end-to-end-test
  (let [d (tmp-dir)]
    (try
      (with-redefs [p4/info         (constantly info-2024)
                    p4/stream-chain (constantly [mainline])
                    p4/changes      (fn [_ _ & _] [{:p4/change 100}
                                                   {:p4/change 101}
                                                   {:p4/change 102}])
                    p4/describe     (fn [_ n] (describe-fixture n))
                    p4/print-bytes! print-bytes-stub]
        (let [target (str (io/file d "repo"))
              progress (atom [])
              {:keys [commits last-change]}
              (api/clone! {:conn   {:p4/port "h:1666"}
                           :stream "//stream/main"
                           :target target
                           :progress-fn #(swap! progress conj %)})]
          (is (= 3 commits))
          (is (= 102 last-change))

          (testing "git log shows 3 commits"
            (is (= 3 (git/commit-count target "refs/heads/main"))))

          (testing "tree contains expected files"
            (let [{:keys [stdout-bytes]}
                  (proc/run-checked! ["git" "-C" target "ls-tree" "-r"
                                      "refs/heads/main"])
                  listing (String. ^bytes stdout-bytes "UTF-8")]
              (is (str/includes? listing "src/file100.cpp"))
              (is (str/includes? listing "src/file101.cpp"))
              (is (str/includes? listing "src/file102.cpp"))))

          (testing "git-p4 trailer in commit message"
            (let [{:keys [stdout-bytes]}
                  (proc/run-checked! ["git" "-C" target "log" "-1"
                                      "--pretty=%B" "refs/heads/main"])
                  msg (String. ^bytes stdout-bytes "UTF-8")]
              (is (str/includes? msg "git-p4:"))
              (is (str/includes? msg "change = 102"))))

          (testing "progress-fn called once per op"
            (is (= 4 (count @progress)))   ; 3 process-change + 1 final checkpoint
            (is (every? :op/kind @progress)))))
      (finally (rm-rf d)))))

(deftest virtual-stream-refused-test
  (let [d (tmp-dir)]
    (try
      (with-redefs [p4/info         (constantly info-2024)
                    p4/stream-chain (constantly
                                     [(assoc mainline :stream/type :virtual)])]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"virtual stream"
             (api/clone! {:conn   {:p4/port "h:1666"}
                          :stream "//stream/virt"
                          :target (str (io/file d "repo"))}))))
      (finally (rm-rf d)))))

(deftest excludes-applied-test
  (let [d  (tmp-dir)
        ex (clj-p4.exclude/compile-patterns ["*.bin"])
        cl-with-bin
        {:p4/change 100 :p4/user "x" :p4/time 0 :p4/desc "with-bin"
         :p4/stream "//stream/main"
         :p4/files [{:rev/depot "//stream/main/src/keep.cpp" :rev/rev 1
                     :rev/action :add :rev/type :text
                     :rev/flags #{} :rev/keyword-flags #{}}
                    {:rev/depot "//stream/main/src/drop.bin" :rev/rev 1
                     :rev/action :add :rev/type :binary
                     :rev/flags #{} :rev/keyword-flags #{}}]}]
    (try
      (with-redefs [p4/info         (constantly info-2024)
                    p4/stream-chain (constantly [mainline])
                    p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                    p4/describe     (constantly cl-with-bin)
                    p4/print-bytes! print-bytes-stub]
        (let [target (str (io/file d "repo"))]
          (api/clone! {:conn    {:p4/port "h:1666"}
                       :stream  "//stream/main"
                       :target  target
                       :exclude ex})
          (let [{:keys [stdout-bytes]}
                (proc/run-checked! ["git" "-C" target "ls-tree" "-r"
                                    "refs/heads/main"])
                listing (String. ^bytes stdout-bytes "UTF-8")]
            (is (str/includes? listing "keep.cpp"))
            (is (not (str/includes? listing "drop.bin"))))))
      (finally (rm-rf d)))))

(deftest delete-action-test
  (let [d (tmp-dir)
        cl1 {:p4/change 100 :p4/user "x" :p4/time 0 :p4/desc "add"
             :p4/stream "//stream/main"
             :p4/files [{:rev/depot "//stream/main/src/a.txt" :rev/rev 1
                         :rev/action :add :rev/type :text
                         :rev/flags #{} :rev/keyword-flags #{}}]}
        cl2 {:p4/change 101 :p4/user "x" :p4/time 1000 :p4/desc "del"
             :p4/stream "//stream/main"
             :p4/files [{:rev/depot "//stream/main/src/a.txt" :rev/rev 2
                         :rev/action :delete :rev/type :text
                         :rev/flags #{} :rev/keyword-flags #{}}]}]
    (try
      (with-redefs [p4/info         (constantly info-2024)
                    p4/stream-chain (constantly [mainline])
                    p4/changes      (fn [_ _ & _] [{:p4/change 100}
                                                   {:p4/change 101}])
                    p4/describe     (fn [_ n] (case n 100 cl1 101 cl2))
                    p4/print-bytes! print-bytes-stub]
        (let [target (str (io/file d "repo"))]
          (api/clone! {:conn   {:p4/port "h:1666"}
                       :stream "//stream/main"
                       :target target})
          (is (= 2 (git/commit-count target "refs/heads/main")))
          (let [{:keys [stdout-bytes]}
                (proc/run-checked! ["git" "-C" target "ls-tree" "-r"
                                    "refs/heads/main"])]
            (is (= "" (str/trim
                       (String. ^bytes stdout-bytes "UTF-8")))))))
      (finally (rm-rf d)))))

(deftest repo-state-test
  (let [d (tmp-dir)]
    (try
      (with-redefs [p4/info         (constantly info-2024)
                    p4/stream-chain (constantly [mainline])
                    p4/changes      (fn [_ _ & _] [{:p4/change 100}
                                                   {:p4/change 101}])
                    p4/describe     (fn [_ n] (describe-fixture n))
                    p4/print-bytes! print-bytes-stub]
        (let [target (str (io/file d "repo"))]
          (api/clone! {:conn {:p4/port "h:1666"}
                       :stream "//stream/main" :target target})
          (let [s (api/repo-state target)]
            (is (= 2 (:commit-count s)))
            (is (= 101 (:last-change s)))
            (is (string? (:head-sha s))))))
      (finally (rm-rf d)))))

(deftest stop-predicate-test
  (let [d (tmp-dir)]
    (try
      (with-redefs [p4/info         (constantly info-2024)
                    p4/stream-chain (constantly [mainline])
                    p4/changes      (fn [_ _ & _]
                                      (mapv (fn [n] {:p4/change n})
                                            (range 100 110)))
                    p4/describe     (fn [_ n] (describe-fixture n))
                    p4/print-bytes! print-bytes-stub]
        (let [target  (str (io/file d "repo"))
              counter (atom 0)]
          (api/clone! {:conn {:p4/port "h:1666"}
                       :stream "//stream/main" :target target
                       :stop?  (fn [] (>= @counter 3))
                       :progress-fn (fn [_] (swap! counter inc))})
          ;; Stop is checked BEFORE each op; expect ≤3 commits
          (is (<= (git/commit-count target "refs/heads/main") 3))))
      (finally (rm-rf d)))))
