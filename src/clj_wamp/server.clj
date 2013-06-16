(ns clj-wamp.server
  ^{:author "Christopher Martin"
    :doc "Clojure implementation of the WebSocket Application Messaging Protocol"}
  (:use [clojure.core.incubator :only [dissoc-in]]
        [clojure.string :only [split]])
  (:require [clojure.java.io :as io]
            [org.httpkit.server :as httpkit]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

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
(def ^:const URI-WAMP-TOPIC           (str URI-WAMP-BASE "topic#"))
(def ^:const URI-WAMP-ERROR-GENERIC   (str URI-WAMP-ERROR "generic"))
(def ^:const DESC-WAMP-ERROR-GENERIC  "generic error")
(def ^:const URI-WAMP-ERROR-INTERNAL  (str URI-WAMP-ERROR "internal"))
(def ^:const DESC-WAMP-ERROR-INTERNAL "internal error")
(def ^:const URI-WAMP-ERROR-NOTFOUND  (str URI-WAMP-ERROR "notfound"))
(def ^:const DESC-WAMP-ERROR-NOTFOUND "not found error")

(def project-version "clj-wamp/0.4.1")

(def max-sess-id (atom 0))

(defn- next-sess-id []
  (swap! max-sess-id inc))


;; Client utils

(def clients (atom {})) ; TODO needs ref transactions with topics

(defn add-client
  "add a websocket client with it's corresponding event channel to a map of clients"
  [channel]
  (let [sess-id (str (System/currentTimeMillis) "-" (next-sess-id))]
    (swap! clients assoc sess-id {:channel channel})
    sess-id))

(defn get-client-channel
  "returns the channel for a websocket client"
  [sess-id]
  (get-in @clients [sess-id :channel]))

(defn del-client
  "remove a websocket session from the map of clients"
  [sess-id]
  (swap! clients dissoc sess-id))

(defn add-prefix
  "add a new curi prefix for a websocket client"
  [sess-id prefix uri]
  (log/trace "New CURI Prefix [" sess-id "]" prefix uri)
  (swap! clients assoc-in [sess-id :prefixes prefix] uri))

(defn get-topic
  "get a full topic uri from a prefix"
  [sess-id curi]
  (let [topic (split curi #":")
        prefix (first topic)
        suffix (second topic)]
    (if-let [uri (get-in @clients [sess-id :prefixes prefix])]
      (str uri suffix)
      curi)))


;; Topic utils

(def topics (atom {})) ; TODO needs ref transactions with topics

(defn topic-subscribe
  "subscribe a websocket session to a topic"
  [topic sess-id]
  (swap! topics assoc-in [topic sess-id] true)
  (swap! clients assoc-in [sess-id :topics topic] true))

(defn topic-unsubscribe
  "unsubscribe a websocket session from a topic"
  [topic sess-id]
  (swap! topics dissoc-in [topic sess-id])
  (swap! clients dissoc-in [sess-id :topics topic]))

(defn topic-send!
  "send an event to all websocket clients subscribed to a topic"
  [topic & data]
  (doseq [[sess-id _] (@topics topic)]
    (apply send! sess-id data)))

(defn topic-broadcast!
  "send an event to websocket clients subscribed to a topic, except those excluded"
  [topic excludes & data]
  (let [excludes (if (sequential? excludes) excludes [excludes])]
    (doseq [[sess-id _] (@topics topic)]
      (if (not-any? #{sess-id} excludes)
        (apply send! sess-id data)))))

(defn topic-emit!
  "send an event to specific websocket clients subscribed to a topic"
  [topic includes & data]
  (let [includes (if (sequential? includes) includes [includes])]
    (doseq [[sess-id _] (@topics topic)]
      (if (some #{sess-id} includes)
        (apply send! sess-id data)))))

(defn topic-clients [topic]
  "get all client session ids within a topic"
  (if-let [clients (@topics topic)]
    (keys clients)))

;; WAMP websocket send! utils

(defn- send!
  "send data to a websocket client"
  [sess-id & data]
  (let [channel (get-client-channel sess-id)
        json-data (json/encode data)]
    (log/trace "Sending data:" data)
    (if (fn? channel) ; application callback?
      (channel data)
      (when channel
        (httpkit/send! channel json-data)))))

(defn send-welcome!
  "send wamp welcome message format to a websocket client.
  [ TYPE_ID_WELCOME , sessionId , protocolVersion, serverIdent ]"
  ([sess-id]
    (send-welcome! sess-id 1 project-version))
  ([sess-id protocol-ver server-ident]
    (send! sess-id TYPE-ID-WELCOME sess-id protocol-ver server-ident)))

(defn send-call-result!
  "send wamp call result message format to a websocket client.
  [ TYPE_ID_CALLRESULT , callID , result ]"
  [sess-id call-id result]
  (send! sess-id TYPE-ID-CALLRESULT call-id result))

(defn send-call-error!
  "send wamp call error message format to a websocket client.
  [ TYPE_ID_CALLERROR , callID , errorURI , errorDesc [, errorDetails] ]"
  ([sess-id call-id err-uri err-desc]
    (send-call-error! sess-id call-id err-uri err-desc nil))
  ([sess-id call-id err-uri err-desc err-details]
    (if (nil? err-details)
      (send! sess-id TYPE-ID-CALLERROR call-id err-uri err-desc)
      (send! sess-id TYPE-ID-CALLERROR call-id err-uri err-desc err-details))))

(defn send-event!
  "Broadcast a wamp call error message format to a topic.
  If a sess-id is included, a topic-broadcast! will occur, which excludes
  sending to the sess-id. Otherwise, the event will be sent to all
  clients within the topic.
  [ TYPE_ID_EVENT , topicURI , event ]"
  ([topic event]
    (topic-send! topic TYPE-ID-EVENT topic event))
  ([sess-id topic event]
    (topic-broadcast! topic sess-id TYPE-ID-EVENT topic event))
  ([sess-id topic event self?]
    (if (true? self?)
      (send! sess-id TYPE-ID-EVENT topic event)
      (send-event! sess-id topic event))))


;; WAMP callbacks

(defn- callback-rewrite
  "utility for rewriting params with an optional callback fn"
  [callback & params]
  (if (fn? callback)
    (apply callback params)
    (when (true? callback)
      params)))

(defn- on-close
  "clean up clients and topics upon disconnect"
  [sess-id close-cb unsub-cb]
  (fn [status]
    (when (fn? close-cb) (close-cb sess-id status))
    (if-let [sess-topics (get-in @clients [sess-id :topics])]
      (doseq [[topic _] sess-topics]
        (topic-unsubscribe topic sess-id)
        (when (fn? unsub-cb) (unsub-cb sess-id topic))))
    (del-client sess-id)))

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
    (when kill (httpkit/close (get-client-channel sess-id)))))

(defn- on-call
  "handle client call (RPC) messages"
  [callbacks sess-id topic call-id & call-params]
  (if-let [rpc-cb (callbacks topic)]
    (try
      (let [cb-params [sess-id topic call-id call-params]
            cb-params (apply callback-rewrite (callbacks :on-before) cb-params)
            [sess-id topic call-id call-params] cb-params
            rpc-result (apply rpc-cb sess-id call-params)
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
          (callbacks :on-after-error))))
    (call-error sess-id topic call-id
      {:uri URI-WAMP-ERROR-NOTFOUND
       :message DESC-WAMP-ERROR-NOTFOUND}
      (callbacks :on-after-error))))

(defn- map-key-or-prefix
  "finds a map value by key or string key prefix (ending with *)"
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
  (when (nil? (get-in @topics [topic sess-id]))
    (when-let [topic-cb (map-key-or-prefix callbacks topic)]
      (when (or (true? topic-cb) (topic-cb sess-id topic))
        (let [on-after-cb (callbacks :on-after)]
          (topic-subscribe topic sess-id)
          (when (fn? on-after-cb)
            (on-after-cb sess-id topic)))))))

(defn- get-publish-exclude [sess-id exclude]
  (if (= Boolean (type exclude))
    (when (true? exclude) [sess-id])
    exclude))

(defn- on-publish
  "handle client publish messages,
  sending events to clients subscribed to the topic.
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
              (topic-emit! topic eligible TYPE-ID-EVENT topic event)
              (topic-broadcast! topic exclude TYPE-ID-EVENT topic event))
            (when (fn? on-after-cb) (on-after-cb sess-id topic event exclude eligible))))))))

