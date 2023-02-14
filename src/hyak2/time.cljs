(ns hyak2.time)

(defn now []
  (js/Date.))

(defn default-expires-at []
  ;; opinion: a quarter year is as long as this stuff should usually live
  (js/Date. (+ (now) (* 1000 60 60 24 30 3))))

(defn before?
  "Compare two JavaScript `Date` objects."
  [a b]
  (< a b))
