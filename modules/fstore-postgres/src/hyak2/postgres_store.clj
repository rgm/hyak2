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
   [clojure.core.memoize  :as memo]
   [clojure.data.json     :as json]
   [clojure.tools.logging :refer [warn]]
   [hugsql.adapter.next-jdbc]
   [hugsql.core           :as hugsql]
   [hyak2.adapter         :as ha]
   [hyak2.platform        :as hp]
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

(defn- decode-feature-meta
  "Decode metadata off a feature-row, return as a map. The adapter may or may
   not have set up auto-deserialization of the JSON metadata column.

   See https://cljdoc.org/d/com.github.seancorfield/next.jdbc/1.3.1002/doc/getting-started/tips-tricks#working-with-json-and-jsonb"
  [feature-row]
  (let [raw-meta (:metadata feature-row)]
    (try
      (cond
        (map? raw-meta) ;; has already been decoded by something earlier on
        raw-meta

        (instance? org.postgresql.util.PGobject raw-meta) ;; is still a PGobject
        (json/read-str (.getValue raw-meta) :key-fn keyword)

        (string? raw-meta)
        (json/read-str raw-meta :key-fn keyword)

        :else
        (throw (ex-info "metadata decoding failure" {:feature-row feature-row})))
      (catch Exception ex
        (warn (ex-message ex))
        {}))))

(defn- feature-row->feature
  "Flatten an {:fkey :metadata} row into a flat map with expires-at parsed.

   Just decodes JSON manually (instead of setting up auto-convert via
   next.jdbc) so we don't mess with library consumers. "
  ([feature-row]
   (let [metadata (decode-feature-meta feature-row)
         ?expires-at (:expires-at metadata)]
     (if ?expires-at
       (let [expires-at (string->ldt ?expires-at)]
         (merge metadata {:fkey (:fkey feature-row)
                          :expires-at expires-at}))
       (merge metadata {:fkey (:fkey feature-row)})))))

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
   hug:delete-gate-for-fkey-key-val
   hug:delete-gate-for-fkey-key
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
                   {:key "actors" :value akey}))
       seq boolean))

(defn group-gate-open? [group-registry gates akey]
  (let [active-group? (->> gates
                           (filter #(= (:key %) "groups"))
                           (map :value)
                           (map keyword)
                           (into #{}))
        active-preds (keep (fn [[k v]] (when (active-group? k) v))
                           group-registry)]
    (when-not (empty? active-preds)
      ((apply some-fn active-preds) akey))))

(defn- pct-of-time-gate-open? [gate-values]
  (when-let [percent-str (some->> gate-values
                                  (filter #(= (:key %) "percentage_of_time"))
                                  first
                                  :value)]
    (< (rand) (/ (parse-long percent-str) 100.0))))

(defn pct-of-actors-gate-open? [gate-values akey]
  (when-let [percent-str (some->> gate-values
                                  (filter #(= (:key %) "percentage_of_actors"))
                                  first
                                  :value)]
    (let [scaling-factor 1000 ;; gives us a few more decimal places
          n (mod (hp/akey->n akey) (* 100 scaling-factor))]
      (< n (* (parse-long percent-str) scaling-factor)))))

(defn- enabled?
  "Is the feature enabled for the key?

   This is a separate function outside the protocol so we can use core.memoized
   on it if requested."
  [fstore fkey akey]
  (let [{:keys [table-prefix datasource]} fstore
        params (merge (make-names table-prefix)
                      {:fkey fkey})
        gates (hug:select-gates-for-fkey datasource params)]
    (or (pct-of-time-gate-open? gates)
        (pct-of-actors-gate-open? gates akey)
        (boolean-gate-open? gates)
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
      (hp/before? (:expires-at feature) now)))

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
                         :gate-type "actors"
                         :gate-value akey})]
      (hug:insert-gate datasource params)))

  (-disable-actor! [_ fkey akey]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "actors"
                         :gate-value akey})]
      (hug:delete-gate-for-fkey-key-val datasource params)))

  (-groups [_]
    (->> @*group-registry keys set))

  (-register-group! [_ gkey pred]
    (swap! *group-registry assoc gkey pred))

  (-unregister-group! [_ gkey]
    (swap! *group-registry dissoc gkey))

  (-enable-group! [_ fkey gkey]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "groups"
                         :gate-value (name gkey)})]
      (hug:insert-gate datasource params)))

  (-disable-group! [_ fkey gkey]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "groups"
                         :gate-value (name gkey)})]
      (hug:delete-gate-for-fkey-key-val datasource params)))

  (-enable-percentage-of-time! [_ fkey pct]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "percentage_of_time"
                         :gate-value (str pct)})]
      (doto datasource
        (hug:delete-gate-for-fkey-key params)
        (hug:insert-gate params))))

  (-disable-percentage-of-time! [_ fkey]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "percentage_of_time"})]
      (hug:delete-gate-for-fkey-key datasource params)))

  (-enable-percentage-of-actors! [_ fkey pct]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "percentage_of_actors"
                         :gate-value (str pct)})]
      (doto datasource
        (hug:delete-gate-for-fkey-key params)
        (hug:insert-gate params))))

  (-disable-percentage-of-actors! [_ fkey]
    (let [params (merge (make-names table-prefix)
                        {:fkey fkey
                         :gate-type "percentage_of_actors"})]
      (hug:delete-gate-for-fkey-key datasource params))))

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
