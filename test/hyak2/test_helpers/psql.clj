(ns hyak2.test-helpers.psql
  "Deal with auto-serialization tips and tricks from next.jdbc.

   See https://cljdoc.org/d/com.github.seancorfield/next.jdbc/1.3.1002/doc/getting-started/tips-tricks#working-with-json-and-jsonb

   Problem: this advice proposes extending IPersistentMap, IPersistentVector
   and org.postgresql.util.PGobject. This happens at the whole-JVM level until
   restart, but I want test cases that exercise both the possibility that the
   extension has and hasn't happened.

   Solution: set a dynamic var `*use-psql-auto-serialization?*` that we can
   flip per-test with `bind` and check it in `x->json` and `json->x` to no-op
   when I need to, or handle json (de-)serialization when I don't."
  (:require
   [jsonista.core        :as json]
   [next.jdbc.prepare    :as prepare]
   [next.jdbc.result-set :as rs])
  (:import
   (java.sql            PreparedStatement)
   (org.postgresql.util PGobject)))

(def ^:dynamic *use-psql-auto-serialization?* false)

(def x->json (fn [x]
               (if *use-psql-auto-serialization?*
                 (json/write-value-as-string x)
                 x)))

(def json->x (let [mapper (json/object-mapper {:decode-key-fn keyword})]
               (fn [json-str-or-x]
                 (if *use-psql-auto-serialization?*
                   (json/read-value json-str-or-x mapper)
                   json-str-or-x))))

(defn clj->pgobject
  "Transforms Clojure data to a PGobject that contains the data as JSON.
   PGObject type defaults to `jsonb` but can be changed via metadata key
   `:pgtype`"
  [x]
  (let [pgtype (or (:pgtype (meta x)) "jsonb")]
    (doto (PGobject.)
      (.setType pgtype)
      (.setValue (x->json x)))))

(defn pgobject->clj
  "Transform PGobject containing `json` or `jsonb` value to Clojure data."
  [^org.postgresql.util.PGobject v]
  (let [pg-type         (.getType v)
        raw-value       (.getValue v)
        value           (if (#{"jsonb" "json"} pg-type)
                          (json->x raw-value)
                          raw-value)
        value-with-meta (if (instance? clojure.lang.IObj value)
                          (with-meta value {:pgtype pg-type})
                          value)]
    value-with-meta))

(defn init-json-write-helper []
  ;; if a SQL parameter is a Clojure hash map or vector, it'll be transformed
  ;; to a PGobject for JSON/JSONB:
  (extend-protocol next.jdbc.prepare/SettableParameter
    clojure.lang.IPersistentMap
    (set-parameter [m ^PreparedStatement s i]
      (.setObject s i (clj->pgobject m)))

    clojure.lang.IPersistentVector
    (set-parameter [v ^PreparedStatement s i]
      (.setObject s i (clj->pgobject v)))))

(defn init-json-read-helper []
  ;; if a row contains a PGobject then we'll convert them to Clojure data
  ;; while reading (if column is either "json" or "jsonb" type):
  (extend-protocol next.jdbc.result-set/ReadableColumn
    org.postgresql.util.PGobject
    (read-column-by-label [^org.postgresql.util.PGobject v _]
      (pgobject->clj v))
    (read-column-by-index [^org.postgresql.util.PGobject v _2 _3]
      (pgobject->clj v))))

(defn extend-psql-for-auto-serialization! []
  (init-json-read-helper)
  (init-json-write-helper))

;; extend the relevant next.jdbc protocols unconditionally in test, and use the
;; *use-psql-auto-serialization?* dynamic var to opt in and out with `(bind [,,,])`

(extend-psql-for-auto-serialization!)
