(ns hyak2.core-test
  #?(:clj
     (:require
      [clojure.core.async            :as a]
      [clojure.tools.logging.test    :refer [logged? with-log]]
      [clojure.test                  :as t :refer [deftest testing is]]
      [clojure.test.check.generators :as gen]
      [hyak2.core                    :as sut]
      [hyak2.memory-store            :as memory-store]
      [hyak2.postgres-store          :as postgres-store]
      [hyak2.redis-store             :as redis-store]
      [hyak2.platform                :as hp])
     :cljs
     (:require
      [clojure.core.async            :as a]
      [clojure.test                  :as t :refer [deftest testing is]]
      [clojure.test.check.generators :as gen]
      [hyak2.core                    :as sut]
      [hyak2.memory-store            :as memory-store]
      [hyak2.platform                :as hp])))

;; TODO implement for cljs
#?(:cljs (defn logged? [_ _ _] false))
#?(:cljs (defn with-log [_ _]))

(defn make-fkey [s]
  ;; prefix all features so we can clean up strays in persistent stores
  (str "TEST_FEATURE_" s))

;; * fixtures {{{

;; bit odd, kaocha flips out on reporting if fixtures don't end with the
;; test-fn so in lieu of `clojure.test/use-fixture` I found this technique of
;; leaning on the `testing` macro outside of deftests useful:
;; https://stackoverflow.com/a/31766419/53790

(def ^:dynamic *fstore*)

(defn run-with-memory-store [test-fn]
  (binding [*fstore* (memory-store/create-fstore!)]
    (test-fn)))

#?(:clj (defn run-with-redis-store [test-fn]
          (let [fstore (redis-store/create-fstore!
                        "hyak-core2-test"
                        {:spec {:uri (System/getenv "REDIS_URL")}}
                        {:clean? true})]
            (binding [*fstore* fstore]
              (test-fn))
            (redis-store/destroy-fstore! fstore)))
   :cljs (defn run-with-redis-store [_]))

#? (:clj (defn run-with-postgres-store
           ([test-fn]
            (run-with-postgres-store {:recreate-tables? true} test-fn))
           ([fstore-opts test-fn]
            (let [jdbc-url (System/getenv "JDBC_DATABASE_URL")
                  prefix   "hyak_core2_test_"
                  fstore   (postgres-store/create-fstore! prefix jdbc-url fstore-opts)]
              (binding [*fstore* fstore]
                (test-fn)))))
    :cljs (defn run-with-postgres-store ([_]) ([_ _])))

;; }}}

