(ns example)

(require '[hyak2.core :as hyak])
(require '[hyak2.memory-store :as mem]) ;; or redis-store, or postgres-store

;; an in-memory store is useful for tests or browsers, but can't easily share
;; across multiple appservers ... use the redis or postgres fstore for that.
(def fstore (mem/create-fstore!))

;; fake users aka. actors for the gates below. The representation of an actor
;; is up to you. Consider what you'll use for an "actor key" aka. `akey` in
;; your enabled checks
(def enabled-actor  {:id 1000 :username "willy"})
(def disabled-actor {:id 1001 :username "shamu"})

(def MY-FEATURE-FLAG "my_great_new_feature")
(hyak/add! fstore MY-FEATURE-FLAG)

;; in application code a feature check is simply a call to `hyak/enabled?`.
;; Write the code so that it fails "safe." If the feature store is missing or
;; unresponsive, the *unreleased* branch should execute.

(if (hyak/enabled? fstore MY-FEATURE-FLAG enabled-actor)
  (prn "doing the new thing")
  (prn "doing the old thing"))

;; hyak uses a concept of 5 different kinds of "gates" to determine whether a
;; feature is enabled or not.

;; 1. boolean gate
;;
;; Feature is either on or off for all users, but checked at runtime.

(hyak/enable! fstore  MY-FEATURE-FLAG)
(hyak/enabled? fstore MY-FEATURE-FLAG) ;; => true
(hyak/disable! fstore MY-FEATURE-FLAG)

;; 2. actor gate
;;
;; Is a actor's identifier in a set of users who get the feature? Good for
;; previewing features to known users.

(hyak/enable-actor! fstore MY-FEATURE-FLAG 1001)
(hyak/enabled? fstore MY-FEATURE-FLAG (:id enabled-actor))  ;; => true
(hyak/enabled? fstore MY-FEATURE-FLAG (:id disabled-actor)) ;; => false
(hyak/disable! fstore MY-FEATURE-FLAG)

;; 3. percentage of actors gate
;;
;; Is the actor in a (deterministic) partition of n % of the users? (we use
;; `mod 100` on a numeric checksum of the supplied actor identifier, so an
;; actor in the enabled set will stay in the set over successive calls). Good
;; for gradually rolling out UI features.

(hyak/enable-percentage-of-actors! fstore MY-FEATURE-FLAG 50)
(hyak/enabled? fstore MY-FEATURE-FLAG (:username enabled-actor))  ;; => true
(hyak/enabled? fstore MY-FEATURE-FLAG (:username disabled-actor)) ;; => false
(hyak/disable! fstore MY-FEATURE-FLAG)

;; 4. percentage of time gate
;;
;; Randomly make some percentage of all requests enable the feature, without
;; regard for the actor. Good for gradually bringing up load on backend
;; features that tax the system, but where we otherwise don't care who uses it.

(hyak/enable-percentage-of-time! fstore MY-FEATURE-FLAG 10)
(let [tries 1000]
  (/ (count (filter true? (repeatedly tries #(hyak/enabled? fstore MY-FEATURE-FLAG))))
     tries)) ;; => should expect somewhere near 0.1
(hyak/disable! fstore MY-FEATURE-FLAG)

;; 5. group gate
;;
;; Is an arbitrary predicate true for the actor? Good for defining eg. admin
;; users, or a set of beta testers. Unlike the other gates, this one requires
;; registering code on the fstore to establish the group, so probably requires
;; a re-deploy to adjust the group predicate. It's just a function, though, so
;; you could supply one closed over a more dynamic data source to eg. respond
;; to the contents of a redis key at runtime.

(def in-group-actor {:id 1002 :username "tahlequah" :in-group? true})
(defn in-group? [actor] (:in-group? actor))
(hyak/register-group! fstore "group-name" in-group?)

(hyak/enable-group! fstore MY-FEATURE-FLAG "group-name")
(hyak/enabled? fstore MY-FEATURE-FLAG enabled-actor)     ;; => false
(hyak/enabled? fstore MY-FEATURE-FLAG disabled-actor)    ;; => false
(hyak/enabled? fstore MY-FEATURE-FLAG in-group-actor)    ;; => true
