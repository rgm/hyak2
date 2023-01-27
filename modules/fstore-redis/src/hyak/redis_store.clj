(ns hyak.redis-store
  "A redis persistent feature (dark launch) store. Follows the same data format
   as https://github.com/jnunemaker/flipper so that that project's UI tools
   for managing flipper state should work."
  (:require
   [hyak.adapter     :as ha]
   [taoensso.carmine :as car :refer [wcar]]))

(defrecord FeatureStore [root-key carmine-opts]
  ha/IFStore
  (-features [_]
    (set (wcar carmine-opts (car/smembers root-key))))

  (-add! [_ fkey]
    (wcar carmine-opts (car/sadd root-key fkey)))

  (-remove! [_ fkey]
    (wcar carmine-opts (car/del fkey))
    (wcar carmine-opts (car/srem root-key fkey))))

(defn create-fstore!
  "Create a Redis feature store.

   Use the default `root-key` to be able to easily use Flipper-based tools."
  ([carmine-opts]
   (create-fstore! "flipper_features" carmine-opts))
  ([root-key carmine-opts]
   (->FeatureStore root-key carmine-opts)))

(defn destroy-fstore!
  "Purge all feature state from the Redis store, leaving other kvs alone."
  [fstore]
  (doseq [fkey (ha/-features fstore)] ;; destroy all features
      (ha/-remove! fstore fkey))
  (let [{:keys [carmine-opts root-key]} fstore]
      (wcar carmine-opts (car/del root-key))) ;; destroy the root key
  :destroyed)
