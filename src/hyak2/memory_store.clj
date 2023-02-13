(ns hyak2.memory-store
  (:require
   [hyak2.adapter :as ha]))

(defn- boolean-gate-open? [gates fkey]
  (boolean (get-in gates [fkey :gate/boolean])))

(defrecord FeatureStore [*state]
  ha/IFStore
  (-features [_]
    (:features @*state))

  (-add! [_ fkey expires-at author]
    (swap! *state update :features #(conj % fkey))
    (swap! *state assoc-in [:meta fkey]
           {:expires-at expires-at :author author}))

  (-remove! [_ fkey]
    (swap! *state (fn [state]
                    (-> state
                        (update :features disj fkey)
                        (update :gates dissoc fkey)))))

  (-disable! [_ fkey]
    ;; "disabled" means just having no gates at all for the fkey
    (swap! *state #(update  % :gates dissoc fkey)))

  (-enabled? [_ fkey _akey]
    (boolean-gate-open? (:gates @*state) fkey))

  (-enable! [_ fkey]
    (swap! *state #(assoc-in % [:gates fkey :gate/boolean] true))))

(defn create-fstore! []
  (let [initial-state {:features #{} :meta {} :gates {}}]
    (->FeatureStore (atom initial-state))))
