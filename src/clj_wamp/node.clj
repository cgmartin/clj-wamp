(ns ^{:author "Ryan Sundberg"
      :doc "WAMP V2 application node"}
  clj-wamp.node
  (:require
    [clojure.tools.logging :as log]
    [cheshire.core :as json]
    [gniazdo.core :as ws]
    [clj-wamp.core :as core]
    [clj-wamp.v2 :as wamp])
  (:import
    [org.eclipse.jetty.websocket.client WebSocketClient]))

(defn- handle-connect
  [{:keys [registrations on-call] :as instance} session]
  (log/debug "Connected to WAMP router with session" session)
  (reset! registrations [on-call {} {}]))

(defn- handle-message
  [instance msg-str]
  (let [msg-data (try (json/decode msg-str)
                      (catch com.fasterxml.jackson.core.JsonParseException ex
                        [nil nil]))]
    (log/debug "Handling message" msg-str)
    (wamp/handle-message instance msg-data)))

(declare connect)

(defn- handle-close
  [instance code reason]
  (log/debug "Disconnected from WAMP router:" code reason)
  (reset! (:socket instance) nil)
  (when @(:reconnect-state instance)
    (connect instance)))

(defn- handle-error
  [instance ex]
  (log/error ex "WAMP router error"))

(defn publish!
  "Publish an event"
  ([instance event-uri]
   (publish! instance event-uri nil))
  ([instance event-uri seq-args]
   (publish! instance event-uri seq-args nil))
  ([instance event-uri seq-args kw-args]
   (wamp/publish instance (core/new-rand-id) {} event-uri seq-args kw-args)))

(defn connect! [instance]
  (log/debug "Connecting clj-wamp node")
  (reset! (:reconnect-state instance) (:reconnect? instance))
  (swap! (:socket instance)
         (fn [socket]
           (when (nil? socket)
             (let [socket (ws/connect
                            (:router-uri instance)
                            :client (:client instance)
                            :headers {}
                            :subprotocols [wamp/subprotocol-id]
                            :on-connect (partial handle-connect instance)
                            :on-receive (partial handle-message instance)
                            :on-close (partial handle-close instance)
                            :on-error (partial handle-error instance))]
               socket))))
  (log/debug "Sending HELLO")
  (wamp/hello instance)
  instance)

(defn disconnect! [instance]
  (reset! (:reconnect-state instance) false)  
  (swap! (:socket instance)
         (fn [socket]
           (when (some? socket)
             (ws/close socket)
             nil))))

(defn create [{:keys [router-uri realm on-call] :as conf}]
  {:pre [(string? router-uri)
         (string? realm)]}
  (let [client (ws/client (java.net.URI. router-uri))]
    (.start ^WebSocketClient client)
    (merge 
      {:reconnect? true
       :debug? false}
      conf
      {:client client
       :socket (atom nil)
       :reconnect-state (atom false)
       :registrations (atom nil)})))
