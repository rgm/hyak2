(ns hyak.redis-store
  "A redis persistent feature (dark launch) store. Follows the same data format
   as https://github.com/jnunemaker/flipper so that that project's UI tools for
   the Redis adapter for managing feature state should work for the base state.
   (It won't work for the expiry and author data; these are hyak
   extensions)."
  (:require
   [hyak.adapter     :as ha]
   [taoensso.carmine :as car :refer [wcar]]))

(defrecord FeatureStore [root-key carmine-opts]
  ha/IFStore
  (-features [_]
    (set (wcar carmine-opts (car/smembers root-key))))

  (-add! [_ fkey expires-at author]
    (wcar carmine-opts (car/sadd root-key fkey))
    :added)

  (-remove! [_ fkey]
    (wcar carmine-opts (car/del fkey))
    (wcar carmine-opts (car/srem root-key fkey))))

(defn create-fstore!
  "Create a Redis feature store.

   Use the default `root-key` to be able to easily use Flipper-based tools."
  ([carmine-opts opts]
   (create-fstore! "flipper_features" carmine-opts opts))
  ([root-key carmine-opts _opts]
   (->FeatureStore root-key carmine-opts)))

(defn destroy-fstore!
  "Purge all feature state from the Redis store, leaving other kvs alone."
  [fstore]
  (doseq [fkey (ha/-features fstore)] ;; destroy all features
      (ha/-remove! fstore fkey))
  (let [{:keys [carmine-opts root-key]} fstore]
      (wcar carmine-opts (car/del root-key))) ;; destroy the root key
  :destroyed)
