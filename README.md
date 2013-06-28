# clj-wamp

Clojure implementation of the WebSocket Application Messaging Protocol,
for HTTP Kit servers.

[![Build Status](https://travis-ci.org/cgmartin/clj-wamp.png?branch=master)](https://travis-ci.org/cgmartin/clj-wamp)

**Warning: Immature / In beta**

Visit [cljwamp.us](http://cljwamp.us) for live demos and additional information.
See [clj-wamp-example](https://github.com/cgmartin/clj-wamp-example) for an example project and source code.

For information on HTTP Kit, a Ring-compatible HTTP server for Clojure, visit [http-kit.org](http://http-kit.org/).

For information on the WAMP specification, visit [wamp.ws](http://wamp.ws).

## Usage

Add the following dependency to your `project.clj` file:
```clojure
[clj-wamp "1.0.0-beta3"]
```

Run clj-wamp's http-kit-handler within http-kit's with-channel context:

```clojure
(ns clj-wamp-example
  (:require [org.httpkit.server :as http-kit]
            [clj-wamp.server :as wamp]))

; Topic URIs
(defn rpc-url [path] (str "http://clj-wamp-example/api#"   path))
(defn evt-url [path] (str "http://clj-wamp-example/event#" path))

(def origin-re #"https?://myhost")

(defn my-wamp-handler
  "Returns a http-kit websocket handler with wamp subprotocol"
  [request]
  (wamp/with-channel-validation request channel origin-re
    (wamp/http-kit-handler channel
      ; Here be dragons...
      {:on-open        on-open-fn
       :on-close       on-close-fn

       :on-call        {(rpc-url "add")    +                       ; map topics to RPC fn calls
                        (rpc-url "echo")   identity
                        :on-before         on-before-call-fn       ; broker incoming params or
                                                                   ; return false to deny access                         :on-after-error    on-after-call-error-fn
                        :on-after-success  on-after-call-success-fn }

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

       :on-unsubscribe on-unsubscribe-fn })))

(http-kit/run-server my-wamp-handler {:port 8080})
```

See [the docs](http://cljwamp.us/doc/index.html) for more information on the PubSub API and callback signatures.

## Change Log

[CHANGES.md](https://github.com/cgmartin/clj-wamp/blob/master/CHANGES.md)

## License

Copyright © 2013 Christopher Martin

Distributed under the Eclipse Public License, the same as Clojure.
