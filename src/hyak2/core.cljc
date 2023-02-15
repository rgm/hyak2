(ns hyak2.core
  "
  A library for managing feature (aka. dark launch) flags.

  An in-memory store `hyak.memory-store` is provided in this library. You'll
  need either `hyak.postgres-store` or `hyak.redis-store` for a persistent
  store.
  "
  (:require
   #?(:clj [clojure.tools.logging :refer [warn]])
   [hyak2.adapter :as ha]
   [hyak2.time    :as ht]))

;; DRAMATIS PERSONAE:
;; fstore .................... Feature Store
;; fkey ...................... Feature Key, a string
;; akey ...................... Actor Key, a string
;; gkey ...................... Group Key, a string

;; TODO implement for cljs
#?(:cljs (defn warn [& _]))

(defn features [fstore]
  (->> fstore ha/-features (map :fkey) set))

(defn feature-exists? [fstore fkey]
  (contains? (features fstore) fkey))

(defn stale?
  "Does the fstore contain any expired features?"
  ([fstore]
   (stale? fstore (ht/now)))
  ([fstore now]
   (some->> (ha/-features fstore)
            (map :expires-at)
            (filter #(ht/before? % now))
            seq
            boolean)))

(defn add!
  "Add a feature."
  ([fstore fkey]
   (add! fstore fkey (ht/default-expires-at) nil))
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

(defn expired?
  "Has the feature expired?"
  ([fstore fkey] (expired? fstore fkey (ht/now)))
  ([fstore fkey now] (ha/-expired? fstore fkey now)))

(defn disable!
  "Disable a feature (ie. all gates) for the fkey."
  [fstore fkey]
  (ha/-disable! fstore fkey)
  :disabled)

(defn enabled?
  "Is the feature `fkey` enabled for actor `akey`?"
  ([fstore fkey]
   (enabled? fstore fkey nil))
  ([fstore fkey akey]
   (when (expired? fstore fkey)
     (warn (str "feature \"" fkey "\" has expired")))
   (ha/-enabled? fstore fkey akey)))

;; * boolean gate {{{

(defn enable!
  "Unconditionally enable a feature. Disables any other active gates since
   they're redundant when the boolean gate is open."
  [fstore fkey]
  (doto fstore
    (disable! fkey) ;; clear other gates
    (ha/-enable! fkey))
  :enabled-boolean)

;; }}}
;; * actor gate {{{

(defn enable-actor!
  "Enable a feature for a specific actor."
  [fstore fkey akey]
  (ha/-enable-actor! fstore fkey akey)
  :enabled-actor)

(defn disable-actor!
  "Enable a feature for a specific actor."
  [fstore fkey akey]
  (ha/-disable-actor! fstore fkey akey)
  :disabled-actor)

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
  (ha/-register-group! fstore gkey pred)
  :registered-group)

(defn unregister-groups!
  "Unregister all groups from the fstore."
  [fstore]
  (doseq [gkey (ha/-groups fstore)] (ha/-unregister-group! fstore gkey))
  :unregistered-groups)

(defn enable-group!
  "Enable a feature for a specific group."
  [fstore fkey gkey]
  (let [known-group? (ha/-groups fstore)]
    (when-not (known-group? gkey)
      (throw (ex-info "unknown-group" {:group-key gkey
                                       :known-groups known-group?}))))
  (ha/-enable-group! fstore fkey gkey)
  :enabled-group)

(defn disable-group!
  "Disable a feature for a specific group."
  [fstore fkey gkey]
  (ha/-disable-group! fstore fkey gkey)
  :disabled-group)

;; }}}
;; * percent of time gate {{{

;; percent of time gate is random and doesn't depend on akey. If
;; percent-of-time is 50, then for 100 calls to `enabled?` you should expect
;; about 50 to come back true. It won't be stable on akey.

(defn enable-percentage-of-time!
  "Enable a feature for a random % of calls to `enabled?` Supply a percentage
   on the interval [1, 99]. Disable for 0 and use the boolean gate for 100."
  [fstore fkey pct]
  {:pre [(integer? pct) (< 0 pct 100)]}
  (ha/-enable-percentage-of-time! fstore fkey pct))

(defn disable-percentage-of-time!
  "Disable a feature previously turned on for a percentage of time."
  [fstore fkey]
  (ha/-disable-percentage-of-time! fstore fkey))

;; }}}
;; * percent of actors gate {{{

;; percent of actors gate is stable for a given actor, ie. the specific set of
;; actors is random (based on hashing the akey to a number between 0 and 100).
;; The result of `enabled?` will be stable given the same akey.

(defn enable-percentage-of-actors! [fstore fkey pct]
  {:pre [(integer? pct) (< 0 pct 100)]}
  (ha/-enable-percentage-of-actors! fstore fkey pct))

(defn disable-percentage-of-actors! [fstore fkey]
  (ha/-disable-percentage-of-actors! fstore fkey))

;; }}}

;; vi:fdm=marker
