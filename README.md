# clj-wamp #

The WebSocket Application Messaging Protocol for Clojure and ClojureScript.

In a nutshell, the WAMP spec provides some common WebSocket messaging patterns
(RPC, PubSub, and Authentication). See [wamp.ws](http://wamp.ws) for more information.

The clj-wamp library contains client/server WAMP WebSockets in Clojure:
 * a Clojure WAMP server handler for [HTTP-Kit](http://http-kit.org/).
 * a ClojureScript WAMP browser client library.

[![Build Status](https://travis-ci.org/cgmartin/clj-wamp.png?branch=master)](https://travis-ci.org/cgmartin/clj-wamp)

Visit [cljwamp.us](http://cljwamp.us) for live demos, tutorials, and additional information.

## Usage ##

Create a new WAMP client/server starter project using the `clj-wamp` leiningen project template:
```bash
lein new clj-wamp my-project
```

...or add the following dependency to your existing `project.clj` file:
```clojure
[clj-wamp "1.1.0-SNAPSHOT"]
```

### Clojure WAMP Server ###

Simply run clj-wamp's `http-kit-handler` within HTTP-Kit's `with-channel` context
and expose some RPC functions and PubSub channels:
```clojure
(ns clj-wamp-example
  (:require [org.httpkit.server :as http-kit]
            [clj-wamp.server :as wamp]))

; Topic URIs
(defn rpc-url [path] (str "http://clj-wamp-example/api#"   path))
(defn evt-url [path] (str "http://clj-wamp-example/event#" path))

(defn my-wamp-handler
  "Returns a http-kit websocket handler with wamp subprotocol"
  [request]
  (wamp/with-channel request channel
    (wamp/http-kit-handler channel
      {:on-call        {(rpc-url "add")   +     ; Expose plain old functions as RPC
                        (rpc-url "echo")  identity}

       :on-subscribe   {(evt-url "chat")  true} ; Expose PubSub channels
       :on-publish     {(evt-url "chat")  true}})))

(http-kit/run-server my-wamp-handler {:port 8080})
```

An example with *all* configuration options:
```clojure
(def origin-re #"https?://myhost") ; origin header validation

(defn my-wamp-handler
  "Alternatively, with-channel-validation can do wamp subprotocol and origin validation"
  [request]
  (wamp/with-channel-validation request channel origin-re
    (wamp/http-kit-handler channel
      {:sess-id        gen-sess-id-fn      ; generate custom session ids
       :on-open        on-open-fn
       :on-close       on-close-fn

       :on-call        {(rpc-url "add")    +                  ; map topics to RPC fn calls
                        (rpc-url "echo")   identity
                        :on-before         on-before-call-fn  ; broker incoming params or
                                                              ; return false to deny access                         :on-after-error    on-after-call-error-fn
                        :on-after-success  on-after-call-success-fn
                        :on-after-error    on-after-call-error-fn}

       :on-subscribe   {(evt-url "chat")     chat-subscribe?  ; allowed to subscribe?
                        (evt-url "prefix*")  true             ; match topics by prefix
                        (evt-url "sub-only") true             ; implicitly allowed
                        (evt-url "pub-only") false            ; subscription is denied
                        :on-after            on-subscribe-fn }

       :on-publish     {(evt-url "chat")     chat-broker-fn   ; custom event broker
                        (evt-url "prefix*")  true             ; pass events through as-is
                        (evt-url "sub-only") false            ; publishing is denied
                        (evt-url "pub-only") true
                        :on-after            on-publish-fn }

       :on-unsubscribe on-unsubscribe-fn

       ; challenge-response authentication hooks
       :on-auth        {:allow-anon? true                ; allow anonymous authentication?
                        :secret      auth-secret-fn      ; retrieve the auth key's secret
                        :permissions auth-permissions-fn ; return the permissions for a key
                        :timeout     20000}})))          ; close the connection if not auth'd
```

See [the docs](http://cljwamp.us/doc/index.html) for more information on the API and callback signatures.

### ClojureScript WAMP Client ###

To connect to a WAMP server, call the `wamp-handler` with open, close, and event callbacks:
```clojure
(ns cljs-wamp-example
  (:require [clj-wamp.client :as wamp :refer [wamp-handler]]))

(defn on-open [ws sess-id]
  (.log js/console "WAMP connected" sess-id)

  (wamp/prefix! ws "event" "http://clj-wamp-cljs/event#")
  (wamp/prefix! ws "rpc"   "http://clj-wamp-cljs/rpc#")

  ; PubSub
  (wamp/subscribe! ws "event:chat")
  (wamp/publish! ws "event:chat" "foo")
  (wamp/publish! ws "event:chat" {:send "complex" :clj "data"})

  ; RPC
  (wamp/rpc! ws "rpc:echo" ["test"]
    (fn [ws success? result]
      (.log js/console "rpc:echo RECEIVED" success? (pr-str result)))))

(defn on-event [ws topic event]
  (case topic
    "http://clj-wamp-cljs/event#chat"
    (.log js/console "event:chat RECEIVED" (pr-str event))))

(defn on-close []
  (.log js/console "WAMP disconnected"))

(defn ^:export init []
  (if-let [ws (wamp-handler "ws://localhost:8080/ws"
                {:on-open  on-open
                 :on-close on-close
                 :on-event on-event})]
    (.addEventListener js/window "unload" #(wamp/close! ws))
    (.log js/console "WAMP client init failed")))
```

By default, the `wamp-handler` client will auto-reconnect with an exponential back-off.
This behavior can be adjusted with the following reconnect options:
```clojure
(def ws (wamp-handler "ws://localhost:8080/ws"
          {:reconnect? true              ; false to disable reconnects
           :next-reconnect (fn [n] 5000) ; milliseconds till next attempt
           ; ...
           }))
```
## Change Log

[CHANGES.md](https://github.com/cgmartin/clj-wamp/blob/master/CHANGES.md)

## Contributions

Pull requests are most welcome!

## License

Copyright © 2013 Christopher Martin

Distributed under the Eclipse Public License, the same as Clojure.
