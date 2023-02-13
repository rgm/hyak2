(ns hyak2.redis-store
  "A redis persistent feature (dark launch) store. Follows the same data format
   as https://github.com/jnunemaker/flipper so that that project's UI tools for
   the Redis adapter for managing feature state should work for the base state.
   (It won't work for the expiry and author data; these are hyak
   extensions)."
  (:require
   [hyak2.adapter    :as ha]
   [taoensso.carmine :as car :refer [wcar]]))

(defn- get-feature [carmine-opts fkey]
  (apply hash-map (wcar carmine-opts (car/hgetall fkey))))

(defn- boolean-gate-open? [gate-values _akey]
  (= "true" (get gate-values "boolean")))

(defrecord FeatureStore [root-key carmine-opts]
  ha/IFStore
  (-features [_]
    (set (wcar carmine-opts (car/smembers root-key))))

  (-add! [_ fkey _expires-at _author]
    (wcar carmine-opts (car/sadd root-key fkey))
    :added)

  (-remove! [_ fkey]
    (wcar carmine-opts (car/del fkey))
    (wcar carmine-opts (car/srem root-key fkey))
    :removed)

  (-disable! [_ fkey]
    (wcar carmine-opts (car/del fkey))
    :disabled)

  (-enabled? [_ fkey akey]
    (let [gate-values (get-feature carmine-opts fkey)]
      (boolean-gate-open? gate-values akey)))

  (-enable! [_ fkey]
    (wcar carmine-opts (car/hset fkey "boolean" "true"))
    :enabled))

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
