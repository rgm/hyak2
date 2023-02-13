(ns hyak2.memory-store
  (:require
   [hyak2.adapter :as ha]))

(defn- boolean-gate-open? [gates fkey]
  (boolean (get-in gates [fkey :gate/boolean])))

(defn- actor-gate-open? [gates fkey akey]
  (let [open? (get-in gates [fkey :gate/actor] #{})]
    (boolean (open? akey))))

(defrecord FeatureStore [*state]
  ha/IFStore
  (-features [_]
    (:features @*state))

  (-add! [_ fkey expires-at author]
    (swap! *state update :features #(conj % fkey))
    (swap! *state assoc-in [:meta fkey]
           {:expires-at expires-at :author author})
    :added)

  (-remove! [_ fkey]
    (swap! *state (fn [state]
                    (-> state
                        (update :features disj fkey)
                        (update :gates dissoc fkey))))
    :removed)

  (-disable! [_ fkey]
    ;; "disabled" means just having no gates at all for the fkey
    (swap! *state #(update  % :gates dissoc fkey))
    :disabled)

  (-enabled? [_ fkey akey]
    (let [gates (:gates @*state)]
      (or (boolean-gate-open? gates fkey)
          (actor-gate-open? gates fkey akey))))

  (-enable! [_ fkey]
    (swap! *state #(assoc-in % [:gates fkey :gate/boolean] true))
    :enabled-boolean)

  (-enable-actor! [_ fkey akey]
    (swap! *state #(update-in % [:gates fkey :gate/actor]
                              (fnil conj #{}) akey))
    :enabled-actor)

  (-disable-actor! [_ fkey akey]
    (swap! *state #(update-in % [:gates fkey :gate/actor]
                              (fnil disj #{}) akey))
    :disabled-actor))

(defn create-fstore! []
  (let [initial-state {:features #{} :meta {} :gates {}}]
    (->FeatureStore (atom initial-state))))
