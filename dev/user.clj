(ns user
  (:require
   [portal.api :as p]))

(def *p (atom nil))

(defn start-portal []
  (add-tap #'p/submit)
  (reset! *p (p/open)))

(defn stop-portal []
  (remove-tap #'p/submit)
  (reset! *p nil)
  (p/close))

(comment
  (start-portal)
  (stop-portal)
  (p/clear)
  (p/docs))
