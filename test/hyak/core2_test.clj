(ns hyak.core2-test
  (:require
   [clojure.test        :as t :refer [deftest testing is]]
   [hyak.core2          :as sut]
   [hyak.memory-store   :as memory-store]
   [hyak.postgres-store :as postgres-store]
   [hyak.redis-store    :as redis-store]))

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

(defn run-with-postgres-store [test-fn]
  (let [jdbc-url (System/getenv "JDBC_DATABASE_URL")
        prefix   "hyak_core2_test_"
        fstore   (postgres-store/create-fstore!
                  prefix jdbc-url {:recreate-tables? true})]
    (binding [*fstore* fstore]
      (test-fn))))

;; }}}

(defn add-remove-test []
  (testing "adding and removing features is idempotent"
    (let [fkey       (make-fkey "my-new-feature")
          expires-at (.plusMinutes (java.time.LocalDateTime/now) 15)
          author     "ryan@ryanmccuaig.net"]
      (is (not (sut/exists? *fstore* fkey)))
      (dotimes [_ 2]
        (sut/add! *fstore* fkey expires-at author)
        (is (sut/exists? *fstore* fkey)))
      (dotimes [_ 2]
        (sut/remove! *fstore* fkey)
        (is (not (sut/exists? *fstore* fkey)))))))

(deftest add-remove-all-stores-test
  (doto add-remove-test
    run-with-memory-store
    run-with-redis-store
    run-with-postgres-store))

;; vi:fdm=marker
