(defproject clj-wamp "0.3.0"
  :description "The WebSocket Application Messaging Protocol for Clojure"
  :url "https://github.com/cgmartin/clj-wamp"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.incubator "0.1.2"]
                 [org.clojure/tools.logging "0.2.6"]
                 [http-kit "2.1.3"]
                 [cheshire "5.2.0"]]
  :profiles {:1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}})
