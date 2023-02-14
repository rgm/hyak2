(ns hyak2.memory-store
  (:require
   [hyak2.adapter :as ha]))

(defn- boolean-gate-open? [gates fkey]
  (boolean (get-in gates [fkey :gate/boolean])))

(defn- actor-gate-open? [gates fkey akey]
  (let [open? (get-in gates [fkey :gate/actor] #{})]
    (boolean (open? akey))))

(defn- group-gate-open? [group-registry gates fkey akey]
  (let [active-group? (get-in gates [fkey :gate/group] #{})
        active-preds  (keep (fn [[k v]] (when (active-group? k) v))
                            group-registry)]
    (when-not (empty? active-preds)
      ((apply some-fn active-preds) akey))))

(defrecord FeatureStore [*state]
  ha/IFStore
  (-features [_]
    (let [ms (->> (:fkeys @*state)
                  (map #(assoc (get-in @*state [:meta %])
                               :fkey %)))]
      ms))

  (-add! [_ fkey expires-at author]
    (swap! *state update :fkeys #(conj % fkey))
    (swap! *state assoc-in [:meta fkey]
           {:expires-at expires-at :author author}))

  (-remove! [_ fkey]
    (swap! *state (fn [state]
                    (-> state
                        (update :fkeys disj fkey)
                        (update :gates dissoc fkey)))))

  (-disable! [_ fkey]
    ;; "disabled" means just having no gates at all for the fkey
    (swap! *state #(update  % :gates dissoc fkey)))

  (-enabled? [_ fkey akey]
    (let [gates (:gates @*state)]
      (or (boolean-gate-open? gates fkey)
          (actor-gate-open? gates fkey akey)
          ;; group gate can get pricey; do last for OR short-circuit
          (group-gate-open? (:group-registry @*state) gates fkey akey))))

  (-enable! [_ fkey]
    (swap! *state #(assoc-in % [:gates fkey :gate/boolean] true)))

  (-enable-actor! [_ fkey akey]
    (swap! *state #(update-in % [:gates fkey :gate/actor]
                              (fnil conj #{}) akey)))

  (-disable-actor! [_ fkey akey]
    (swap! *state #(update-in % [:gates fkey :gate/actor]
                              (fnil disj #{}) akey)))

  (-groups [_]
    (-> (get @*state :group-registry {}) keys set))

  (-register-group! [_ gkey pred]
    (swap! *state #(assoc-in % [:group-registry gkey] pred)))

  (-unregister-group! [_ gkey]
    (swap! *state #(update % :group-registry dissoc gkey)))

  (-enable-group! [_ fkey gkey]
    (swap! *state #(update-in % [:gates fkey :gate/group]
                              (fnil conj #{}) gkey)))

  (-disable-group! [_ fkey gkey]
    (swap! *state #(update-in % [:gates fkey :gate/group]
                              (fnil disj #{}) gkey))))

(defn create-fstore! []
  (let [initial-state {:fkeys #{} :meta {}}]
    (->FeatureStore (atom initial-state))))
