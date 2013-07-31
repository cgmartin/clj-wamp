(ns clj-wamp.test
  (:require [clj-wamp.client-test :as client]))

(def success 0)

(defn ^:export run []
  (.log js/console "clj-wamp cljs test started.")
  (client/run)
  success)