(ns hyak.core2
  "
  A library for managing feature (aka. dark launch) flags.

  An in-memory store `hyak.memory-store` is provided in this library. You'll
  need either `hyak.postgres-store` or `hyak.redis-store` for a persistent
  store.
  "
  (:require
   [hyak.adapter :as ha]))

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

;; vi:fdm=marker
