(defproject clj-wamp "1.1.0-SNAPSHOT"
  :description "The WebSocket Application Messaging Protocol for Clojure"
  :url "https://github.com/cgmartin/clj-wamp"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :plugins [[lein-cljsbuild "0.3.2"]]
  :hooks [leiningen.cljsbuild]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.incubator "0.1.2"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/data.codec "0.1.0"]
                 [http-kit "2.1.5"]
                 [cheshire "5.2.0"]
                 [com.cemerick/clojurescript.test "0.0.4"]]
  :cljsbuild {:builds {:dev {:source-paths ["src/cljs"]
                             :jar true
                             :compiler {:output-to "target/cljs/cljwamp.js"}}
                       :test-whitespace {:source-paths ["src/cljs" "test/cljs"]
                              :compiler {:optimizations :whitespace
                                         :pretty-print true
                                         :output-to "target/cljs/whitespace-test.js"}}
                       :test-advanced {:source-paths ["src/cljs" "test/cljs"]
                              :compiler {:optimizations :advanced
                                         :pretty-print false
                                         :externs ["lib/cryptojs-externs.js"]
                                         :output-to "target/cljs/advanced-test.js"}}}
              :test-commands {"phantom-whitespace"
                              ["runners/phantomjs.js" "lib/hmac-sha256.js" "lib/enc-base64.js"
                               "target/cljs/whitespace-test.js"]
                              "phantom-advanced"
                              ["runners/phantomjs.js" "lib/hmac-sha256.js" "lib/enc-base64.js"
                               "target/cljs/advanced-test.js"]}}
  :profiles {:1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :dev {:dependencies [[log4j "1.2.17" :exclusions [javax.mail/mail
                                                               javax.jms/jms
                                                               com.sun.jdmk/jmxtools
                                                               com.sun.jmx/jmxri]]]}})