(defn- on-message
  "handle all http-kit messages. parses the incoming data as json
  and finds the appropriate wamp callback."
  [sess-id callbacks]
  (fn [data]
    (log/trace "Data received:" data)
    (let [[msg-type & msg-params] (json/decode data) ; TODO parse error handling
          on-call-cbs  (callbacks :on-call)
          on-sub-cbs   (callbacks :on-subscribe)
          on-unsub-cb  (callbacks :on-unsubscribe)
          on-pub-cbs   (callbacks :on-publish)]
      (case msg-type

        1 ;TYPE-ID-PREFIX
        (apply add-prefix sess-id msg-params)

        2 ;TYPE-ID-CALL
        (if (map? on-call-cbs)
          (let [[call-id topic-uri & call-params] msg-params
                topic (get-topic sess-id topic-uri)]
            (apply on-call on-call-cbs sess-id topic call-id call-params)))

        5 ;TYPE-ID-SUBSCRIBE
        (let [topic (get-topic sess-id (first msg-params))]
          (on-subscribe on-sub-cbs sess-id topic))

        6 ;TYPE-ID-UNSUBSCRIBE
        (let [topic (get-topic sess-id (first msg-params))]
          (when (true? (get-in @topics [topic sess-id]))
            (topic-unsubscribe topic sess-id)
            (when (fn? on-unsub-cb) (on-unsub-cb sess-id topic))))

        7 ;TYPE-ID-PUBLISH
        (let [[topic-uri event & pub-args] msg-params
              topic (get-topic sess-id topic-uri)]
          (apply on-publish on-pub-cbs sess-id topic event pub-args))

        ; TODO close connection on bad message?
        nil))))


(defn http-kit-handler
  "sets up the necessary http-kit websocket event handlers
  for use with the wamp sub-protocol"
  [channel callbacks]
  (let [cb-on-open  (callbacks :on-open)
        sess-id     (add-client channel)]
    (httpkit/on-close channel (on-close sess-id (callbacks :on-close) (callbacks :on-unsubscribe)))
    (httpkit/on-receive channel (on-message sess-id callbacks))
    (send-welcome! sess-id)
    (when (fn? cb-on-open) (cb-on-open sess-id))
    sess-id))
