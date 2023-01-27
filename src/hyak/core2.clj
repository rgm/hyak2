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

(defn add!
  "Add a feature."
  [fstore fkey]
  (ha/-add! fstore fkey)
  :added)

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
