(ns clj-wamp.core-test
  (:use clojure.test
        clj-wamp.core))

(deftest clj-wamp-version-test
  (is (= "clj-wamp/0.1.0" clj-wamp-version)))

(deftest next-sess-id-test
  (is (= 1 (next-sess-id)))
  (is (= 2 (next-sess-id))))


