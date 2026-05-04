(ns clj-p4.shell.git-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-p4.shell.git :as git]
            [clj-p4.shell.proc :as proc]))

(defn- tmp-dir []
  (let [d (java.nio.file.Files/createTempDirectory
           "clj-p4-git-test"
           (make-array java.nio.file.attribute.FileAttribute 0))]
    (.toFile d)))

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

(defn- with-tmp-repo [body-fn]
  (let [d (tmp-dir)]
    (try (body-fn d)
         (finally (rm-rf d)))))

(deftest init-bare!-test
  (with-tmp-repo
    (fn [d]
      (git/init-bare! d)
      (is (.exists (io/file d "HEAD"))))))

(deftest fast-import-roundtrip-test
  (with-tmp-repo
    (fn [d]
      (git/init-bare! d)
      (let [h (git/fast-import-start d)]
        (git/blob! h 1 (.getBytes "hello world\n" "UTF-8"))
        (git/emit-commit! h
                          {:ref       "refs/heads/main"
                           :mark      100
                           :committer {:name "Test" :email "t@x"
                                       :time-ms 1700000000000 :tz "+0000"}
                           :message   "first commit"
                           :files     [{:op :M :mode "100644" :mark 1
                                        :path "hello.txt"}]})
        (git/blob! h 2 (.getBytes "hello again\n" "UTF-8"))
        (git/emit-commit! h
                          {:ref       "refs/heads/main"
                           :mark      101
                           :committer {:name "Test" :email "t@x"
                                       :time-ms 1700001000000 :tz "+0000"}
                           :message   "second commit"
                           :from      ":100"
                           :files     [{:op :M :mode "100644" :mark 2
                                        :path "hello.txt"}]})
        (let [{:keys [exit stderr]} (git/fast-import-close! h)]
          (is (zero? exit) (str "fast-import failed: " stderr)))

        (testing "git log shows 2 commits"
          (is (= 2 (git/commit-count d "refs/heads/main"))))

        (testing "tree contains the file"
          (let [{:keys [stdout-bytes]}
                (proc/run-checked!
                 ["git" "-C" (str d) "ls-tree" "-r" "refs/heads/main"])]
            (is (str/includes? (String. ^bytes stdout-bytes "UTF-8")
                               "hello.txt"))))))))

(deftest inline-blob-test
  (with-tmp-repo
    (fn [d]
      (git/init-bare! d)
      (let [h (git/fast-import-start d)]
        (git/emit-commit! h
                          {:ref       "refs/heads/main"
                           :mark      1
                           :committer {:name "T" :email "t@x" :time-ms 0 :tz "+0000"}
                           :message   "inline"
                           :files     [{:op :M :mode "100644" :inline true
                                        :bytes (.getBytes "inline content"
                                                          "UTF-8")
                                        :path "f.txt"}]})
        (let [{:keys [exit]} (git/fast-import-close! h)]
          (is (zero? exit)))
        (is (= 1 (git/commit-count d "refs/heads/main")))))))

(deftest delete-and-rename-test
  (with-tmp-repo
    (fn [d]
      (git/init-bare! d)
      (let [h (git/fast-import-start d)]
        (git/blob! h 1 (.getBytes "a\n" "UTF-8"))
        (git/blob! h 2 (.getBytes "b\n" "UTF-8"))
        (git/emit-commit! h
                          {:ref "refs/heads/main" :mark 100
                           :committer {:name "T" :email "t@x"
                                       :time-ms 0 :tz "+0000"}
                           :message "init"
                           :files [{:op :M :mode "100644" :mark 1 :path "a.txt"}
                                   {:op :M :mode "100644" :mark 2 :path "b.txt"}]})
        (git/emit-commit! h
                          {:ref "refs/heads/main" :mark 101
                           :committer {:name "T" :email "t@x"
                                       :time-ms 1000 :tz "+0000"}
                           :message "rename + delete"
                           :from ":100"
                           :files [{:op :R :from "a.txt" :to "renamed.txt"}
                                   {:op :D :path "b.txt"}]})
        (git/fast-import-close! h)
        (let [{:keys [stdout-bytes]}
              (proc/run-checked!
               ["git" "-C" (str d) "ls-tree" "-r" "refs/heads/main"])
              listing (String. ^bytes stdout-bytes "UTF-8")]
          (is (str/includes? listing "renamed.txt"))
          (is (not (str/includes? listing "a.txt")))
          (is (not (str/includes? listing "b.txt"))))))))

(deftest marks-file-resume-test
  (with-tmp-repo
    (fn [d]
      (git/init-bare! d)
      (let [marks-file (str (io/file d ".." "marks.txt"))
            h (git/fast-import-start d {:marks-file marks-file})]
        (git/blob! h 1 (.getBytes "x\n" "UTF-8"))
        (git/emit-commit! h
                          {:ref "refs/heads/main" :mark 100
                           :committer {:name "T" :email "t@x"
                                       :time-ms 0 :tz "+0000"}
                           :message "with-marks"
                           :files [{:op :M :mode "100644" :mark 1 :path "x"}]})
        (git/fast-import-close! h)
        (is (.exists (java.io.File. marks-file)))
        (let [content (slurp marks-file)]
          (is (str/includes? content ":100"))
          (is (str/includes? content ":1")))))))
