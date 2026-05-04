(ns clj-p4.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.plan :as plan]))

(def ^:private mainline
  {:stream/name "//stream/main"
   :stream/options #{}
   :stream/paths   [[:share "src/..."]]
   :stream/remapped []
   :stream/ignored  []})

(def ^:private conn
  {:p4/port "ssl:host:1666"})

(deftest clone-plan-shape-test
  (let [p (plan/clone-plan
           {:conn         conn
            :stream-chain [mainline]
            :changelists  [100 101 102]
            :target       "/tmp/x"
            :excludes     []
            :options      {:checkpoint-every 1000}})]
    (is (= :clone     (:plan/kind p)))
    (is (= conn       (:plan/conn p)))
    (is (= mainline   (:plan/stream p)))
    (is (= [100 101 102] (:plan/changelists p)))
    (is (= "/tmp/x"   (:plan/target p)))
    (is (= "//stream/main" (-> p :plan/view :view/stream)))))

(deftest sync-plan-shape-test
  (let [p (plan/sync-plan
           {:conn         conn
            :stream-chain [mainline]
            :changelists  [200 201]
            :target       "/tmp/x"
            :since-change 199})]
    (is (= :sync (:plan/kind p)))
    (is (= 199   (:plan/since-change p)))))

(deftest re-plan-test
  (let [p  (plan/clone-plan
            {:conn conn :stream-chain [mainline]
             :changelists [100 101 102 103] :target "/tmp/x"})
        p' (plan/re-plan p 101)]
    (is (= [102 103] (:plan/changelists p')))))

(deftest operation-seq-laziness-test
  (let [p (plan/clone-plan
           {:conn conn :stream-chain [mainline]
            :changelists (range 1 1000001)
            :target "/tmp/x"})]
    (testing "doesn't realise full seq"
      (is (= 5 (count (take 5 (plan/operation-seq p))))))))

(deftest operation-seq-content-test
  (let [p   (plan/clone-plan
             {:conn conn :stream-chain [mainline]
              :changelists [100 101 102]
              :target "/tmp/x"
              :options {:checkpoint-every 1000}})
        ops (vec (plan/operation-seq p))]
    (is (= [{:op/kind :process-change :op/change 100 :op/idx 0}
            {:op/kind :process-change :op/change 101 :op/idx 1}
            {:op/kind :process-change :op/change 102 :op/idx 2}
            {:op/kind :checkpoint     :op/last-change 102}]
           ops))))

(deftest operation-seq-checkpoint-frequency-test
  (let [p   (plan/clone-plan
             {:conn conn :stream-chain [mainline]
              :changelists [1 2 3 4 5 6]
              :target "/tmp/x"
              :options {:checkpoint-every 2}})
        ops (vec (plan/operation-seq p))]
    (is (= [{:op/kind :process-change :op/change 1 :op/idx 0}
            {:op/kind :process-change :op/change 2 :op/idx 1}
            {:op/kind :checkpoint     :op/last-change 2}
            {:op/kind :process-change :op/change 3 :op/idx 2}
            {:op/kind :process-change :op/change 4 :op/idx 3}
            {:op/kind :checkpoint     :op/last-change 4}
            {:op/kind :process-change :op/change 5 :op/idx 4}
            {:op/kind :process-change :op/change 6 :op/idx 5}
            {:op/kind :checkpoint     :op/last-change 6}]
           ops))))

(deftest plan-is-edn-printable-test
  (let [p (plan/clone-plan
           {:conn conn :stream-chain [mainline]
            :changelists [100] :target "/tmp/x"})
        s (pr-str (-> p
                      (update :plan/view dissoc :view/entries)
                      (update :plan/view dissoc :view/remap)
                      (update :plan/view dissoc :view/ignores)))]
    (is (string? s))
    (is (pos? (count s)))))
