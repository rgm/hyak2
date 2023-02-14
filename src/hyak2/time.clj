(ns hyak2.time
  "Encapsulate clj/cljs time handling differences."
  (:import [java.time LocalDateTime]))

(defn now []
  (LocalDateTime/now))

(defn default-expires-at []
  ;; opinion: a quarter year is as long as this stuff should usually live
  (.plusMonths (now) 3))

(defn before?
  "Compare 2 Java LocalDateTimes."
  [a b]
  (and a b (.isBefore a b)))
