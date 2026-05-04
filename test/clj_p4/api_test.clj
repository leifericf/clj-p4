(ns clj-p4.api-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-p4.api :as api]
            [clj-p4.shell.git :as git]
            [clj-p4.shell.p4 :as p4]
            [clj-p4.shell.proc :as proc]))

;; The 0.12.0 ephemeral-first dispatch tries `create-stream-client!`
;; before any path that used to be `clone-direct!`. Force that creation
;; to fail with `:proc-failed` so tests that exercise the pure-data
;; parent-chain path (i.e. don't redef `with-ephemeral-client`) fall
;; through to it. Tests that DO want to exercise the ephemeral lifecycle
;; (e.g. virtual-stream-clones-via-ephemeral-client-test) override
;; `with-ephemeral-client` itself, which short-circuits the failing
;; `create-stream-client!` stub.
(use-fixtures
  :each
  (fn force-create-failure [test-fn]
    (with-redefs [p4/create-stream-client!
                  (fn [& _]
                    (throw (ex-info "test stub: no client creation"
                                    {:clj-p4/error :proc-failed})))]
      (test-fn))))

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

(def ^:private virtual-leaf
  {:stream/name "//stream/virt"
   :stream/parent "//stream/main"
   :stream/type :virtual
   :stream/options #{}
   :stream/paths   [[:share "src/..."]]
   :stream/remapped []
   :stream/ignored  []})

(defn- describe-virtual-fixture
  "Same shape as describe-fixture but the depot path lives under
   `//stream/main/src/...` (the parent), since virtual streams have no
   storage of their own and `describe` returns parent depot paths."
  [n]
  (assoc (describe-fixture n)
         :p4/files [{:rev/depot  (str "//stream/main/src/file" n ".cpp")
                     :rev/rev    1
                     :rev/action :add
                     :rev/type   :text
                     :rev/flags  #{}
                     :rev/keyword-flags #{}}]))

(deftest virtual-stream-clones-via-ephemeral-client-test
  (let [d (tmp-dir)
        target (str (io/file d "repo"))
        eph-conn-arg (atom nil)
        cleanup-called? (atom false)
        view-lines ["//stream/main/src/... //virt-eph/src/..."]
        with-eph (fn [_conn _stream f]
                   (let [info {:client/name "virt-eph"
                               :client/root "/tmp/clj-p4-eph-virt"
                               :client/view-lines view-lines}
                         eph-conn {:p4/port "h:1666" :p4/client "virt-eph"}]
                     (try
                       (reset! eph-conn-arg eph-conn)
                       (f eph-conn info)
                       (finally
                         (reset! cleanup-called? true)))))]
    (try
      (with-redefs [p4/info                 (constantly info-2024)
                    p4/stream-chain         (constantly [mainline virtual-leaf])
                    p4/with-ephemeral-client with-eph
                    p4/changes              (fn [_ _ & _] [{:p4/change 100}
                                                           {:p4/change 101}])
                    p4/describe             (fn [_ n] (describe-virtual-fixture n))
                    p4/print-bytes!         print-bytes-stub]
        (let [result (api/clone! {:conn   {:p4/port "h:1666"}
                                  :stream "//stream/virt"
                                  :target target})]
          (is (= 2 (:commits result)))
          (is (= 101 (:last-change result)))

          (testing "ephemeral client connection threaded through"
            (is (= "virt-eph" (:p4/client @eph-conn-arg))))

          (testing "ephemeral client torn down"
            (is @cleanup-called?))

          (testing "files imported under view-mapped local path"
            (let [{:keys [stdout-bytes]}
                  (proc/run-checked! ["git" "-C" target "ls-tree" "-r"
                                      "refs/heads/main"])
                  listing (String. ^bytes stdout-bytes "UTF-8")]
              (is (str/includes? listing "src/file100.cpp"))
              (is (str/includes? listing "src/file101.cpp"))))

          (testing "git-p4 trailer references the user-visible stream"
            (let [{:keys [stdout-bytes]}
                  (proc/run-checked! ["git" "-C" target "log" "-1"
                                      "--pretty=%B" "refs/heads/main"])
                  msg (String. ^bytes stdout-bytes "UTF-8")]
              (is (str/includes? msg "//stream/virt"))
              (is (not (str/includes? msg "virt-eph")))))))
      (finally (rm-rf d)))))

(deftest classic-depot-routes-via-classic-client-test
  (testing "non-stream depot path triggers classic-client clone path"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          stream-chain-stub
          (fn [_ _ & _]
            (throw (ex-info "p4 stream -o failed: //depot/main is not a stream"
                            {:clj-p4/error :proc-failed
                             :stderr "//depot/main is not a stream"})))
          with-classic
          (fn [_conn _path f]
            (let [info {:client/name "classic-eph"
                        :client/view-lines
                        ["//depot/main/... //classic-eph/..."]}
                  eph-conn {:p4/port "h:1666" :p4/client "classic-eph"}]
              (f eph-conn info)))]
      (try
        (with-redefs [p4/info               (constantly info-2024)
                      p4/stream-chain       stream-chain-stub
                      p4/with-classic-client with-classic
                      p4/changes            (fn [_ _ & _] [{:p4/change 100}])
                      p4/describe           (fn [_ _]
                                              {:p4/change 100 :p4/user "x"
                                               :p4/time 0 :p4/desc "classic"
                                               :p4/files
                                               [{:rev/depot  "//depot/main/src/x.cpp"
                                                 :rev/rev    1
                                                 :rev/action :add
                                                 :rev/type   :text
                                                 :rev/flags  #{}
                                                 :rev/keyword-flags #{}}]})
                      p4/print-bytes!       print-bytes-stub]
          (let [result (api/clone! {:conn   {:p4/port "h:1666"}
                                    :stream "//depot/main"
                                    :target target})]
            (is (= 1 (:commits result)))
            (testing "trailer references the user-supplied depot path"
              (let [{:keys [stdout-bytes]}
                    (proc/run-checked! ["git" "-C" target "log" "-1"
                                        "--pretty=%B" "refs/heads/main"])
                    msg (String. ^bytes stdout-bytes "UTF-8")]
                (is (str/includes? msg "//depot/main"))
                (is (not (str/includes? msg "classic-eph")))))))
        (finally (rm-rf d))))))

(deftest virtual-stream-cleanup-on-error-test
  (testing "ephemeral client is torn down even if the import fails"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          cleanup-called? (atom false)
          with-eph (fn [_conn _stream f]
                     (let [info {:client/name "virt-eph"
                                 :client/view-lines
                                 ["//stream/main/src/... //virt-eph/src/..."]}
                           eph-conn {:p4/port "h:1666" :p4/client "virt-eph"}]
                       (try
                         (f eph-conn info)
                         (finally
                           (reset! cleanup-called? true)))))]
      (try
        (with-redefs [p4/info                 (constantly info-2024)
                      p4/stream-chain         (constantly [mainline virtual-leaf])
                      p4/with-ephemeral-client with-eph
                      p4/changes              (fn [_ _ & _]
                                                (throw (ex-info "boom" {})))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"boom"
               (api/clone! {:conn   {:p4/port "h:1666"}
                            :stream "//stream/virt"
                            :target target})))
          (is @cleanup-called?))
        (finally (rm-rf d))))))

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

(deftest move-pair-emits-single-rename-test
  (testing "move/delete + move/add pair collapse to a single R rename"
    (let [d (tmp-dir)
          ;; CL 100 adds src/old.txt; CL 101 moves it to src/new.txt
          cl1 {:p4/change 100 :p4/user "x" :p4/time 0 :p4/desc "add"
               :p4/stream "//stream/main"
               :p4/files [{:rev/depot "//stream/main/src/old.txt" :rev/rev 1
                           :rev/action :add :rev/type :text
                           :rev/flags #{} :rev/keyword-flags #{}}]}
          cl2 {:p4/change 101 :p4/user "x" :p4/time 1000 :p4/desc "rename"
               :p4/stream "//stream/main"
               :p4/files [{:rev/depot "//stream/main/src/old.txt" :rev/rev 2
                           :rev/action :move/delete :rev/type :text
                           :rev/flags #{} :rev/keyword-flags #{}
                           :rev/moved-file "//stream/main/src/new.txt"}
                          {:rev/depot "//stream/main/src/new.txt" :rev/rev 1
                           :rev/action :move/add :rev/type :text
                           :rev/flags #{} :rev/keyword-flags #{}
                           :rev/moved-file "//stream/main/src/old.txt"}]}]
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
            (testing "tree shows src/new.txt and not src/old.txt"
              (let [{:keys [stdout-bytes]}
                    (proc/run-checked! ["git" "-C" target "ls-tree" "-r"
                                        "refs/heads/main"])
                    listing (String. ^bytes stdout-bytes "UTF-8")]
                (is (str/includes? listing "src/new.txt"))
                (is (not (str/includes? listing "src/old.txt")))))
            (testing "the rename commit's content reflects the move/add revision"
              (let [{:keys [stdout-bytes]}
                    (proc/run-checked!
                     ["git" "-C" target "show"
                      "refs/heads/main:src/new.txt"])
                    content (String. ^bytes stdout-bytes "UTF-8")]
                (is (str/includes? content "src/new.txt@101"))))))
        (finally (rm-rf d))))))

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

(deftest fetch-parallelism-uses-pmap-test
  (testing "with :fetch-parallelism > 1 print-bytes! is invoked once per file"
    (let [d (tmp-dir)
          n-files 8
          cl {:p4/change 100 :p4/user "x" :p4/time 0 :p4/desc "many"
              :p4/stream "//stream/main"
              :p4/files (vec (for [i (range n-files)]
                               {:rev/depot (str "//stream/main/src/f" i ".txt")
                                :rev/rev 1 :rev/action :add :rev/type :text
                                :rev/flags #{} :rev/keyword-flags #{}}))}
          calls (atom 0)]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                      p4/describe     (constantly cl)
                      p4/print-bytes! (fn [_ rev out & _]
                                        (swap! calls inc)
                                        (.write ^java.io.OutputStream out
                                                (.getBytes (str "x " rev) "UTF-8")))]
          (let [target (str (io/file d "repo"))]
            (api/clone! {:conn   {:p4/port "h:1666"}
                         :stream "//stream/main"
                         :target target
                         :fetch-parallelism 4})
            (is (= n-files @calls))
            (is (= 1 (git/commit-count target "refs/heads/main")))))
        (finally (rm-rf d))))))

(deftest max-print-bytes-cap-test
  (testing "throws :max-print-bytes-exceeded when a file exceeds the cap"
    (let [d (tmp-dir)
          big-cl {:p4/change 100 :p4/user "x" :p4/time 0 :p4/desc "big"
                  :p4/stream "//stream/main"
                  :p4/files [{:rev/depot "//stream/main/src/big.txt"
                              :rev/rev 1 :rev/action :add :rev/type :text
                              :rev/flags #{} :rev/keyword-flags #{}}]}]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                      p4/describe     (constantly big-cl)
                      p4/print-bytes! (fn [_ _ out & _]
                                        (.write ^java.io.OutputStream out
                                                (byte-array 1024 (byte 65))))]
          (let [target (str (io/file d "repo"))]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"max-print-bytes"
                 (api/clone! {:conn   {:p4/port "h:1666"}
                              :stream "//stream/main"
                              :target target
                              :max-print-bytes 100})))))
        (finally (rm-rf d))))))

(deftest user-map-overrides-author-test
  (testing ":user-map sets the committer name + email"
    (let [d (tmp-dir)]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                      p4/describe     (constantly (describe-fixture 100))
                      p4/print-bytes! print-bytes-stub]
          (let [target (str (io/file d "repo"))]
            (api/clone! {:conn   {:p4/port "h:1666"}
                         :stream "//stream/main"
                         :target target
                         :user-map {"alice" {:name "Alice Engineer"
                                             :email "alice@example.test"}}})
            (let [{:keys [stdout-bytes]}
                  (proc/run-checked! ["git" "-C" target "log" "-1"
                                      "--pretty=%cn <%ce>" "refs/heads/main"])
                  out (String. ^bytes stdout-bytes "UTF-8")]
              (is (str/includes? out "Alice Engineer"))
              (is (str/includes? out "alice@example.test")))))
        (finally (rm-rf d))))))

(deftest user-map-falls-back-to-perforce-email-test
  (testing "users not in :user-map keep the default <user>@perforce email"
    (let [d (tmp-dir)]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                      p4/describe     (constantly (describe-fixture 100))
                      p4/print-bytes! print-bytes-stub]
          (let [target (str (io/file d "repo"))]
            (api/clone! {:conn   {:p4/port "h:1666"}
                         :stream "//stream/main"
                         :target target
                         :user-map {"bob" {:name "Bob"}}})
            (let [{:keys [stdout-bytes]}
                  (proc/run-checked! ["git" "-C" target "log" "-1"
                                      "--pretty=%ce" "refs/heads/main"])
                  email (String. ^bytes stdout-bytes "UTF-8")]
              (is (str/includes? email "alice@perforce")))))
        (finally (rm-rf d))))))

