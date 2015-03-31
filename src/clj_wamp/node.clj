(ns ^{:author "Ryan Sundberg"
      :doc "WAMP V2 server node"}
  clj-wamp.node
  (:require
    [clojure.tools.logging :as log]
    [cheshire.core :as json]
    [gniazdo.core :as ws]
    [clj-wamp.v2 :as wamp]))

(defn- handle-connect
  [instance session]
  (log/debug "Connected to WAMP router with session" session)
  (ws/send-msg @(:socket instance)
               (wamp/hello instance)))

(defn- handle-message
  [instance msg-str]
  (let [msg-data (try (json/decode msg-str)
                      (catch com.fasterxml.jackson.core.JsonParseException ex
                        [nil nil]))]
    (wamp/handle-message msg-data)))

(declare connect)

(defn- handle-close
  [instance code reason]
  (reset! (:socket instance) nil)
  (when @(:reconnect-state instance)
    (connect instance)))

(defn- handle-error
  [instance ex]
  (log/error ex "WAMP router error"))

(defn connect [instance]
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
  instance)

(defn disconnect [instance]
  (reset! (:reconnect-state instance) false)  
  (swap! (:socket instance)
         (fn [socket]
           (when (some? socket)
             (ws/close socket)
             nil))))

(defn create [{:keys [router-uri realm on-call] :as conf}]
  {:pre [(string? router-uri)
         (string? realm)]}
  (merge 
    {:reconnect? true}
    conf
    {:client (ws/client (java.net.URI. router-uri))
     :socket (atom nil)
     :reconnect-state (atom false)
     :registrations (atom [on-call {} {}])}))
