(ns hyak2.core
  "
  A library for managing feature (aka. dark launch) flags.

  An in-memory store `hyak.memory-store` is provided in this library. You'll
  need either `hyak.postgres-store` or `hyak.redis-store` for a persistent
  store.
  "
  (:require
   [hyak2.adapter :as ha]))

;; DRAMATIS PERSONAE:
;; fstore .................... Feature Store
;; fkey ...................... Feature Key, a string
;; akey ...................... Actor Key, a string
;; gkey ...................... Group Key, a string

(defn features [fstore]
  (ha/-features fstore))

(defn exists? [fstore fkey]
  (tap> (features fstore))
  (contains? (features fstore) fkey))

(defn- default-expires-at []
  ;; opinion: a quarter year is as long as this stuff should usually live
  (.plusMonths (java.time.LocalDateTime/now) 3))

(defn add!
  "Add a feature."
  ([fstore fkey]
   (add! fstore fkey (default-expires-at) nil))
  ([fstore fkey expires-at]
   (add! fstore fkey expires-at nil))
  ([fstore fkey expires-at author]
   (ha/-add! fstore fkey expires-at author)
   :added))

(defn remove!
  "Remove a feature entirely."
  [fstore fkey]
  (ha/-remove! fstore fkey)
  :removed)

(defn disable!
  "Disable a feature (ie. all gates) for the fkey."
  [fstore fkey]
  (ha/-disable! fstore fkey))

(defn enabled?
  "Is the feature `fkey` enabled for actor `akey`?"
  ([fstore fkey]
   (enabled? fstore fkey nil))
  ([fstore fkey akey]
   (ha/-enabled? fstore fkey akey)))

;; * boolean gate {{{

(defn enable!
  "Unconditionally enable a feature. Disables any other active gates since
   they're redundant when the boolean gate is open."
  [fstore fkey]
  (doto fstore
    (disable! fkey) ;; clear other gates
    (ha/-enable! fkey)))

;; }}}
;; * actor gate {{{

(defn enable-actor!
  "Enable a feature for a specific actor."
  [fstore fkey akey]
  (ha/-enable-actor! fstore fkey akey))

(defn disable-actor!
  "Enable a feature for a specific actor."
  [fstore fkey akey]
  (ha/-disable-actor! fstore fkey akey))

;; }}}
;; * group gate {{{

(defn register-group!
  "Register a predicate `f :: akey -> Boolean` that can give a verdict on
   whether a feature is enabled for a given akey.

   A group is one order removed from an actor. This gate could be simulated by
   a set of actor gates, but it would be tedious. Instead:

   ```
   (register-group! fstore \"beta-testers\" #{\"a@example.org\" \"b@other.com\")
   (enable-group! fstore \"new-feature\" \"beta-testers\")
   ```

   The persistence layer for the fstores doesn't contain executable code, so
   the groups have to be registered as part of app startup. It can be hard to
   avoid having to re-deploy to adjust the group membership predicates so
   assume these are fairly stable, or provide a predicate that is closed over
   some more dynamic store like Redis if that's not flexible enough."
  [fstore gkey pred]
  {:pre [(ifn? pred)]}
  (ha/-register-group! fstore gkey pred))

(defn unregister-groups!
  "Unregister all groups from the fstore."
  [fstore]
  (doseq [gkey (ha/-groups fstore)] (ha/-unregister-group! fstore gkey)))

(defn enable-group!
  "Enable a feature for a specific group."
  [fstore fkey gkey]
  (let [known-group? (ha/-groups fstore)]
    (when-not (known-group? gkey)
      (throw (ex-info "unknown-group" {:group-key gkey
                                       :known-groups known-group?}))))
  (ha/-enable-group! fstore fkey gkey))

(defn disable-group!
  "Disable a feature for a specific group."
  [fstore fkey gkey]
  (ha/-disable-group! fstore fkey gkey))

;; }}}

;; vi:fdm=marker