(deftest emit-labels-creates-annotated-tags-test
  (testing ":emit-labels? makes labels referencing imported CLs become tags"
    (let [d (tmp-dir)]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}
                                                     {:p4/change 101}])
                      p4/describe     (fn [_ n] (describe-fixture n))
                      p4/print-bytes! print-bytes-stub
                      p4/labels       (fn [_ & _]
                                        [{:label/name "v1"}
                                         {:label/name "skip-me"}])
                      p4/label-spec   (fn [_ name & _]
                                        (case name
                                          "v1"      {:label/name "v1"
                                                     :label/revision "@100"
                                                     :label/desc "first release"}
                                          "skip-me" {:label/name "skip-me"
                                                     :label/revision "@99999"
                                                     :label/desc "out of range"}))]
          (let [target (str (io/file d "repo"))]
            (api/clone! {:conn   {:p4/port "h:1666"}
                         :stream "//stream/main"
                         :target target
                         :emit-labels? true})
            (testing "v1 became an annotated tag"
              (let [{:keys [stdout-bytes]}
                    (proc/run-checked! ["git" "-C" target "tag" "-l"])
                    out (String. ^bytes stdout-bytes "UTF-8")]
                (is (str/includes? out "v1"))
                (is (not (str/includes? out "skip-me")))))
            (testing "tag points at the labeled changelist's commit"
              (let [{:keys [stdout-bytes]}
                    (proc/run-checked! ["git" "-C" target "rev-list" "-1" "v1"])
                    tag-sha (String. ^bytes stdout-bytes "UTF-8")
                    {:keys [stdout-bytes]}
                    (proc/run-checked! ["git" "-C" target "log" "--pretty=%H"
                                        "--reverse" "refs/heads/main"])
                    first-sha (-> ^bytes stdout-bytes
                                  (String. "UTF-8")
                                  str/trim
                                  str/split-lines
                                  first)]
                (is (= (str/trim tag-sha) first-sha))))))
        (finally (rm-rf d))))))

