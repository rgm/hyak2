(ns hyak2.core-test
  (:require
   [clojure.test         :as t :refer [deftest testing is]]
   [hyak2.core           :as sut]
   [hyak2.memory-store   :as memory-store]
   [hyak2.postgres-store :as postgres-store]
   [hyak2.redis-store    :as redis-store])
  (:import
   [java.time LocalDateTime]))

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

(defn run-with-redis-store [test-fn]
  (let [fstore (redis-store/create-fstore!
                "hyak-core2-test"
                {:spec {:uri (System/getenv "REDIS_URL")}}
                {:clean? true})]
    (binding [*fstore* fstore]
      (test-fn))
    (redis-store/destroy-fstore! fstore)))

(defn run-with-postgres-store
  ([test-fn]
   (run-with-postgres-store {:recreate-tables? true} test-fn))
  ([fstore-opts test-fn]
   (let [jdbc-url (System/getenv "JDBC_DATABASE_URL")
         prefix   "hyak_core2_test_"
         fstore   (postgres-store/create-fstore! prefix jdbc-url fstore-opts)]
     (binding [*fstore* fstore]
       (test-fn)))))

;; }}}

(deftest before?-test
  ;; ugh dates are easy to mess up, esp w nils
  (let [before? #'sut/before?
        present (LocalDateTime/now)
        past (.minusHours present 1)
        future (.plusHours present 1)]
    (is (before? past present))
    (is (before? present future))
    (is (not (before? present past)))
    (is (not (before? future present)))
    (is (not (before? present nil)))
    (is (not (before? nil present)))
    (is (not (before? nil nil)))))

(defn add-remove-test []
  (testing "adding and removing features are idempotent"
    (let [fkey       (make-fkey "my-new-feature")
          expires-at (.plusMinutes (LocalDateTime/now) 15)
          author     "ryan@ryanmccuaig.net"]
      (is (not (sut/exists? *fstore* fkey)))
      (dotimes [_ 2]
        (sut/add! *fstore* fkey expires-at author)
        (is (sut/exists? *fstore* fkey))
        (is (= #{fkey} (sut/features *fstore*))))
      (dotimes [_ 2]
        (sut/remove! *fstore* fkey)
        (is (not (sut/exists? *fstore* fkey)))))))

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
         (Thread/sleep 100)
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
        (is (thrown? Exception (sut/enable-group! *fstore* fkey :unknown-k)))
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

(defn stale-fstore-test []
  (testing "an `expires-at` in the past mean fstore is `stale?`"
    (let [fkey   (make-fkey "expired-feature")
          now    (LocalDateTime/now)
          past   (.minusHours now 1)
          future (.plusHours now 1)]
      (is (not (sut/stale? *fstore* now)))
      (sut/add! *fstore* fkey past)
      (is (sut/stale? *fstore* now))
      (sut/add! *fstore* fkey future)
      (is (not (sut/stale? *fstore* now))))))

(deftest all-stores-stale-test
  (doto stale-fstore-test
    run-with-memory-store
    run-with-redis-store
    run-with-postgres-store))

;; vi:fdm=marker
