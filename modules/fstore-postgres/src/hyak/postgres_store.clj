(ns hyak.postgres-store
  (:require
   #_[hugsql.adapter.clojure-java-jdbc :as adp]
   #_[hugsql.core                      :as hugsql]
   [hyak.adapter                     :as ha]
   #_[next.jdbc                        :as jdbc]
   #_[migratus.core                    :as migratus]))

(defrecord FeatureStore [*tmp-state table-prefix jdbc-database-url]
  ha/IFStore
  (-features [_] (:features @*tmp-state))

  (-add! [_ fkey]
    (swap! *tmp-state update :features #(conj % fkey)))

  (-remove! [_ fkey]
    (swap! *tmp-state (fn [state] (-> state (update :features disj fkey))))))

(defn create-fstore!
  ([table-prefix jdbc-database-url]
   (create-fstore! table-prefix jdbc-database-url {}))
  ([table-prefix jdbc-database-url _opts]
   (->FeatureStore (atom {:features #{}}) table-prefix jdbc-database-url)))

(defn destroy-fstore! [_fstore]
  ;;TODO
  :destroyed)
