(ns hyak2.platform-test
  (:require
   [clojure.test   :as t :refer [deftest is]]
   [hyak2.platform :as sut]))

(deftest before?-test
  ;; ugh dates are easy to mess up, esp w nils
  (let [before? #'sut/before?
        present (sut/now)
        past (.minusHours present 1)
        future (.plusHours present 1)]
    (is (before? past present))
    (is (before? present future))
    (is (not (before? present past)))
    (is (not (before? future present)))
    (is (not (before? present nil)))
    (is (not (before? nil present)))
    (is (not (before? nil nil)))))