(deftest lookahead-prefetches-describes-test
  (testing ":lookahead N starts describe(M+1) before commit(M) finishes"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          events (atom [])
          ;; A 30 ms wait inside p4/describe is enough for the lookahead
          ;; future to start the next describe and be observed in `events`
          ;; before the current describe completes.
          delay-ms 30
          stub-describe (fn [_ n]
                          (swap! events conj [:describe-start n
                                              (System/nanoTime)])
                          (Thread/sleep delay-ms)
                          (swap! events conj [:describe-end n
                                              (System/nanoTime)])
                          (describe-fixture n))
          changes [{:p4/change 100} {:p4/change 101} {:p4/change 102}]]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] changes)
                      p4/describe     stub-describe
                      p4/print-bytes! print-bytes-stub]
          (api/clone! {:conn   {:p4/port "h:1666"}
                       :stream "//stream/main"
                       :target target
                       :lookahead 2}))
        (let [evs       @events
              starts    (filter #(= (first %) :describe-start) evs)
              ends      (filter #(= (first %) :describe-end) evs)
              t-start   (fn [n] (some (fn [[_ ch t]] (when (= ch n) t)) starts))
              t-end     (fn [n] (some (fn [[_ ch t]] (when (= ch n) t)) ends))]
          (testing "all three describes ran"
            (is (= 3 (count starts)))
            (is (= 3 (count ends))))
          (testing "describe(101) started before describe(100) finished"
            (is (< (t-start 101) (t-end 100))))
          (testing "describe(102) started before describe(100) finished"
            (is (< (t-start 102) (t-end 100)))))
        (finally (rm-rf d))))))

