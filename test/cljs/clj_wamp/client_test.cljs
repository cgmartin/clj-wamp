(ns clj-wamp.client-test
  (:require [clj-wamp.client :as client :refer [prefix!]]))

(defn run []
  (assert (= (+ 2 2) 4))
  (assert (= (+ 1 2 3) 6))
  (assert (= (+ 4 5 6) 15)))