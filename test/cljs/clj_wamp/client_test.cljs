(ns clj-wamp.client-test
  (:require-macros [cemerick.cljs.test :refer (is deftest are testing)])
  (:require [cemerick.cljs.test :as t]
            [clj-wamp.client :as client :refer [prefix!]]))

(deftest add-test
  (is (= (+ 2 2) 4))
  (is (= (+ 1 2 3) 6))
  (is (= (+ 4 5 6) 15)))

(deftest somewhat-less-wat
  (is (= "{}[]" (+ {} []))))

(deftest javascript-allows-div0
  (is (= js/Infinity (/ 1 0) (/ (int 1) (int 0)))))