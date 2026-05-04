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
