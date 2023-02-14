(ns hyak2.redis-store
  "A redis persistent feature (dark launch) store. Follows the same data format
   as https://github.com/jnunemaker/flipper so that that project's UI tools for
   the Redis adapter for managing feature state should work for the base state.
   (It won't work for the expiry and author data; these are hyak
   extensions)."
  (:require
   [clojure.string   :as string]
   [hyak2.adapter    :as ha]
   [taoensso.carmine :as car :refer [wcar]]))

(defn- get-feature [carmine-opts fkey]
  (apply hash-map (wcar carmine-opts (car/hgetall fkey))))

(defn- boolean-gate-open? [gate-values]
  (= "true" (get gate-values "boolean")))

(defn- akey->str [akey] (str "actors/" (name (or akey ""))))

(defn- actor-gate-open? [gate-values akey]
  (= "1" (get gate-values (akey->str akey) "")))

(defn- gkey->str [gkey] (str "groups/" (name gkey)))

(defn- str->gkey [s] (as-> s $ (string/replace $ #"^groups/" "") (keyword $)))

(defn- group-gate-open? [group-registry gate-values akey]
  (let [active? (fn [[k v]] (and (string/starts-with? k "groups/") (= v "1")))
        gkeys   (into [] (comp (filter active?) (map key)) gate-values)
        preds   (vals (select-keys group-registry gkeys))]
    (when-not (empty? preds) ((apply some-fn preds) akey))))

(defrecord FeatureStore [root-key *group-registry carmine-opts]
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
      (or (boolean-gate-open? gate-values)
          (actor-gate-open? gate-values akey)
          (group-gate-open? @*group-registry gate-values akey))))

  (-enable! [_ fkey]
    (wcar carmine-opts (car/hset fkey "boolean" "true"))
    :enabled-boolean)

  (-enable-actor! [_ fkey akey]
    (wcar carmine-opts (car/hset fkey (akey->str akey) "1"))
    :enabled-actor)

  (-disable-actor! [_ fkey akey]
    (wcar carmine-opts (car/hdel fkey (akey->str akey)))
    :disabled-actor)

  (-groups [_]
    (->> @*group-registry keys (map str->gkey) set))

  (-register-group! [_ gkey pred]
    (swap! *group-registry assoc (gkey->str gkey) pred))

  (-unregister-group! [_ gkey]
    (swap! *group-registry dissoc (gkey->str gkey)))

  (-enable-group! [_ fkey gkey]
    (wcar carmine-opts (car/hset fkey (gkey->str gkey) "1")))

  (-disable-group! [_ fkey gkey]
    (wcar carmine-opts (car/hdel fkey (gkey->str gkey)))))

(defn create-fstore!
  "Create a Redis feature store.

   Use the default `root-key` to be able to easily use Flipper-based tools."
  ([carmine-opts opts]
   (create-fstore! "flipper_features" carmine-opts opts))
  ([root-key carmine-opts _opts]
   (->FeatureStore root-key (atom {}) carmine-opts)))

(defn destroy-fstore!
  "Purge all feature state from the Redis store, leaving other kvs alone."
  [fstore]
  (doseq [fkey (ha/-features fstore)] ;; destroy all features
    (ha/-remove! fstore fkey))
  (let [{:keys [carmine-opts root-key]} fstore]
    (wcar carmine-opts (car/del root-key))) ;; destroy the root key
  :destroyed)
