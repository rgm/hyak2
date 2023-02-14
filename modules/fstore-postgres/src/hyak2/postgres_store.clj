(ns hyak2.postgres-store
  "A postgres persistent feature (dark launch) store. Follows the same data
   format as https://github.com/jnunemaker/flipper so that that project's UI
   tools for the ActiveRecord adapter for managing feature state should work
   for the base state. (It won't work for the expiry and author data; these are
   hyak extensions).

   Because feature lookups can be frequent and some (like the boolean flag) are
   highly cacheable, there is optional caching provided by clojure.core.memoize
   to reduce the load on the database."
  (:require
   [clojure.core.memoize :as memo]
   [clojure.data.json    :as json]
   [hugsql.adapter.next-jdbc]
   [hugsql.core          :as hugsql]
   [hyak2.adapter        :as ha]
   [hyak2.time           :as ht]
   [next.jdbc])
  (:import
   (java.time Instant LocalDateTime ZoneOffset)))

;; * db access {{{

(defn- ldt->string [ldt]
  (when ldt
    (let [inst (.toInstant (.atZone ldt ZoneOffset/UTC))]
      (.toString inst))))

(defn- string->ldt [s]
  (when s
    (let [inst (Instant/parse s)]
      (LocalDateTime/ofInstant inst ZoneOffset/UTC))))

(defn- feature-row->feature
  "Flatten an {:fkey :metadata} row into a flat map with expires-at parsed.

   Just decodes JSON manually (instead of setting up auto-convert via
   next.jdbc) so we don't mess with library consumers. "
  [feature-row]
  (let [raw-meta (.getValue (:metadata feature-row))
        metadata (json/read-str raw-meta :key-fn keyword)
        ?expires-at (:expires-at metadata)]
    (if ?expires-at
      (let [expires-at (string->ldt ?expires-at)]
        (merge metadata {:fkey (:fkey feature-row)
                         :expires-at expires-at}))
      (merge metadata {:fkey (:fkey feature-row)}))))

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
   hug:delete-gate
   hug:delete-gates-for-fkey
   hug:drop-features-index
   hug:drop-features-table
   hug:drop-gates-index
   hug:drop-gates-table
   hug:insert-gate
   hug:select-features
   hug:select-feature
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
  :cleaned)

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

(defn boolean-gate-open? [gates]
  (->> gates
       (filter #(= (select-keys % [:key :value])
                   {:key "boolean" :value "true"}))
       seq boolean))

(defn actor-gate-open? [gates akey]
  (->> gates
       (filter #(= (select-keys % [:key :value])
                   {:key "actor" :value akey}))
       seq boolean))

(defn group-gate-open? [group-registry gates akey]
  (let [active-group? (->> gates
                           (filter #(= (:key %) "group"))
                           (map :value)
                           (map keyword)
                           (into #{}))
        active-preds (keep (fn [[k v]] (when (active-group? k) v))
                           group-registry)]
    (when-not (empty? active-preds)
      ((apply some-fn active-preds) akey))))

(defn- enabled?
  "Is the feature enabled for the key?

   This is a separate function outside the protocol so we can use core.memoized
   on it if requested."
  [fstore fkey akey]
  (let [{:keys [table-prefix datasource]} fstore
        params (merge (make-names table-prefix)
                      {:fkey fkey})
        gates (hug:select-gates-for-fkey datasource params)]
    (or (boolean-gate-open? gates)
        (actor-gate-open? gates akey)
        (group-gate-open? @(:*group-registry fstore) gates akey))))

(defn- memoize-fn
  "Generate a (possibly) TTL-memoized version of `f`.

   See https://github.com/clojure/core.memoize/blob/master/src/main/clojure/clojure/core/memoize.clj#L450 for more detail on TTL cache strategy. TTL is probably the only day-to-day useful one."
  [f ttl-threshold-msec]
  (if ttl-threshold-msec (memo/ttl f :ttl/threshold ttl-threshold-msec) f))

(defrecord FeatureStore [table-prefix datasource enabled-pred *group-registry]
  ha/IFStore
  (-features [_]
    (let [params (make-names table-prefix)]
      (map feature-row->feature (hug:select-features datasource params))))

  (-add! [_ fkey expires-at author]
    (let [row {:fkey     fkey
               :metadata (json/write-str {:expires-at (ldt->string expires-at)
                                          :author author})}
          params (merge (make-names table-prefix) row)]
      (hug:upsert-feature datasource params)))

  (-remove! [_ fkey]
    (let [params (merge (make-names table-prefix) {:fkey fkey})]
      (hug:delete-feature datasource params)))

  (-expired? [_ fkey now]
    (let [params  (merge (make-names table-prefix) {:fkey fkey})
          feature (some-> (hug:select-feature datasource params) feature-row->feature)]
      (ht/before? (:expires-at feature) now)))

  (-disable! [_ fkey]
    (let [params (merge (make-names table-prefix) {:fkey fkey})]
      (hug:delete-gates-for-fkey datasource params)))

  (-enabled? [fstore fkey akey]
    (enabled-pred fstore fkey akey))

  (-enable! [_ fkey]
    (let [params (merge (make-names table-prefix)
                        {:fkey       fkey
                         :gate-type  "boolean"
                         :gate-value "true"})]
      (hug:insert-gate datasource params)))

  (-enable-actor! [_ fkey akey]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "actor"
                         :gate-value akey})]
      (hug:insert-gate datasource params)))

  (-disable-actor! [_ fkey akey]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "actor"
                         :gate-value akey})]
      (hug:delete-gate datasource params)))

  (-groups [_]
    (->> @*group-registry keys set))

  (-register-group! [_ gkey pred]
    (swap! *group-registry assoc gkey pred))

  (-unregister-group! [_ gkey]
    (swap! *group-registry dissoc gkey))

  (-enable-group! [_ fkey gkey]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "group"
                         :gate-value (name gkey)})]
      (hug:insert-gate datasource params)))

  (-disable-group! [_ fkey gkey]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "group"
                         :gate-value (name gkey)})]
      (hug:delete-gate datasource params))))

(defn create-fstore!
  "Create a postgres feature store. By default reuses any existing data in the
   tables `table_prefix_flipper_features` and `table_prefix_flipper_gates`.

   Supply opts `{:recreate-tables? true}` to start fresh in the database.

   Supply opts `{:clear-tables? true}` to reuse existing tables but clear
   existing table data.

   Supply opts `{:ttl/threshold msec}` to memoize the `enabled?` call to
   reduce db load, at the cost of the TTL's delay in a feature's activation."
  ([table-prefix jdbc-database-url]
   (create-fstore! table-prefix jdbc-database-url {}))
  ([table-prefix jdbc-database-url opts]
   (when (:recreate-tables? opts)
     (drop-tables! table-prefix jdbc-database-url))
   (create-tables! table-prefix jdbc-database-url)
   (when (:clean-tables? opts)
     (clean-tables! table-prefix jdbc-database-url))
   (->FeatureStore table-prefix
                   jdbc-database-url
                   (memoize-fn enabled? (:ttl/threshold opts))
                   (atom {}))))

(defn destroy-fstore! [{:keys [table-prefix datasource] :as _fstore}]
  (drop-tables! table-prefix datasource)
  :destroyed)

;; vi:fdm=marker
