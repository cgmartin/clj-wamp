# clj-wamp

Clojure implementation of the WebSocket Application Messaging Protocol,
for HTTP Kit servers.

[![Build Status](https://travis-ci.org/cgmartin/clj-wamp.png?branch=master)](https://travis-ci.org/cgmartin/clj-wamp)

*Warning: Signature in flux, beta testing. Check back soon for a 1.0.0 release*

Visit [cljwamp.us](http://cljwamp.us) for live demos and additional information.
See [clj-wamp-example](https://github.com/cgmartin/clj-wamp-example) for an example project and source code.

For information on HTTP Kit, a Ring-compatible HTTP server for Clojure, visit [http-kit.org](http://http-kit.org/).

For information on the WAMP specification, visit [wamp.ws](http://wamp.ws).

## Usage

Add the following dependency to your `project.clj` file:
```clojure
[clj-wamp "0.4.2"]
```

Add clj-wamp's `http-kit-handler` to http-kit's `with-channel`, and attach to the server:

```clojure
(ns clj-wamp-example
  (:require [org.httpkit.server :as http-kit]
            [clj-wamp.server :as wamp]))

; Topic URIs
(defn rpc-url [path] (str "http://clj-wamp-example/api#"   path))
(defn evt-url [path] (str "http://clj-wamp-example/event#" path))

(defn wamp-websocket-handler
  "Returns a http-kit websocket handler with wamp subprotocol"
  [req]
  (http-kit/with-channel req channel
    (if (:websocket? req)
      (wamp/http-kit-handler channel
        {:on-open        on-open-fn     ; (fn [sess-id] ...)
         :on-close       on-close-fn    ; (fn [sess-id status] ...)

         :on-call        {(rpc-url "add")    rpc-add            ; RPC topic map (fn [sess-id & params] ...)
                          :on-before         on-before-call-fn  ; broker incoming params or
                                                                ; return false to restrict rpc access
                          :on-after-error    on-after-call-error-fn
                          :on-after-success  on-after-call-success-fn}

         :on-subscribe   {(evt-url "chat")     chat-subscribe? ; allowed to subscribe? (fn [sess-id topic] ...)
                          (evt-url "prefix*")  true            ; match topics by prefix
                          (evt-url "sub-only") true            ; implicitly allowed
                          (evt-url "pub-only") false           ; subscription is denied
                          :on-after            on-subscribe-fn};

         :on-publish     {(evt-url "chat")     chat-broker-fn  ; custom event broker
                                                               ; (fn [sess-id topic event exclude eligible] ...)
                          (evt-url "prefix*")  true            ; pass events through as-is
                          (evt-url "sub-only") false           ; publishing is denied
                          (evt-url "pub-only") true
                          :on-after            on-publish-fn}

         :on-unsubscribe on-unsubscribe-fn})
      (http-kit/close channel))))

(http-kit/run-server wamp-websocket-handler {:port 8080})
```

## License

Copyright © 2013 Christopher Martin

Distributed under the Eclipse Public License, the same as Clojure.
