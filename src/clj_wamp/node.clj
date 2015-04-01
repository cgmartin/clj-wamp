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
  [{:keys [debug? registrations on-call] :as instance} session]
  (when debug?
    (log/debug "Connected to WAMP router with session" session))
  (reset! registrations [on-call {} {}]))

(defn- handle-message
  [{:keys [debug?] :as instance} msg-str]
  (let [msg-data (try (json/decode msg-str)
                      (catch com.fasterxml.jackson.core.JsonParseException ex
                        [nil nil]))]
    (when debug?
      (log/debug "WAMP message received:" msg-str))
    (wamp/handle-message instance msg-data)))

(declare connect!)

(defn- handle-close
  [{:keys [debug?] :as instance} code reason]
  (when debug?
    (log/debug "Disconnected from WAMP router:" code reason))
  (reset! (:socket instance) nil)
  (when @(:reconnect-state instance)
    (connect! instance)))

(defn- handle-error
  [instance ex]
  (log/error ex "WAMP socket error"))

(defn publish!
  "Publish an event"
  ([instance event-uri]
   (publish! instance event-uri nil))
  ([instance event-uri seq-args]
   (publish! instance event-uri seq-args nil))
  ([instance event-uri seq-args kw-args]
   (wamp/publish instance (core/new-rand-id) {} event-uri seq-args kw-args)))

(defn- try-connect [{:keys [debug? router-uri] :as instance}]
  (try 
    (swap! (:socket instance)
           (fn [socket]
             (when (nil? socket)
               (when debug?
                 (log/debug "Connecting to WAMP router at" router-uri))
               (let [socket (ws/connect
                              router-uri
                              :client (:client instance)
                              :headers {}
                              :subprotocols [wamp/subprotocol-id]
                              :on-connect (partial handle-connect instance)
                              :on-receive (partial handle-message instance)
                              :on-close (partial handle-close instance)
                              :on-error (partial handle-error instance))]
                 socket))))
    (wamp/hello instance)
    true
    (catch Exception e
      (log/error e "Failed to connect to WAMP router")
      false)))

(defn connect! [{:keys [reconnect-state reconnect? reconnect-wait-ms] :as instance}]
  (reset! reconnect-state reconnect?)
  (let [connected? (try-connect instance)]
    (if connected?
      instance
      (if @reconnect-state
        (do
          (Thread/sleep reconnect-wait-ms)
          (recur instance))
        instance))))

(defn disconnect! [{:keys [debug?] :as instance}]
  (reset! (:reconnect-state instance) false)  
  (swap! (:socket instance)
         (fn [socket]
           (when (some? socket)
             (when debug?
               (log/debug "Disconnecting from WAMP router"))
             (ws/close socket)
             nil))))

(defn create [{:keys [router-uri realm on-call] :as conf}]
  {:pre [(string? router-uri)
         (string? realm)]}
  (let [client (ws/client (java.net.URI. router-uri))]
    (.start ^WebSocketClient client)
    (merge 
      {:debug? false
       :reconnect? true
       :reconnect-wait-ms 10000}
      conf
      {:client client
       :socket (atom nil)
       :reconnect-state (atom false)
       :registrations (atom nil)})))
