(ns clj-wamp.server
  ^{:author "Christopher Martin"
    :doc "Clojure implementation of the WebSocket Application Messaging Protocol"}
  (:use [clojure.core.incubator :only [dissoc-in]]
        [clojure.string :only [split trim lower-case]])
  (:require [clojure.java.io :as io]
            [org.httpkit.server :as httpkit]
            [org.httpkit.timer :as timer]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.data.codec.base64 :as base64])
  (:import [org.httpkit.server AsyncChannel]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(declare send!)

(def ^:const TYPE-ID-WELCOME     0) ; Server-to-client (Aux)
(def ^:const TYPE-ID-PREFIX      1) ; Client-to-server (Aux)
(def ^:const TYPE-ID-CALL        2) ; Client-to-server (RPC)
(def ^:const TYPE-ID-CALLRESULT  3) ; Server-to-client (RPC)
(def ^:const TYPE-ID-CALLERROR   4) ; Server-to-client (RPC)
(def ^:const TYPE-ID-SUBSCRIBE   5) ; Client-to-server (PubSub)
(def ^:const TYPE-ID-UNSUBSCRIBE 6) ; Client-to-server (PubSub)
(def ^:const TYPE-ID-PUBLISH     7) ; Client-to-server (PubSub)
(def ^:const TYPE-ID-EVENT       8) ; Server-to-client (PubSub)

(def ^:const URI-WAMP-BASE            "http://api.wamp.ws/")
(def ^:const URI-WAMP-ERROR           (str URI-WAMP-BASE "error#"))
(def ^:const URI-WAMP-PROCEDURE       (str URI-WAMP-BASE "procedure#"))
(def ^:const URI-WAMP-CALL-AUTHREQ    (str URI-WAMP-PROCEDURE "authreq"))
(def ^:const URI-WAMP-CALL-AUTH       (str URI-WAMP-PROCEDURE "auth"))
(def ^:const URI-WAMP-TOPIC           (str URI-WAMP-BASE "topic#"))
(def ^:const URI-WAMP-ERROR-GENERIC   (str URI-WAMP-ERROR "generic"))
(def ^:const DESC-WAMP-ERROR-GENERIC  "generic error")
(def ^:const URI-WAMP-ERROR-INTERNAL  (str URI-WAMP-ERROR "internal"))
(def ^:const DESC-WAMP-ERROR-INTERNAL "internal error")
(def ^:const URI-WAMP-ERROR-NOTFOUND  (str URI-WAMP-ERROR "notfound"))
(def ^:const DESC-WAMP-ERROR-NOTFOUND "not found error")
(def ^:const DESC-WAMP-ERROR-NOAUTH   "unauthorized")
(def ^:const URI-WAMP-ERROR-NOAUTH    (str URI-WAMP-ERROR "unauthorized"))

(def project-version "clj-wamp/1.0.0-beta3")

(def max-sess-id (atom 0))

(defn- next-sess-id []
  (swap! max-sess-id inc))



;; Client utils

(def client-channels (ref {}))
(def client-prefixes (ref {}))
(def client-auth     (ref {}))

(defn add-client
  "Adds a websocket channel (or callback function) to a map of clients
  and returns a unique session id."
  [channel-or-fn]
  (let [sess-id (str (System/currentTimeMillis) "-" (next-sess-id))]
    (dosync (alter client-channels assoc sess-id channel-or-fn))
    sess-id))

(defn get-client-channel
  "Returns the channel (or callback function) for a websocket client's
  session id."
  [sess-id]
  (dosync (get @client-channels sess-id)))

(defn del-client
  "Removes a websocket session from the map of clients."
  [sess-id]
  (dosync
    (alter client-channels dissoc sess-id)
    (alter client-prefixes dissoc sess-id)
    (alter client-auth     dissoc sess-id)))

(defn add-topic-prefix
  "Adds a new CURI topic prefix for a websocket client."
  [sess-id prefix uri]
  (log/trace "New CURI Prefix [" sess-id "]" prefix uri)
  (dosync
    (alter client-prefixes assoc-in [sess-id prefix] uri)))

(defn get-topic
  "Returns the full topic URI for a prefix. If prefix does not exist,
  returns the CURI passed in."
  [sess-id curi]
  (let [topic (split curi #":")
        prefix (first topic)
        suffix (second topic)]
    (dosync
      (if-let [uri (get-in @client-prefixes [sess-id prefix])]
        (str uri suffix)
        curi))))

(defn close-channel
  ([sess-id]
    (close-channel sess-id 1002))
  ([sess-id code]
    (when-let [channel (get-client-channel sess-id)]
      (if (fn? channel)
        (httpkit/close channel) ; for unit testing
        (.serverClose channel code)) ; TODO thread-safe? (locking AsyncChannel ...) ?
      (log/trace "Channel closed" code))))

;; Topic utils

(def client-topics (ref {}))
(def topic-clients (ref {}))

(defn topic-subscribe
  "Subscribes a websocket session to a topic."
  [topic sess-id]
  (dosync
    (alter topic-clients assoc-in [topic sess-id] true)
    (alter client-topics assoc-in [sess-id topic] true)))

(defn topic-unsubscribe
  "Unsubscribes a websocket session from a topic."
  [topic sess-id]
  (dosync
    (alter topic-clients dissoc-in [topic sess-id])
    (alter client-topics dissoc-in [sess-id topic])))

(defn- topic-send!
  "Sends an event to *all* websocket clients subscribed to a topic."
  [topic & data]
  (dosync
    (doseq [[sess-id _] (@topic-clients topic)]
      (apply send! sess-id data))))

(defn- topic-broadcast!
  "Send an event to websocket clients subscribed to a topic,
  except those excluded."
  [topic excludes & data]
  (let [excludes (if (sequential? excludes) excludes [excludes])]
    (dosync
      (doseq [[sess-id _] (@topic-clients topic)]
        (if (not-any? #{sess-id} excludes)
          (apply send! sess-id data))))))

(defn- topic-emit!
  "Sends an event to a specific list of websocket clients subscribed
  to a topic."
  [topic includes & data]
  (let [includes (if (sequential? includes) includes [includes])]
    (dosync
      (doseq [[sess-id _] (@topic-clients topic)]
        (if (some #{sess-id} includes)
          (apply send! sess-id data))))))

(defn get-topic-clients [topic]
  "Returns all client session ids within a topic."
  (dosync
    (if-let [clients (@topic-clients topic)]
      (keys clients))))

;; WAMP websocket send! utils

(defn- send!
  "Sends data to a websocket client."
  [sess-id & data]
  (let [channel-or-fn (get-client-channel sess-id)
        json-data     (json/encode data {:escape-non-ascii true})]
    (log/trace "Sending data:" data)
    (if (fn? channel-or-fn) ; application callback?
      (channel-or-fn data)
      (when channel-or-fn
        (httpkit/send! channel-or-fn json-data)))))

(defn send-welcome!
  "Sends a WAMP welcome message to a websocket client.
  [ TYPE_ID_WELCOME , sessionId , protocolVersion, serverIdent ]"
  ([sess-id]
    (send-welcome! sess-id 1 project-version))
  ([sess-id protocol-ver server-ident]
    (send! sess-id TYPE-ID-WELCOME sess-id protocol-ver server-ident)))

(defn send-call-result!
  "Sends a WAMP call result message to a websocket client.
  [ TYPE_ID_CALLRESULT , callID , result ]"
  [sess-id call-id result]
  (send! sess-id TYPE-ID-CALLRESULT call-id result))

(defn send-call-error!
  "Sends a WAMP call error message to a websocket client.
  [ TYPE_ID_CALLERROR , callID , errorURI , errorDesc [, errorDetails] ]"
  ([sess-id call-id err-uri err-desc]
    (send-call-error! sess-id call-id err-uri err-desc nil))
  ([sess-id call-id err-uri err-desc err-details]
    (if (nil? err-details)
      (send! sess-id TYPE-ID-CALLERROR call-id err-uri err-desc)
      (send! sess-id TYPE-ID-CALLERROR call-id err-uri err-desc err-details))))

(defn send-event!
  "Sends an event message to all clients in topic.
  [ TYPE_ID_EVENT , topicURI , event ]"
  [topic event]
    (topic-send! topic TYPE-ID-EVENT topic event))

(defn broadcast-event!
  "Sends an event message to all clients in a topic but those excluded."
  [topic event excludes]
    (topic-broadcast! topic excludes TYPE-ID-EVENT topic event))

(defn emit-event!
  "Sends an event message to specific clients in a topic"
  [topic event includes]
    (topic-emit! topic includes TYPE-ID-EVENT topic event))


;; WAMP callbacks

(defn- callback-rewrite
  "Utility for rewriting params with an optional callback fn."
  [callback & params]
  (if (fn? callback)
    (apply callback params)
    (when (or (nil? callback) (true? callback))
      params)))

(defn- on-close
  "Clean up clients and topics upon disconnect."
  [sess-id close-cb unsub-cb]
  (fn [status]
    (dosync
      (when (fn? close-cb) (close-cb sess-id status))
      (if-let [sess-topics (@client-topics sess-id)]
        (doseq [[topic _] sess-topics]
          (topic-unsubscribe topic sess-id)
          (when (fn? unsub-cb) (unsub-cb sess-id topic))))
      (del-client sess-id))))

(defn- call-success
  [sess-id topic call-id result on-after-cb]
  (let [cb-params [sess-id topic call-id result]
        cb-params (apply callback-rewrite on-after-cb cb-params)
        [sess-id topic call-id result] cb-params]
    (send-call-result! sess-id call-id result)))

(defn- call-error
  [sess-id topic call-id error on-after-cb]
  (let [cb-params [sess-id topic call-id error]
        cb-params (apply callback-rewrite on-after-cb cb-params)
        [sess-id topic call-id error] cb-params
        {err-uri :uri err-msg :message err-desc :description kill :kill} error
        err-uri (if (nil? err-uri) URI-WAMP-ERROR-GENERIC err-uri)
        err-msg (if (nil? err-msg) DESC-WAMP-ERROR-GENERIC err-msg)]
    (send-call-error! sess-id call-id err-uri err-msg err-desc)
    (when kill (close-channel sess-id))))

; Optional session id for rpc calls
(def ^:dynamic *call-sess-id* nil)

;; WAMP-CRA Authentication

(defn hmac-sha-256
  [^String key ^String data]
  (let [hmac-key (SecretKeySpec. (.getBytes key) "HmacSHA256")
        hmac     (doto (Mac/getInstance "HmacSHA256") (.init hmac-key))
        result   (.doFinal hmac (.getBytes data))]
    (String. (base64/encode result) "UTF-8")))

(defn auth-challenge [sess-id auth-key auth-secret]
  (let [hmac-key (str auth-secret "-" (System/currentTimeMillis) "-" sess-id)]
    (hmac-sha-256 hmac-key auth-key)))

(defn auth-sig-match? [sess-id signature]
  (if-let [auth-sig (get-in @client-auth [sess-id :sig])]
    (= signature auth-sig)))

(defn add-client-auth-sig [sess-id auth-key auth-secret challenge]
  (let [sig (hmac-sha-256 challenge auth-secret)]
    (dosync (alter client-auth assoc sess-id
              {:sig   sig
               :key   auth-key
               :auth? false}))
    sig))

(defn add-client-auth-anon [sess-id]
  (dosync
    (alter client-auth assoc sess-id {:key :anon :auth? false})))

(defn client-auth-requested? [sess-id]
  (not (nil? (get-in @client-auth [sess-id :key]))))

(defn client-authenticated? [sess-id]
  (get-in @client-auth [sess-id :auth?]))

(defn authorized? [sess-id type topic perm-cb]
  (if-let [auth-key (get-in @client-auth [sess-id :key])]
    (let [perms (perm-cb sess-id auth-key)]
      (get-in perms [type topic]))))

(defn create-call-authreq
  [allow-anon? secret-cb]
  (fn [& [auth-key extra]]
    (if (client-authenticated? *call-sess-id*)
      {:error {:uri (str URI-WAMP-ERROR "already-authenticated")
               :message "already authenticated"}}
      (if (client-auth-requested? *call-sess-id*)
        {:error {:uri (str URI-WAMP-ERROR "authentication-already-requested")
                 :message "authentication request already issued - authentication pending"}}

        (if (nil? auth-key)
          ; Allow anonymous auth?
          (if-not allow-anon?
            {:error {:uri (str URI-WAMP-ERROR "anonymous-auth-forbidden")
                     :message "authentication as anonymous is forbidden"}}
            (do
              (add-client-auth-anon *call-sess-id*)
              nil)) ; return nil
          ; Non-anonymous auth
          (if-let [auth-secret (secret-cb *call-sess-id* auth-key extra)]
            (let [challenge (auth-challenge *call-sess-id* auth-key auth-secret)]
              (add-client-auth-sig *call-sess-id* auth-key auth-secret challenge)
              challenge) ; return the challenge
            {:error {:uri (str URI-WAMP-ERROR "no-such-authkey")
                     :message "authentication key does not exist"}}))))))

(defn create-call-auth
  [perm-cb]
  (fn [& [signature]]
    (if (client-authenticated? *call-sess-id*)
      {:error {:uri (str URI-WAMP-ERROR "already-authenticated")
               :message "already authenticated"}}
      (if (not (client-auth-requested? *call-sess-id*))
        {:error {:uri (str URI-WAMP-ERROR "no-authentication-requested")
                 :message "no authentication previously requested"}}
        (let [auth-key (get-in @client-auth [*call-sess-id* :key])]
          (if (or (= :anon auth-key) (auth-sig-match? *call-sess-id* signature))
            (do
              (dosync (alter client-auth assoc-in [*call-sess-id* :auth?] true))
              (perm-cb *call-sess-id* auth-key))
            (do
              ; remove previous auth data, must request and authenticate again
              (dosync (alter client-auth dissoc *call-sess-id*))
              {:error {:uri (str URI-WAMP-ERROR "invalid-signature")
                       :message "signature for authentication request is invalid"}})))))))

(defn init-cr-auth [callbacks]
  (if-let [auth-cbs (callbacks :on-auth)]
    (let [allow-anon? (auth-cbs :allow-anon?)
          secret-cb   (auth-cbs :secret)
          perm-cb     (auth-cbs :permissions)]
      (merge-with merge callbacks
        {:on-call {URI-WAMP-CALL-AUTHREQ (create-call-authreq allow-anon? secret-cb)
                   URI-WAMP-CALL-AUTH    (create-call-auth perm-cb)}}))
    callbacks))

(defn auth-timeout [sess-id]
  (when-not (client-authenticated? sess-id)
    (close-channel sess-id)))

(defn init-auth-timer [callbacks sess-id]
  (when-let [auth-cbs (callbacks :on-auth)]
    (let [timeout-ms (auth-cbs :timeout 20000)
          task       (timer/schedule-task timeout-ms (auth-timeout sess-id))]
      task)))

;; WAMP PubSub/RPC callbacks

(defn- on-call
  "Handle WAMP call (RPC) messages"
  [callbacks sess-id topic call-id & call-params]
  (if-let [rpc-cb (callbacks topic)]
    (try
      (let [cb-params [sess-id topic call-id call-params]
            cb-params (apply callback-rewrite (callbacks :on-before) cb-params)
            [sess-id topic call-id call-params] cb-params
            rpc-result (binding [*call-sess-id* sess-id]  ; bind optional sess-id
                         (apply rpc-cb call-params))      ; use fn's own arg signature
            error      (:error  rpc-result)
            result     (:result rpc-result)]
        (if (and (nil? error) (nil? result))
          ; No map with result or error? Assume successful rpc-result as-is
          (call-success sess-id topic call-id rpc-result (callbacks :on-after-success))
          (if (nil? error)
            (call-success sess-id topic call-id result (callbacks :on-after-success))
            (call-error   sess-id topic call-id error  (callbacks :on-after-error)))))

      (catch Exception e
        (call-error sess-id topic call-id
          {:uri URI-WAMP-ERROR-INTERNAL
           :message DESC-WAMP-ERROR-INTERNAL
           :description (.getMessage e)}
          (callbacks :on-after-error))
        (log/error e)))

    (call-error sess-id topic call-id
      {:uri URI-WAMP-ERROR-NOTFOUND
       :message DESC-WAMP-ERROR-NOTFOUND}
      (callbacks :on-after-error))))

(defn- map-key-or-prefix
  "Finds a map value by key or lookup by string key prefix (ending with *)."
  [m k]
  (if-let [v (m k)] v
    (some #(when (not (nil? %)) %)
      (for [[mk mv] m]
        (when (and (not (keyword? mk)) (not (false? mv))
                (= \* (last mk))
                (= (take (dec (count mk)) k) (butlast mk)))
          mv)))))

(defn- on-subscribe
  [callbacks sess-id topic]
  (dosync
    (when (nil? (get-in @topic-clients [topic sess-id]))
      (when-let [topic-cb (map-key-or-prefix callbacks topic)]
        (when (or (true? topic-cb) (topic-cb sess-id topic))
          (let [on-after-cb (callbacks :on-after)]
            (topic-subscribe topic sess-id)
            (when (fn? on-after-cb)
              (on-after-cb sess-id topic))))))))

(defn- get-publish-exclude [sess-id exclude]
  (if (= Boolean (type exclude))
    (when (true? exclude) [sess-id])
    exclude))

(defn- on-publish
  "Handles WAMP publish messages, sending event messages back out
  to clients subscribed to the topic.
  [ TYPE_ID_PUBLISH , topicURI , event [, exclude [, eligible ]]"
  ([callbacks sess-id topic event]
    (on-publish callbacks sess-id topic event false nil))
  ([callbacks sess-id topic event exclude]
    (on-publish callbacks sess-id topic event exclude nil))
  ([callbacks sess-id topic event exclude eligible]
    (when-let [pub-cb (map-key-or-prefix callbacks topic)]
      (let [cb-params [sess-id topic event exclude eligible]
            cb-params (apply callback-rewrite pub-cb cb-params)
            on-after-cb (callbacks :on-after)]
        (when (sequential? cb-params)
          (let [[sess-id topic event exclude eligible] cb-params
                exclude (get-publish-exclude sess-id exclude)]
            (if-not (nil? eligible)
              (emit-event! topic event eligible)
              (broadcast-event! topic event exclude))
            (when (fn? on-after-cb)
              (on-after-cb sess-id topic event exclude eligible))))))))

(defn- on-message
  "Handles all http-kit messages. parses the incoming data as json
  and finds the appropriate wamp callback."
  [sess-id callbacks]
  (fn [data]
    (log/trace "Data received:" data)
    (let [[msg-type & msg-params] (try (json/decode data)
                                    (catch com.fasterxml.jackson.core.JsonParseException ex
                                      [nil nil]))
          on-call-cbs  (callbacks :on-call)
          on-sub-cbs   (callbacks :on-subscribe)
          on-unsub-cb  (callbacks :on-unsubscribe)
          on-pub-cbs   (callbacks :on-publish)
          perm-cb      (get-in callbacks [:on-auth :permissions])]
      (case msg-type

        1 ;TYPE-ID-PREFIX
        (apply add-topic-prefix sess-id msg-params)

        2 ;TYPE-ID-CALL
        (if (map? on-call-cbs)
          (let [[call-id topic-uri & call-params] msg-params
                topic (get-topic sess-id topic-uri)]
            (if (or (nil? perm-cb)
                    (= URI-WAMP-CALL-AUTHREQ topic)
                    (= URI-WAMP-CALL-AUTH topic)
                    (authorized? sess-id :rpc topic perm-cb))
              (apply on-call on-call-cbs sess-id topic call-id call-params)
              (call-error sess-id topic call-id
                {:uri URI-WAMP-ERROR-NOAUTH :message DESC-WAMP-ERROR-NOAUTH}
                (on-call-cbs :on-after-error)))))

        5 ;TYPE-ID-SUBSCRIBE
        (let [topic (get-topic sess-id (first msg-params))]
          (if (or (nil? perm-cb)
                  (authorized? sess-id :subscribe topic perm-cb))
            (on-subscribe on-sub-cbs sess-id topic)))

        6 ;TYPE-ID-UNSUBSCRIBE
        (let [topic (get-topic sess-id (first msg-params))]
          (dosync
            (when (true? (get-in @topic-clients [topic sess-id]))
              (topic-unsubscribe topic sess-id)
              (when (fn? on-unsub-cb) (on-unsub-cb sess-id topic)))))

        7 ;TYPE-ID-PUBLISH
        (let [[topic-uri event & pub-args] msg-params
              topic (get-topic sess-id topic-uri)]
          (if (or (nil? perm-cb)
                (authorized? sess-id :publish topic perm-cb))
            (apply on-publish on-pub-cbs sess-id topic event pub-args)))

        ; default: Unknown message type
        (log/warn "Unknown message type" data)))))


(defn http-kit-handler
  "Sets up the necessary http-kit websocket event handlers
  for use with the WAMP sub-protocol. Returns a WAMP client session id.

  Example usage:

    (http-kit/with-channel req channel
      (if-not (:websocket? req)
        (http-kit/close channel)
        (http-kit-handler channel
          {:on-open        on-open-fn
           :on-close       on-close-fn

           :on-auth        {:allow-anon?     true
                            :timeout         20000 ; 20 secs
                            :get-secret      get-auth-secret-fn
                            :get-permissions get-auth-permissions-fn}

           :on-call        {(rpc-url \"add\")      +         ; map topics to rpc functions
                            (rpc-url \"echo\")     identity
                            :on-before           on-before-call-fn
                            :on-after-error      on-after-call-error-fn
                            :on-after-success    on-after-call-success-fn}

           :on-subscribe   {(evt-url \"chat\")     on-subscribe-fn? ; allowed to subscribe?
                            (evt-url \"prefix*\")  true             ; match topics by prefix
                            (evt-url \"sub-only\") true             ; implicitly allowed
                            (evt-url \"pub-only\") false            ; subscription is denied
                            :on-after            on-after-subscribe-fn};

           :on-publish     {(evt-url \"chat\")     on-publish-fn   ; custom event broker
                            (evt-url \"prefix*\")  true            ; pass events through as-is
                            (evt-url \"sub-only\") false           ; publishing is denied
                            (evt-url \"pub-only\") true
                            :on-after            on-after-publish-fn}

           :on-unsubscribe on-unsubscribe-fn})))

  Callback signatures:

    (on-open-fn sess-id)
    (on-close-fn sess-id status)

    (rpc-call ...)
      Can have any signature. The parameters received from the client will be applied as-is.
      The client session is also available in the bound *call-sess-id* var.
      The function may return a value as is, or in a result map: {:result \"my result\"},
      or as an error map: {:error {:uri \"http://example.com/error#give-error\"
                                   :message \"Test error\"
                                   :description \"Test error description\"}}

    (on-before-call-fn sess-id topic call-id call-params)
      To allow call, return params as vector: [sess-id topic call-id call-params]
      To deny, return nil/false.

    (on-after-call-error-fn sess-id topic call-id error)
      Return params as vector: [sess-id topic call-id error]

    (on-after-call-success-fn sess-id topic call-id result)
      Return params as vector: [sess-id topic call-id result]

    (on-subscribe-fn? sess-id topic)
      Return true to allow client to subscribe, false to deny.

    (on-after-subscribe-fn sess-id topic)
      No return values required.

    (on-publish-fn sess-id topic event exclude eligible)
      To allow publish, return params as vector: [sess-id topic event exclude eligible]
      To deny, return nil/false.

    (on-after-publish-fn sess-id topic event exclude eligible)
      No return values required.

    (on-unsubscribe-fn sess-id topic)
      No return values required."
  [channel callbacks-map]
  (let [callbacks-map (init-cr-auth callbacks-map)
        cb-on-open    (callbacks-map :on-open)
        sess-id       (add-client channel)]
    (httpkit/on-close channel   (on-close sess-id
                                  (callbacks-map :on-close)
                                  (callbacks-map :on-unsubscribe)))
    (httpkit/on-receive channel (on-message sess-id callbacks-map))
    (send-welcome! sess-id)
    (when (fn? cb-on-open) (cb-on-open sess-id))
    (init-auth-timer callbacks-map sess-id)
    sess-id))


(defn origin-match?
  "Compares a regular expression against the Origin: header.
  Used to help protect against CSRF, but do not depend on just
  this check. Best to use a server-generated CSRF token for comparison."
  [origin-re req]
  (if-let [req-origin (get-in req [:headers "origin"])]
    (re-matches origin-re req-origin)))

(defn subprotocol?
  "Checks if a protocol string exists in the Sec-WebSocket-Protocol
  list header."
  [proto req]
  (if-let [protocols (get-in req [:headers "sec-websocket-protocol"])]
    (some #{proto}
      (map #(lower-case (trim %))
        (split protocols #",")))))

(defmacro with-channel-validation
  "Replaces HTTP Kit with-channel macro to do extra validation
  for the wamp subprotocol and allowed origin URLs.

  Example usage:

    (defn my-wamp-handler [request]
      (wamp/with-channel-validation request channel #\"https?://myhost\"
        (wamp/http-kit-handler channel { ... })))

  See org.httpkit.server for more information."
  [request ch-name origin-re & body]
  `(let [~ch-name (:async-channel ~request)]
     (if (:websocket? ~request)
       (if-let [key# (get-in ~request [:headers "sec-websocket-key"])]
         (if (origin-match? ~origin-re ~request)
           (if (subprotocol? "wamp" ~request)
             (do
               (.sendHandshake ~(with-meta ch-name {:tag `AsyncChannel})
                 {"Upgrade"                "websocket"
                  "Connection"             "Upgrade"
                  "Sec-WebSocket-Accept"   (httpkit/accept key#)
                  "Sec-WebSocket-Protocol" "wamp"})
               ~@body
               {:body ~ch-name})
             {:status 400 :body "missing or bad WebSocket-Protocol"})
           {:status 400 :body "missing or bad WebSocket-Origin"})
         {:status 400 :body "missing or bad WebSocket-Key"})
       {:status 400 :body "not websocket protocol"})))