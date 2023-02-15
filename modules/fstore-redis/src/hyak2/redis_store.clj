(ns hyak2.redis-store
  "A redis persistent feature (dark launch) store. Follows the same data format
   as https://github.com/jnunemaker/flipper so that that project's UI tools for
   the Redis adapter for managing feature state should work for the base state.
   (It won't work for the expiry and author data; these are hyak
   extensions)."
  (:require
   [clojure.string   :as string]
   [hyak2.adapter    :as ha]
   [hyak2.time       :as ht]
   [taoensso.carmine :as car :refer [wcar]]))

(defn- get-feature [carmine-opts fkey]
  (apply hash-map (wcar carmine-opts (car/hgetall fkey))))

(defn- fkey->meta-key [fkey]
  (str "metadata:" fkey))

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

(defn- pct-of-time-gate-open? [gate-values]
  (when-let [percent-str (get gate-values "percentage_of_time")]
    (< (rand) (/ (parse-long percent-str) 100.0))))

(defrecord FeatureStore [root-key *group-registry carmine-opts]
  ha/IFStore
  (-features [_]
    (->> (wcar carmine-opts (car/smembers root-key))
         (map #(assoc (or (wcar carmine-opts (car/get (fkey->meta-key %))) {})
                      :fkey %))))

  (-add! [_ fkey expires-at author]
    (doto carmine-opts
      (wcar (car/sadd root-key fkey))
      (wcar (car/set (fkey->meta-key fkey) {:expires-at expires-at
                                            :author author}))))

  (-remove! [_ fkey]
    (doto carmine-opts
      (wcar (car/del fkey))
      (wcar (car/del (fkey->meta-key fkey)))
      (wcar (car/srem root-key fkey))))

  (-expired? [_ fkey now]
    (let [metadata (wcar carmine-opts (car/get (fkey->meta-key fkey)))]
      (ht/before? (:expires-at metadata) now)))

  (-disable! [_ fkey]
    (wcar carmine-opts (car/del fkey)))

  (-enabled? [_ fkey akey]
    (let [gate-values (get-feature carmine-opts fkey)]
      (or (pct-of-time-gate-open? gate-values)
          (boolean-gate-open? gate-values)
          (actor-gate-open? gate-values akey)
          (group-gate-open? @*group-registry gate-values akey))))

  (-enable! [_ fkey]
    (wcar carmine-opts (car/hset fkey "boolean" "true")))

  (-enable-actor! [_ fkey akey]
    (wcar carmine-opts (car/hset fkey (akey->str akey) "1")))

  (-disable-actor! [_ fkey akey]
    (wcar carmine-opts (car/hdel fkey (akey->str akey))))

  (-groups [_]
    (->> @*group-registry keys (map str->gkey) set))

  (-register-group! [_ gkey pred]
    (swap! *group-registry assoc (gkey->str gkey) pred))

  (-unregister-group! [_ gkey]
    (swap! *group-registry dissoc (gkey->str gkey)))

  (-enable-group! [_ fkey gkey]
    (wcar carmine-opts (car/hset fkey (gkey->str gkey) "1")))

  (-disable-group! [_ fkey gkey]
    (wcar carmine-opts (car/hdel fkey (gkey->str gkey))))

  (-enable-percentage-of-time! [_ fkey pct]
    (wcar carmine-opts (car/hset fkey "percentage_of_time" (str pct))))

  (-disable-percentage-of-time! [_ fkey]
    (wcar carmine-opts (car/hdel fkey "percentage_of_time"))))

(defn create-fstore!
  "Create a Redis feature store.

   Use the default `root-key` to be able to easily use Flipper-based tools."
  ([carmine-opts opts]
   (create-fstore! "flipper_features" carmine-opts opts))
  ([root-key carmine-opts opts]
   (let [fstore (->FeatureStore root-key (atom {}) carmine-opts)]
     (when (:clean? opts)
       (doseq [{fkey :fkey} (ha/-features fstore)]
         (ha/-remove! fstore fkey)))
     fstore)))

(defn destroy-fstore!
  "Purge all feature state from the Redis store, leaving other kvs alone."
  [fstore]
  (doseq [{fkey :fkey} (ha/-features fstore)] ;; destroy all features
    (ha/-remove! fstore fkey))
  (let [{:keys [carmine-opts root-key]} fstore]
    (wcar carmine-opts (car/del root-key))) ;; destroy the root key
  :destroyed)