(deftest lookahead-zero-stays-sequential-test
  (testing "no :lookahead means describe runs strictly serially"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          events (atom [])
          stub-describe (fn [_ n]
                          (swap! events conj [:start n])
                          (Thread/sleep 10)
                          (swap! events conj [:end n])
                          (describe-fixture n))]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}
                                                     {:p4/change 101}])
                      p4/describe     stub-describe
                      p4/print-bytes! print-bytes-stub]
          (api/clone! {:conn   {:p4/port "h:1666"}
                       :stream "//stream/main"
                       :target target}))
        (is (= [[:start 100] [:end 100] [:start 101] [:end 101]]
               @events))
        (finally (rm-rf d))))))

(deftest integrate-as-merge-emits-2nd-parent-test
  (testing "majority-vote integrate source in imported set → merge parent"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          ;; Three changes:
          ;;   100 — add src/lib.cpp on //stream/main
          ;;   101 — add src/app.cpp on //stream/main
          ;;   200 — integrate src/lib.cpp + src/app.cpp into //stream/dev
          ;;          BOTH source from change 100 / change 101 respectively
          cl100 {:p4/change 100 :p4/user "x" :p4/time 0 :p4/desc "lib"
                 :p4/files [{:rev/depot "//stream/main/src/lib.cpp"
                             :rev/rev 1 :rev/action :add :rev/type :text
                             :rev/flags #{} :rev/keyword-flags #{}}]}
          cl101 {:p4/change 101 :p4/user "x" :p4/time 1000 :p4/desc "app"
                 :p4/files [{:rev/depot "//stream/main/src/app.cpp"
                             :rev/rev 1 :rev/action :add :rev/type :text
                             :rev/flags #{} :rev/keyword-flags #{}}]}
          cl200 {:p4/change 200 :p4/user "x" :p4/time 2000
                 :p4/desc "merge from main"
                 :p4/files [{:rev/depot "//stream/main/src/lib.cpp"
                             :rev/rev 1 :rev/action :integrate :rev/type :text
                             :rev/flags #{} :rev/keyword-flags #{}}
                            {:rev/depot "//stream/main/src/app.cpp"
                             :rev/rev 1 :rev/action :integrate :rev/type :text
                             :rev/flags #{} :rev/keyword-flags #{}}]}
          ;; integrated → fromFile + endFromRev; fstat → headChange.
          ;; Both files claim source change 101 → 101 wins by majority,
          ;; emitted as the merge parent.
          integrated-stub (fn [_ _change to-file]
                            [{:integ/from-file to-file
                              :integ/end-from-rev 1
                              :integ/how "merge from"
                              :integ/change 200}])
          fstat-stub (fn [_ rev]
                       (cond
                         (str/includes? rev "lib.cpp") {:fstat/head-change 101}
                         (str/includes? rev "app.cpp") {:fstat/head-change 101}
                         :else {:fstat/head-change nil}))]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}
                                                     {:p4/change 101}
                                                     {:p4/change 200}])
                      p4/describe     (fn [_ n] (case n
                                                  100 cl100
                                                  101 cl101
                                                  200 cl200))
                      p4/print-bytes! print-bytes-stub
                      p4/integrated   integrated-stub
                      p4/fstat        fstat-stub]
          (api/clone! {:conn   {:p4/port "h:1666"}
                       :stream "//stream/main"
                       :target target}))
        (testing "the integrate commit has 2 parents"
          (let [{:keys [stdout-bytes]}
                (proc/run-checked! ["git" "-C" target "log" "-1"
                                    "--pretty=%P" "refs/heads/main"])
                parents (str/split (str/trim
                                    (String. ^bytes stdout-bytes "UTF-8"))
                                   #"\s+")]
            (is (= 2 (count parents)))))
        (finally (rm-rf d))))))

(deftest integrate-without-imported-source-stays-linear-test
  (testing "integrate source not in imported set → no merge parent"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          cl100 {:p4/change 100 :p4/user "x" :p4/time 0 :p4/desc "first"
                 :p4/files [{:rev/depot "//stream/main/src/a.cpp"
                             :rev/rev 1 :rev/action :add :rev/type :text
                             :rev/flags #{} :rev/keyword-flags #{}}]}
          cl101 {:p4/change 101 :p4/user "x" :p4/time 1000
                 :p4/desc "integrate from elsewhere"
                 :p4/files [{:rev/depot "//stream/main/src/a.cpp"
                             :rev/rev 2 :rev/action :integrate :rev/type :text
                             :rev/flags #{} :rev/keyword-flags #{}}]}]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}
                                                     {:p4/change 101}])
                      p4/describe     (fn [_ n] (case n 100 cl100 101 cl101))
                      p4/print-bytes! print-bytes-stub
                      p4/integrated   (fn [_ _ to-file]
                                        [{:integ/from-file to-file
                                          :integ/end-from-rev 1
                                          :integ/how "merge from"
                                          :integ/change 101}])
                      ;; The fstat lookup says the source change is 999,
                      ;; which is not in `imported?`'s set.
                      p4/fstat        (fn [_ _] {:fstat/head-change 999})]
          (api/clone! {:conn   {:p4/port "h:1666"}
                       :stream "//stream/main"
                       :target target}))
        (testing "the integrate commit has 1 parent (linear)"
          (let [{:keys [stdout-bytes]}
                (proc/run-checked! ["git" "-C" target "log" "-1"
                                    "--pretty=%P" "refs/heads/main"])
                parents (str/split (str/trim
                                    (String. ^bytes stdout-bytes "UTF-8"))
                                   #"\s+")]
            (is (= 1 (count parents)))))
        (finally (rm-rf d))))))

(deftest no-merge-opt-out-suppresses-2nd-parent-test
  (testing ":no-merge? true skips the merge-source lookup entirely"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          cl100 {:p4/change 100 :p4/user "x" :p4/time 0 :p4/desc "first"
                 :p4/files [{:rev/depot "//stream/main/src/a.cpp"
                             :rev/rev 1 :rev/action :add :rev/type :text
                             :rev/flags #{} :rev/keyword-flags #{}}]}
          cl101 {:p4/change 101 :p4/user "x" :p4/time 1000
                 :p4/desc "integrate"
                 :p4/files [{:rev/depot "//stream/main/src/a.cpp"
                             :rev/rev 2 :rev/action :integrate :rev/type :text
                             :rev/flags #{} :rev/keyword-flags #{}}]}
          integ-called? (atom false)]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}
                                                     {:p4/change 101}])
                      p4/describe     (fn [_ n] (case n 100 cl100 101 cl101))
                      p4/print-bytes! print-bytes-stub
                      p4/integrated   (fn [_ _ _]
                                        (reset! integ-called? true) [])
                      p4/fstat        (fn [_ _] {:fstat/head-change 100})]
          (api/clone! {:conn   {:p4/port "h:1666"}
                       :stream "//stream/main"
                       :target target
                       :no-merge? true}))
        (testing "p4/integrated never called"
          (is (false? @integ-called?)))
        (testing "history is linear"
          (let [{:keys [stdout-bytes]}
                (proc/run-checked! ["git" "-C" target "log" "-1"
                                    "--pretty=%P" "refs/heads/main"])
                parents (str/split (str/trim
                                    (String. ^bytes stdout-bytes "UTF-8"))
                                   #"\s+")]
            (is (= 1 (count parents)))))
        (finally (rm-rf d))))))

(def ^:private dev-stream
  {:stream/name "//stream/dev"
   :stream/parent "//stream/main"
   :stream/type :development
   :stream/options #{}
   :stream/paths   [[:share "src/..."]]
   :stream/remapped []
   :stream/ignored  []})

(deftest multi-stream-clones-into-separate-refs-test
  (testing ":streams [a b] produces two refs in one bare repo"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          ;; Each stream returns a different parent chain so we know
          ;; which call is which. Same fixture body for simplicity.
          stream-chain-stub
          (fn [_ name & _]
            (case name
              "//stream/main" [mainline]
              "//stream/dev"  [mainline dev-stream]))
          ;; Distinct change ranges per stream so commits don't collide.
          changes-stub
          (fn [_ path & _]
            (cond
              (str/includes? path "main") [{:p4/change 100} {:p4/change 101}]
              (str/includes? path "dev")  [{:p4/change 200} {:p4/change 201}]))
          describe-stub
          (fn [_ n]
            (let [stream (if (< n 200) "//stream/main" "//stream/dev")]
              {:p4/change n :p4/user "x" :p4/time (* n 1000)
               :p4/desc (str "change " n)
               :p4/stream stream
               :p4/files [{:rev/depot (str stream "/src/file" n ".cpp")
                           :rev/rev 1 :rev/action :add :rev/type :text
                           :rev/flags #{} :rev/keyword-flags #{}}]}))]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain stream-chain-stub
                      p4/changes      changes-stub
                      p4/describe     describe-stub
                      p4/print-bytes! print-bytes-stub]
          (let [result (api/clone! {:conn   {:p4/port "h:1666"}
                                    :streams ["//stream/main" "//stream/dev"]
                                    :target  target})]
            (is (vector? result))
            (is (= 2 (count result)))
            (is (= 101 (-> result first :last-change)))
            (is (= 201 (-> result second :last-change)))))
        (testing "both refs exist in the bare repo"
          (let [{:keys [stdout-bytes]}
                (proc/run-checked! ["git" "-C" target "branch" "-a"])
                branches (String. ^bytes stdout-bytes "UTF-8")]
            (is (str/includes? branches "main"))
            (is (str/includes? branches "dev"))))
        (testing "marks file is shared (one file in repo dir)"
          (is (.exists (io/file target "clj-p4.marks"))))
        (finally (rm-rf d))))))

(deftest stream-and-streams-mutually-exclusive-test
  (testing "passing both :stream and :streams throws"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))]
      (try
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"mutually exclusive"
             (api/clone! {:conn   {:p4/port "h:1666"}
                          :stream  "//stream/main"
                          :streams ["//stream/main" "//stream/dev"]
                          :target  target})))
        (finally (rm-rf d)))))
  (testing "passing neither :stream nor :streams throws"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))]
      (try
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"needs :stream"
             (api/clone! {:conn   {:p4/port "h:1666"}
                          :target target})))
        (finally (rm-rf d))))))

