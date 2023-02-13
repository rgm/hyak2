(ns hyak2.postgres-store
  "A postgres persistent feature (dark launch) store. Follows the same data format
   as https://github.com/jnunemaker/flipper so that that project's UI tools for
   the ActiveRecord adapter for managing feature state should work for the base
   state. (It won't work for the expiry and author data; these are hyak
   extensions)."
  (:require
   [clojure.data.json :as json]
   [hugsql.adapter.next-jdbc]
   [hugsql.core       :as hugsql]
   [hyak2.adapter     :as ha]
   [next.jdbc]))

;; * db access {{{

(let [opts {:adapter (hugsql.adapter.next-jdbc/hugsql-adapter-next-jdbc)
            :quoting :ansi}]
  (declare
   hug:clean-features-table
   hug:clean-gates-table
   hug:create-features-index
   hug:create-features-table
   hug:create-gates-index
   hug:create-gates-table
   hug:delete-feature
   hug:delete-gates-for-fkey
   hug:drop-features-index
   hug:drop-features-table
   hug:drop-gates-index
   hug:drop-gates-table
   hug:insert-gate
   hug:select-features
   hug:select-gates-for-fkey
   hug:upsert-feature)
  (hugsql/def-db-fns "sql/queries.sql" opts))

(defn- make-names [table-prefix]
  ;; helper to make up hug params for prefixed table names
  (let [prefix #(str table-prefix %)]
    {:features-table-name (prefix "flipper_features")
     :features-index-name (prefix "flipper_features_index")
     :gates-table-name    (prefix "flipper_gates")
     :gates-index-name    (prefix "flipper_gates_index")}))

(defn create-tables!
  "Create tables used for feature persistence. Provide a `table-prefix` to
   avoid table naming collisions."
  [table-prefix datasource]
  (let [names (make-names table-prefix)]
    (doto datasource
      (hug:create-features-table names)
      (hug:create-features-index names)
      (hug:create-gates-table names)
      (hug:create-gates-index names)))
  :created)

(defn clean-tables!
  [table-prefix datasource]
  (let [names (make-names table-prefix)]
    (doto datasource
      (hug:clean-features-table names)
      (hug:clean-gates-table names)))
  :cleaned
  )

(defn drop-tables!
  "Drop tables used for feature persistence. See
   `hyak.postgres-stores/create-tables` for the meaning/use of `table-prefix`."
  [table-prefix datasource]
  (let [names (make-names table-prefix)]
    (doto datasource
      (hug:drop-gates-index names)
      (hug:drop-gates-table names)
      (hug:drop-features-index names)
      (hug:drop-features-table names)))
  :dropped)

;; }}}

(defn boolean-gate-open? [table-prefix datasource fkey _]
  (let [params (merge (make-names table-prefix)
                      {:fkey fkey})]
    (->> params
         (hug:select-gates-for-fkey datasource)
         (filter #(= (select-keys % [:key :value])
                       {:key "boolean" :value "true"}))
         seq boolean)))

(defrecord FeatureStore [table-prefix datasource]
  ha/IFStore
  (-features [_]
    (let [params (make-names table-prefix)
          rows   (hug:select-features datasource params)]
      (into #{} (map :key) rows)))

  (-add! [_ fkey expires-at author]
    (let [row {:key      fkey
               :metadata (json/write-str {:expires-at (str expires-at)
                                          :author author})}
          params (merge (make-names table-prefix) row)]
      (hug:upsert-feature datasource params)))

  (-remove! [_ fkey]
    (let [params (merge (make-names table-prefix) {:key fkey})]
      (hug:delete-feature datasource params)))

  (-disable! [_ fkey]
    (let [params (merge (make-names table-prefix) {:fkey fkey})]
      (hug:delete-gates-for-fkey datasource params)))

  (-enabled? [_ fkey akey]
    (boolean-gate-open? table-prefix datasource fkey akey))

  (-enable! [_ fkey]
    (let [params (merge (make-names table-prefix)
                        {:fkey       fkey
                         :gate-type  "boolean"
                         :gate-value "true"})]
      (hug:insert-gate datasource params)
      (prn (hug:select-features datasource (make-names table-prefix))))))

(defn create-fstore!
  "Create a postgres feature store. By default reuses any existing data in the
   tables `table_prefix_flipper_features` and `table_prefix_flipper_gates`.

   Supply opts `{:recreate-tables? true}` to start fresh in the database.

   Supply opts `{:clear-tables? true}` to reuse existing tables but clear
   existing table data.
   "
  ([table-prefix jdbc-database-url]
   (create-fstore! table-prefix jdbc-database-url {}))
  ([table-prefix jdbc-database-url opts]
   (when (:recreate-tables? opts)
     (drop-tables! table-prefix jdbc-database-url))
   (create-tables! table-prefix jdbc-database-url)
   (when (:clean-tables? opts)
     (clean-tables! table-prefix jdbc-database-url))
   (->FeatureStore table-prefix jdbc-database-url)))

(defn destroy-fstore! [{:keys [table-prefix datasource] :as _fstore}]
  (drop-tables! table-prefix datasource)
  :destroyed)
