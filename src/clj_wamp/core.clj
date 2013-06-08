(ns clj-wamp.core
  ^{:author "Christopher Martin"
    :doc "Clojure implementation of the WebSocket Application Messaging Protocol"}
  (:use [clojure.core.incubator :only [dissoc-in]]
        [clojure.string :only [split]])
  (:require [org.httpkit.server :as httpkit]
            [cheshire.core :as json]))

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

(def clj-wamp-version
  (apply str
    (interpose "/"
      (rest (take 3 (read-string (slurp "project.clj")))))))


(def max-id (atom 0))

(defn next-sess-id
  "return the next (incremented) webservice session id"
  []
  (swap! max-id inc))


(def clients (atom {}))

(defn add-client
  "add a webservice session and it's corresponding event channel to a map of clients"
  [sess-id channel]
  (swap! clients assoc sess-id {:channel channel}))

(defn del-client
  "remove a webservice session from the map of clients"
  [sess-id]
  (swap! clients dissoc sess-id))


(def topics (atom {}))

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

(defn topic-emit!
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

(defn topic-send!
  "send an event to specific webservice clients subscribed to a topic"
  [topic includes & data]
  (let [includes (if (sequential? includes) includes [includes])]
    (doseq [[sess-id _] (@topics topic)]
      (if (some #{sess-id} includes)
        (apply send! sess-id data)))))


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


(defn send!
  "send data to a websocket client"
  [sess-id & data]
  (let [channel (get-in @clients [sess-id :channel])
        json-data (json/generate-string data)]
    (if (fn? channel) ; application callback?
      (channel data)
      (httpkit/send! (get-in @clients [sess-id :channel]) json-data))))

(defn send-welcome!
  ([sess-id]
    (send-welcome! sess-id 1 clj-wamp-version))
  ([sess-id protocol-ver server-info]
    (send! sess-id TYPE-ID-WELCOME sess-id protocol-ver server-info)))

(defn send-call-result!
  [sess-id call-id result]
  (send! sess-id TYPE-ID-CALLRESULT call-id result))

(defn send-call-error!
  ([sess-id call-id err-uri err-desc]
    (send! sess-id TYPE-ID-CALLERROR call-id err-uri err-desc))
  ([sess-id call-id err-uri err-desc err-details]
    (send! sess-id TYPE-ID-CALLERROR call-id err-uri err-desc err-details)))

(defn send-event!
  ([topic event]
    (topic-emit! topic TYPE-ID-EVENT topic event))
  ([sess-id topic event]
    (topic-broadcast! topic sess-id TYPE-ID-EVENT topic event)))


(defn on-close
  [sess-id callback]
  (fn [status]
    (if-not (nil? callback)
      (callback sess-id status))
    (if-let [sess-topics (get-in @clients [sess-id :topics])]
      (doseq [[topic _] sess-topics]
        (topic-unsubscribe topic sess-id)))
    (del-client sess-id)))

(defn on-publish
  ([sess-id topic event]
    (on-publish sess-id topic event false nil))
  ([sess-id topic event exclude]
    (on-publish sess-id topic event exclude nil))
  ([sess-id topic event exclude eligible]
    (if-not (nil? eligible)
      (topic-send! topic eligible TYPE-ID-EVENT event)
      (let [exclude (if (= Boolean (type exclude))
                      (if (true? exclude) [sess-id] nil)
                      exclude)]
        (topic-broadcast! topic exclude TYPE-ID-EVENT event)))))

(defn on-message
  [sess-id callbacks]
  (fn [data]
    (let [[msg-type & msg-params] (json/parse-string data)
          cb-on-call    (callbacks :on-call)
          cb-on-sub     (callbacks :on-subscribe)
          cb-on-unsub   (callbacks :on-unsubscribe)
          cb-on-pub     (callbacks :on-publish)]
      (case msg-type

        1 ;TYPE-ID-PREFIX
        (apply add-prefix sess-id msg-params)

        2 ;TYPE-ID-CALL
        (if-not (nil? cb-on-call)
          (let [[call-id topic-uri & call-params] msg-params
                topic (get-topic sess-id topic-uri)]
            (apply cb-on-call sess-id topic call-id call-params)))

        5 ;TYPE-ID-SUBSCRIBE
        (let [topic (get-topic sess-id (first msg-params))]
          (if (nil? (get-in @topics [topic sess-id]))
            (topic-subscribe topic sess-id)
            (if-not (nil? cb-on-sub)
              (cb-on-sub sess-id topic))))

        6 ;TYPE-ID-UNSUBSCRIBE
        (let [topic (get-topic sess-id (first msg-params))]
          (if (true? (get-in @topics [topic sess-id]))
            (topic-unsubscribe topic sess-id)
            (if-not (nil? cb-on-sub)
              (cb-on-unsub sess-id topic))))

        7 ;TYPE-ID-PUBLISH
        (let [[topic-uri event & pub-args] msg-params
              topic (get-topic sess-id topic-uri)]
          (apply on-publish sess-id topic event pub-args)
          (if-not (nil? cb-on-pub)
            (cb-on-pub sess-id topic event pub-args)))

        nil))))


(defn wamp-ws-handler
  [channel callbacks]
  (let [cb-on-open  (callbacks :on-open)
        cb-on-close (callbacks :on-close)
        sess-id (str (System/currentTimeMillis) "-" (next-sess-id))]
    (add-client sess-id channel)
    (httpkit/on-close channel (on-close sess-id cb-on-close))
    (httpkit/on-receive channel (on-message sess-id callbacks))
    (send-welcome! sess-id)
    (if-not (nil? cb-on-open) (cb-on-open sess-id))))
