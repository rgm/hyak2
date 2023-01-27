(ns hyak.memory-store
  (:require
   [hyak.adapter :as ha]))

(defrecord FeatureStore [*state]
  ha/IFStore
  (-features [_]
    (:features @*state))

  (-add! [_ fkey]
    (swap! *state update :features #(conj % fkey)))

  (-remove! [_ fkey]
    (swap! *state (fn [state]
                    (-> state
                        (update :features disj fkey)
                        (update :gates dissoc fkey))))))

(defn create-fstore! []
  (let [initial-state {:features #{} :gates {}}]
    (->FeatureStore (atom initial-state))))

(comment
  (def fstore (create-fstore!))
  (ha/-features fstore)
  (ha/-add! fstore "test"))