(deftest stream-ref-override-test
  (testing ":stream->ref overrides default basename-derived refs"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                      p4/describe     (fn [_ n] (describe-fixture n))
                      p4/print-bytes! print-bytes-stub]
          (api/clone! {:conn        {:p4/port "h:1666"}
                       :streams     ["//stream/main"]
                       :stream->ref {"//stream/main" "refs/heads/trunk"}
                       :target      target}))
        (testing "the override ref exists, default would not"
          (let [{:keys [stdout-bytes]}
                (proc/run-checked! ["git" "-C" target "branch" "-a"])
                branches (String. ^bytes stdout-bytes "UTF-8")]
            (is (str/includes? branches "trunk"))))
        (finally (rm-rf d))))))

(deftest default-ref-of-source-test
  (testing "stream basename → refs/heads/<basename>"
    (is (= "refs/heads/main"   (#'api/default-ref-of-source "//stream/main")))
    (is (= "refs/heads/dev"    (#'api/default-ref-of-source "//stream/dev")))
    (is (= "refs/heads/legacy" (#'api/default-ref-of-source "//depot/legacy")))
    (is (= "refs/heads/main"   (#'api/default-ref-of-source "//stream/main/...")))
    (is (= "refs/heads/src"    (#'api/default-ref-of-source "//depot/legacy/src/...")))))

(deftest ephemeral-first-dispatch-uses-server-view-test
  (testing "0.12.0: stream clones use the server-resolved client view by default"
    (let [d (tmp-dir)
          target (str (io/file d "repo"))
          ;; The client View block here includes a path the parent-chain
          ;; merge wouldn't produce (//stream/main/extra/...). With the
          ;; ephemeral-first dispatch, a file under that path imports;
          ;; the pure-data merge would have filtered it out.
          view-lines ["//stream/main/src/...   //eph/src/..."
                      "//stream/main/extra/... //eph/extra/..."]
          with-eph (fn [_conn _stream f]
                     (let [info {:client/name "eph"
                                 :client/view-lines view-lines}
                           eph-conn {:p4/port "h:1666" :p4/client "eph"}]
                       (f eph-conn info)))
          fixture-cl {:p4/change 100 :p4/user "x" :p4/time 0
                      :p4/desc "extra"
                      :p4/files [{:rev/depot "//stream/main/extra/component.cpp"
                                  :rev/rev 1 :rev/action :add :rev/type :text
                                  :rev/flags #{} :rev/keyword-flags #{}}]}]
      (try
        (with-redefs [p4/info                  (constantly info-2024)
                      p4/stream-chain          (constantly [mainline])
                      p4/with-ephemeral-client with-eph
                      p4/changes               (fn [_ _ & _] [{:p4/change 100}])
                      p4/describe              (constantly fixture-cl)
                      p4/print-bytes!          print-bytes-stub]
          (api/clone! {:conn   {:p4/port "h:1666"}
                       :stream "//stream/main"
                       :target target}))
        (testing "the extra-component file imports because the server view sees it"
          (let [{:keys [stdout-bytes]}
                (proc/run-checked! ["git" "-C" target "ls-tree" "-r"
                                    "refs/heads/main"])
                listing (String. ^bytes stdout-bytes "UTF-8")]
            (is (str/includes? listing "extra/component.cpp"))))
        (finally (rm-rf d))))))

(deftest fallback-to-direct-when-client-create-denied-test
  (testing "create-stream-client! :proc-failed routes to clone-direct! parent-chain"
    ;; The :each fixture already forces create-failure; this test just
    ;; verifies the user-visible result: a stream still clones.
    (let [d (tmp-dir)
          target (str (io/file d "repo"))]
      (try
        (with-redefs [p4/info         (constantly info-2024)
                      p4/stream-chain (constantly [mainline])
                      p4/changes      (fn [_ _ & _] [{:p4/change 100}])
                      p4/describe     (fn [_ n] (describe-fixture n))
                      p4/print-bytes! print-bytes-stub]
          (let [result (api/clone! {:conn   {:p4/port "h:1666"}
                                    :stream "//stream/main"
                                    :target target})]
            (is (= 1 (:commits result)))))
        (finally (rm-rf d))))))

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
