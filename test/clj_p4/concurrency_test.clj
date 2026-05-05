(ns clj-p4.concurrency-test
  "Tests for the private `bounded-pmap` helper in `clj-p4.runner`.

   The helper is the load-bearing replacement for `pmap` at the two
   parallel-fetch sites; these tests pin its three guarantees: order
   preservation, first-exception propagation, and concurrency cap."
  (:require [clj-p4.runner]
            [clojure.test :refer [deftest is testing]])
  (:import (java.util.concurrent CountDownLatch TimeUnit)
           (java.util.concurrent.atomic AtomicInteger)))

(def ^:private bounded-pmap
  "Reach the private helper via its var so tests don't bend the public API."
  #'clj-p4.runner/bounded-pmap)

(deftest sequential-fallback-test
  (testing "n is nil → mapv (sequential)"
    (is (= [2 3 4] (bounded-pmap nil inc [1 2 3]))))
  (testing "n <= 1 → mapv (sequential)"
    (is (= [2 3 4] (bounded-pmap 1 inc [1 2 3])))
    (is (= [2 3 4] (bounded-pmap 0 inc [1 2 3])))))

(deftest order-preservation-test
  (testing "output index matches input index even with random worker delay"
    (let [delays (mapv (fn [_] (rand-int 25)) (range 50))
          f      (fn [i]
                   (Thread/sleep (long (nth delays i)))
                   i)]
      (is (= (vec (range 50)) (bounded-pmap 8 f (range 50)))))))

(deftest exception-propagation-test
  (testing "first thrown exception propagates with original ex-data"
    (let [boom (fn [x]
                 (when (= x 5)
                   (throw (ex-info "boom" {:x x})))
                 x)]
      (try
        (bounded-pmap 4 boom (range 20))
        (is false "expected exception")
        (catch clojure.lang.ExceptionInfo e
          (is (= "boom" (.getMessage e)))
          (is (= {:x 5} (ex-data e))))))))

(deftest concurrency-cap-test
  (testing "no more than n workers run concurrently"
    (let [n        4
          active   (AtomicInteger. 0)
          peak     (AtomicInteger. 0)
          ;; Hold all workers in `f` until we've observed peak; without
          ;; synchronisation the assertion is racy on slow runners.
          gate     (CountDownLatch. 1)
          observed (CountDownLatch. n)
          f        (fn [x]
                     (let [now (.incrementAndGet active)]
                       (loop []
                         (let [old (.get peak)]
                           (when (and (> now old)
                                      (not (.compareAndSet peak old now)))
                             (recur))))
                       (.countDown observed)
                       (.await gate 2 TimeUnit/SECONDS)
                       (.decrementAndGet active)
                       x))
          fut      (future (bounded-pmap n f (range 20)))]
      ;; Wait for n workers to be in flight, then release them.
      (is (.await observed 2 TimeUnit/SECONDS)
          "expected n workers to start within 2s")
      (.countDown gate)
      (is (= (vec (range 20)) @fut))
      (is (= n (.get peak))
          (str "peak concurrent workers should equal n=" n))))

  (testing "single-thread mode (n=1) never spawns extra threads"
    (let [active (AtomicInteger. 0)
          peak   (AtomicInteger. 0)
          f      (fn [x]
                   (let [now (.incrementAndGet active)]
                     (loop []
                       (let [old (.get peak)]
                         (when (and (> now old)
                                    (not (.compareAndSet peak old now)))
                           (recur))))
                     (Thread/sleep 1)
                     (.decrementAndGet active)
                     x))]
      (bounded-pmap 1 f (range 10))
      (is (= 1 (.get peak)) "n=1 path must stay sequential"))))

(deftest stops-feeding-after-first-exception-test
  (testing "an exception on item 5 stops the rest from being processed"
    (let [n        4
          attempts (AtomicInteger. 0)
          f        (fn [x]
                     (.incrementAndGet attempts)
                     (when (= x 5)
                       (throw (ex-info "boom" {:x x})))
                     ;; Hold workers a moment so the close has a chance
                     ;; to land before they pull more work.
                     (Thread/sleep 25)
                     x)]
      (try (bounded-pmap n f (range 200))
           (is false "expected exception to propagate")
           (catch clojure.lang.ExceptionInfo _))
      ;; A handful of items can be in flight at the moment of throw
      ;; (n workers, plus one fed into the channel buffer). The
      ;; threshold is "well below" 200 — anything under ~3n means the
      ;; helper short-circuited rather than draining the input.
      (let [seen (.get attempts)]
        (is (< seen (* 3 n))
            (str "expected far fewer than 200 invocations after the "
                 "first failure; saw " seen))))))

(deftest empty-coll-test
  (testing "empty input returns empty vector"
    (is (= [] (bounded-pmap 4 inc [])))
    (is (= [] (bounded-pmap 1 inc [])))
    (is (= [] (bounded-pmap nil inc [])))))
