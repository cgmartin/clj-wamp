(ns clj-wamp.server
  ^{:author "Christopher Martin"
    :doc "Clojure implementation of the WebSocket Application Messaging Protocol"}
  (:use [clojure.core.incubator :only [dissoc-in]]
        [clojure.string :only [split]])
  (:require [org.httpkit.server :as httpkit]
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

(def project-version
  (apply str
    (interpose "/"
      (rest (take 3 (read-string (slurp "project.clj")))))))


(def max-sess-id (atom 0))

(defn next-sess-id
  "return the next (incremented) webservice session id"
  []
  (swap! max-sess-id inc))


;; Client utils

(def clients (atom {})) ; TODO Needs load testing to find optimal ref granularity

(defn add-client
  "add a webservice client with it's corresponding event channel to a map of clients"
  [channel]
  (let [sess-id (str (System/currentTimeMillis) "-" (next-sess-id))]
    (swap! clients assoc sess-id {:channel channel})
    sess-id))

(defn del-client
  "remove a webservice session from the map of clients"
  [sess-id]
  (swap! clients dissoc sess-id))

(defn add-prefix
  "add a new curi prefix for a webservice client"
  [sess-id prefix uri]
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

(def topics (atom {})) ; TODO Needs load testing to find optimal ref granularity

(defn topic-subscribe
  "subscribe a webservice session to a topic"
  [topic sess-id]
  (swap! topics assoc-in [topic sess-id] true)
  (swap! clients assoc-in [sess-id :topics topic] true))

(defn topic-unsubscribe
  "unsubscribe a webservice session from a topic"
  [topic sess-id]
  (swap! topics dissoc-in [topic sess-id])
  (swap! clients dissoc-in [sess-id :topics topic]))

(defn topic-send!
  "send an event to all webservice clients subscribed to a topic"
  [topic & data]
  (doseq [[sess-id _] (@topics topic)]
    (apply send! sess-id data)))

(defn topic-broadcast!
  "send an event to webservice clients subscribed to a topic, except those excluded"
  [topic excludes & data]
  (let [excludes (if (sequential? excludes) excludes [excludes])]
    (doseq [[sess-id _] (@topics topic)]
      (if (not-any? #{sess-id} excludes)
        (apply send! sess-id data)))))

(defn topic-emit!
  "send an event to specific webservice clients subscribed to a topic"
  [topic includes & data]
  (let [includes (if (sequential? includes) includes [includes])]
    (doseq [[sess-id _] (@topics topic)]
      (if (some #{sess-id} includes)
        (apply send! sess-id data)))))


;; WAMP websocket send! utils

(defn- send!
  "send data to a websocket client"
  [sess-id & data]
  (let [channel (get-in @clients [sess-id :channel])
        json-data (json/encode data)]
    (if (fn? channel) ; application callback?
      (channel data)
      (httpkit/send! (get-in @clients [sess-id :channel]) json-data))))

(defn send-welcome!
  "send wamp welcome message format to a websocket client
  [ TYPE_ID_WELCOME , sessionId , protocolVersion, serverIdent ]"
  ([sess-id]
    (send-welcome! sess-id 1 project-version))
  ([sess-id protocol-ver server-ident]
    (send! sess-id TYPE-ID-WELCOME sess-id protocol-ver server-ident)))

(defn send-call-result!
  "send wamp call result message format to a websocket client
  [ TYPE_ID_CALLRESULT , callID , result ]"
  [sess-id call-id result]
  (send! sess-id TYPE-ID-CALLRESULT call-id result))

(defn send-call-error!
  "send wamp call error message format to a websocket client
  [ TYPE_ID_CALLERROR , callID , errorURI , errorDesc [, errorDetails] ]"
  ([sess-id call-id err-uri err-desc]
    (send-call-error! sess-id TYPE-ID-CALLERROR call-id err-uri err-desc nil))
  ([sess-id call-id err-uri err-desc err-details]
    (if (nil? err-details)
      (send! sess-id TYPE-ID-CALLERROR call-id err-uri err-desc)
      (send! sess-id TYPE-ID-CALLERROR call-id err-uri err-desc err-details))))

(defn send-event!
  "emit or broadcast a wamp call error message format to a topic.
  If a sess-id is included, a topic-broadcast! will occur, which excludes
  sending to the sess-id. Otherwise, the event will be emitted to all
  clients within the topic.
  [ TYPE_ID_EVENT , topicURI , event ]"
  ([topic event]
    (topic-send! topic TYPE-ID-EVENT topic event))
  ([sess-id topic event]
    (topic-broadcast! topic sess-id TYPE-ID-EVENT topic event)))


;; WAMP callbacks

(defn- on-close
  "callback that handles cleanup of clients and topics"
  [sess-id callback]
  (fn [status]
    (when (fn? callback) (callback sess-id status))
    (if-let [sess-topics (get-in @clients [sess-id :topics])]
      (doseq [[topic _] sess-topics]
        (topic-unsubscribe topic sess-id)))
    (del-client sess-id)))

(defn- on-call
  "callback that handles client call messages"
  [app-callbacks sess-id topic call-id & call-params]
  (if-let [rpc-cb (get app-callbacks topic)]
    (let [rpc-result (apply rpc-cb call-params)]
      (if-let [{:keys [uri message & description]} (get rpc-result :error)]
        (send-call-error! sess-id call-id uri message description)
        (send-call-result! sess-id call-id (rpc-result :result))))
    (if-let [{:keys [uri message & description]} (get app-callbacks :not-found-error)]
      (send-call-error! sess-id call-id uri message description))))

(defn- on-publish
  "callback that handles client publish messages,
  sending events to clients subscribed to the topic.
  [ TYPE_ID_PUBLISH , topicURI , event [, exclude [, eligible ]]"
  ([sess-id topic event]
    (on-publish sess-id topic event false nil))
  ([sess-id topic event exclude]
    (on-publish sess-id topic event exclude nil))
  ([sess-id topic event exclude eligible]
    (if-not (nil? eligible)
      (topic-emit! topic eligible TYPE-ID-EVENT topic event)
      (let [exclude (if (= Boolean (type exclude))
                      (if (true? exclude) [sess-id] nil)
                      exclude)]
        (topic-broadcast! topic exclude TYPE-ID-EVENT topic event)))))

(defn- on-message
  "callback that handles all http-kit messages.
  parses the incoming data as json and finds the appropriate
  wamp callback."
  [sess-id callbacks]
  (fn [data]
    (let [[msg-type & msg-params] (json/decode data) ; TODO parse error handling
          on-call-cbs  (callbacks :on-call)
          on-sub-cb    (callbacks :on-subscribe)
          on-unsub-cb  (callbacks :on-unsubscribe)
          on-pub-cb    (callbacks :on-publish)]
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
          (if (nil? (get-in @topics [topic sess-id]))
            (topic-subscribe topic sess-id)
            (when (fn? on-sub-cb) (on-sub-cb sess-id topic))))

        6 ;TYPE-ID-UNSUBSCRIBE
        (let [topic (get-topic sess-id (first msg-params))]
          (if (true? (get-in @topics [topic sess-id]))
            (topic-unsubscribe topic sess-id)
            (when (fn? on-unsub-cb) (on-unsub-cb sess-id topic))))

        7 ;TYPE-ID-PUBLISH
        (let [[topic-uri event & pub-args] msg-params
              topic (get-topic sess-id topic-uri)]
          (apply on-publish sess-id topic event pub-args)
          (when (fn? on-pub-cb) (on-pub-cb sess-id topic event pub-args)))

        nil))))


(defn http-kit-handler
  "sets up the necessary http-kit websocket callbacks
  for using the wamp sub-protocol"
  [channel callbacks]
  (let [cb-on-open  (get callbacks :on-open)
        cb-on-close (get callbacks :on-close)
        sess-id     (add-client channel)]
    (httpkit/on-close channel (on-close sess-id cb-on-close))
    (httpkit/on-receive channel (on-message sess-id callbacks))
    (send-welcome! sess-id)
    (when (fn? cb-on-open) (cb-on-open sess-id))
    sess-id))
