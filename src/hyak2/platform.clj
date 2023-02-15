(ns hyak2.platform
  "Isolate platform-specific code."
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

(defn akey->n [akey]
  (let [crc (doto (new java.util.zip.CRC32)
              (.update (.getBytes akey)))]
    (.getValue crc)))

