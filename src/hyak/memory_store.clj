(ns hyak.memory-store
  (:require
   [hyak.adapter :as ha]))

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
                        (update :gates dissoc fkey))))))

(defn create-fstore! []
  (let [initial-state {:features #{} :meta {} :gates {}}]
    (->FeatureStore (atom initial-state))))
