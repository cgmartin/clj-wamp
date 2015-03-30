(ns clj-wamp.core
  (:require 
    [clojure.tools.logging :as log]
    [clojure.string :refer [split]]
    [org.httpkit.server :as httpkit]
    [cheshire.core :as json]))

(def ^:const project-version "clj-wamp/2.0.0-SNAPSHOT")

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
  (get @client-channels sess-id))

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
    (if-let [uri (get-in @client-prefixes [sess-id prefix])]
      (str uri suffix)
      curi)))

(defn close-channel
  ([sess-id]
    (close-channel sess-id 1002))
  ([sess-id code]
    (when-let [channel (get-client-channel sess-id)]
      (if (fn? channel)
        (httpkit/close channel) ; for unit testing
        (.serverClose channel code)) ; TODO thread-safe? (locking AsyncChannel ...) ?
      (log/trace "Channel closed" code))))

(defn send!
  "Sends data to a websocket client."
  [sess-id & data]
  (dosync
    (let [channel-or-fn (get-client-channel sess-id)
          json-data     (json/encode data {:escape-non-ascii true})]
      (log/trace "Sending data:" data)
      (if (fn? channel-or-fn) ; application callback?
        (channel-or-fn data)
        (when channel-or-fn
          (httpkit/send! channel-or-fn json-data))))))