(defn add-remove-test []
  (testing "adding and removing features are idempotent"
    (let [fkey       (make-fkey "my-new-feature")
          expires-at (.plusMinutes (hp/now) 15)
          author     "ryan@ryanmccuaig.net"]
      (is (not (sut/feature-exists? *fstore* fkey)))
      (dotimes [_ 2]
        (sut/add! *fstore* fkey expires-at author)
        (is (sut/feature-exists? *fstore* fkey))
        (is (= #{fkey} (sut/features *fstore*))))
      (dotimes [_ 2]
        (sut/remove! *fstore* fkey)
        (is (not (sut/feature-exists? *fstore* fkey)))))))

(deftest add-remove-all-stores-test
  (doto add-remove-test
    run-with-memory-store
    run-with-redis-store
    run-with-postgres-store))

(defn boolean-gate-test []
  (testing "boolean gates work & enable/disable are idempotent"
    (let [fkey (make-fkey "boolean-feature")]
      (sut/add! *fstore* fkey nil nil)
      (is (not (sut/enabled? *fstore* fkey)))
      (dotimes [_ 2]
        (sut/enable! *fstore* fkey)
        (is (sut/enabled? *fstore* fkey)))
      (dotimes [_ 2]
        (sut/disable! *fstore* fkey)
        (is (not (sut/enabled? *fstore* fkey)))))))

(deftest all-stores-boolean-gate-test
  (doto boolean-gate-test
    run-with-memory-store
    run-with-redis-store
    run-with-postgres-store))

(deftest postgres-caching-test
  (run-with-postgres-store
   {:recreate-tables? true :ttl/threshold 50}
   (fn []
     (testing "postgres can cache feature tests"
       (let [fkey (make-fkey "my-cached-feature")]
         (sut/add! *fstore* fkey nil nil)
         (sut/enable! *fstore* fkey)
         (is (sut/enabled? *fstore* fkey nil))
         (sut/disable! *fstore* fkey)
         (is (sut/enabled? *fstore* fkey nil))
         ;; TODO cross-platform pause
         (a/<!! (a/timeout 100))
         (is (not (sut/enabled? *fstore* fkey nil))))))))

(defn actor-gate-test []
  (testing "actor gates work & enable/disable are idempotent"
    (let [fkey (make-fkey "actor-feature")
          akey-yep "beta-tester"
          akey-mmhm "beta-tester-2"
          akey-nope "regular-user"]
      (sut/add! *fstore* fkey nil nil)
      (is (not (sut/enabled? *fstore* fkey akey-yep)))
      (is (not (sut/enabled? *fstore* fkey akey-mmhm)))
      (is (not (sut/enabled? *fstore* fkey akey-nope)))
      (dotimes [_ 2]
        (sut/enable-actor! *fstore* fkey akey-yep)
        (is (sut/enabled? *fstore* fkey akey-yep))
        (is (not (sut/enabled? *fstore* fkey akey-mmhm)))
        (is (not (sut/enabled? *fstore* fkey akey-nope))))
      (dotimes [_ 2]
        (sut/disable-actor! *fstore* fkey akey-yep)
        (sut/enable-actor! *fstore* fkey akey-mmhm)
        (is (not (sut/enabled? *fstore* fkey akey-yep)))
        (is (sut/enabled? *fstore* fkey akey-mmhm))
        (is (not (sut/enabled? *fstore* fkey akey-nope)))))))

(deftest all-stores-actor-test
  (doto actor-gate-test
    run-with-memory-store
    run-with-redis-store
    run-with-postgres-store))

(defn group-gate-test []
  (testing "group gates work & enable/disable/reg/dereg are idempotent"
    (let [fkey      (make-fkey "group-feature")
          akey-yep  "yep"
          akey-nope "nope"
          gkey      :early-access
          pred      (fn [akey] (= akey akey-yep))]
      (sut/add! *fstore* fkey nil nil)
      (is (not (sut/enabled? *fstore* fkey akey-yep)))
      (is (not (sut/enabled? *fstore* fkey akey-nope)))
      (dotimes [_ 2]
        (sut/register-group! *fstore* gkey pred)
        (is (thrown? #? (:clj Exception :cljs js/Error)
                     (sut/enable-group! *fstore* fkey :unknown-k)))
        (sut/enable-group! *fstore* fkey gkey)
        (is (sut/enabled? *fstore* fkey akey-yep))
        (is (not (sut/enabled? *fstore* fkey akey-nope))))
      (dotimes [_ 2]
        (sut/disable-group! *fstore* fkey gkey)
        (is (not (sut/enabled? *fstore* fkey akey-yep)))
        (is (not (sut/enabled? *fstore* fkey akey-nope)))))))

(deftest all-stores-group-test
  (doto group-gate-test
    run-with-memory-store
    run-with-redis-store
    run-with-postgres-store))

(defn epsilon= [eps a b] (< (abs (- a b)) eps))

(defn percent-of-time-test []
  (testing "pct-of-time gate works, is idempotent"
    (let [fkey      (make-fkey "pct-of-time-feature")
          n         10000
          pct       (inc (rand-int 99))
          n-enabled (fn [] (->> (repeat n "an-akey")
                                (filter #(sut/enabled? *fstore* fkey %))
                                (count)))]
      (sut/add! *fstore* fkey nil nil)
      (is (not (sut/enabled? *fstore* fkey)))
      (sut/enable-percentage-of-time! *fstore* fkey pct)
      (let [eps (* n 0.05)]
        (is (epsilon= eps (* (/ pct 100) n) (n-enabled))))
      (sut/disable-percentage-of-time! *fstore* fkey)
      (is (not (sut/enabled? *fstore* fkey))))))

(deftest all-store-percent-of-time-test
  (doto percent-of-time-test
    run-with-memory-store
    run-with-redis-store
    (partial run-with-postgres-store {:ttl/threshold 1500})))

(defn percent-of-actors-test []
  (testing "pct-of-actors gate works, is idempotent"
    (let [fkey      (make-fkey "pct-of-actors-feature")
          n         10000
          pct       (inc (rand-int 99))
          akeys     (gen/sample gen/string n)
          n-enabled (fn [akeys] (->> akeys
                                     (filter #(sut/enabled? *fstore* fkey %))
                                     (count)))]
      (sut/add! *fstore* fkey nil nil)
      (is (= 0 (n-enabled akeys)))
      (sut/enable-percentage-of-actors! *fstore* fkey pct)
      (let [eps (* n 0.05)]
        (is (epsilon= eps (* (/ pct 100) n) (n-enabled akeys))))
      (sut/disable-percentage-of-actors! *fstore* fkey)
      (is (= 0 (n-enabled akeys))))))

(deftest all-store-percent-of-actor-test
  (doto percent-of-actors-test
    run-with-memory-store
    run-with-redis-store
    (partial run-with-postgres-store {:ttl/threshold 1500})))

(deftest expired-test
  (doto
   #(testing "a feature can be `expired?`"
      (let [fkey (make-fkey "my-expired-feature")
            now  (hp/now)
            past (.minusHours now 1)
            fut  (.plusHours now 1)]
        (sut/add! *fstore* fkey past)
        (is (sut/expired? *fstore* fkey now))
        (sut/add! *fstore* fkey fut)
        (is (not (sut/expired? *fstore* fkey now)))))
    run-with-memory-store
    run-with-redis-store
    run-with-postgres-store))

(defn stale-fstore-test []
  (testing "an `expires-at` in the past means fstore is `stale?` and noisy"
    (let [fkey (make-fkey "expired-feature")
          now  (hp/now)
          past (.minusHours now 1)
          fut  (.plusHours now 1)]
      (is (not (sut/stale? *fstore* now)))
      (with-log
        (sut/enabled? *fstore* fkey)
        (is (not (logged? 'hyak2.core :warn #"expired"))
            (str "warned but shouldn't have with " *fstore*)))
      (sut/add! *fstore* fkey past)
      (is (sut/stale? *fstore* now))
      (with-log
        (sut/enabled? *fstore* fkey)
        ;; warn, the feature's expired
        (is (logged? 'hyak2.core :warn #"expired")
            (str "didn't warn but should have with " *fstore*)))
      (sut/add! *fstore* fkey fut)
      (is (not (sut/stale? *fstore* now)))
      (with-log
        (sut/enabled? *fstore* fkey)
        ;; don't warn, we should be OK again
        (is (not (logged? 'hyak2.core :warn #"expired"))
            (str "warned but shouldn't have with " *fstore*))))))

(deftest all-stores-stale-test
  (doto stale-fstore-test
    run-with-memory-store
    run-with-redis-store
    run-with-postgres-store))

;; vi:fdm=marker
