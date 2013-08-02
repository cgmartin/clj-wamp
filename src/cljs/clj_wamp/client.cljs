(ns clj-wamp.client
  (:require [clojure.string :as string :refer [trim blank?]]
            [clj-wamp.websocket :as websocket]))

(def ^:const TYPE-ID-WELCOME     0) ; Server-to-client (Aux)
(def ^:const TYPE-ID-PREFIX      1) ; Client-to-server (Aux)
(def ^:const TYPE-ID-CALL        2) ; Client-to-server (RPC)
(def ^:const TYPE-ID-CALLRESULT  3) ; Server-to-client (RPC)
(def ^:const TYPE-ID-CALLERROR   4) ; Server-to-client (RPC)
(def ^:const TYPE-ID-SUBSCRIBE   5) ; Client-to-server (PubSub)
(def ^:const TYPE-ID-UNSUBSCRIBE 6) ; Client-to-server (PubSub)
(def ^:const TYPE-ID-PUBLISH     7) ; Client-to-server (PubSub)
(def ^:const TYPE-ID-EVENT       8) ; Server-to-client (PubSub)

(def rpc-callbacks (atom {}))

(defn- send!
  [ws msg]
  (let [m (JSON/stringify (clj->js msg))]
    (websocket/send! ws m)))

(defn prefix!
  [ws prefix curi]
  (let [msg [TYPE-ID-PREFIX prefix curi]]
    (send! ws msg)))

(defn rpc!
  [ws curi params callback]
  (let [call-id (.toString (.random js/Math) 36) ; TODO
        msg (into [TYPE-ID-CALL call-id curi] params)]
    (swap! rpc-callbacks assoc call-id callback)
    (send! ws msg)))

(defn subscribe!
  [ws curi]
  (let [msg [TYPE-ID-SUBSCRIBE curi]]
    (send! ws msg)))

(defn unsubscribe!
  [ws curi]
  (let [msg [TYPE-ID-UNSUBSCRIBE curi]]
    (send! ws msg)))

(defn publish!
  [ws curi event & more]
  (let [msg [TYPE-ID-PUBLISH curi event]]
    (send! ws (if more (into msg more) msg))))

(defn close!
  [ws]
  (websocket/close! ws))

(defn- on-message
  [ws data on-open on-event]
  (let [msg (js->clj (JSON/parse data))]
    ;(.log js/console "WAMP message" (pr-str msg))
    (condp = (first msg)
      TYPE-ID-WELCOME
      ; don't start until Welcome message received
      (on-open ws (second msg))

      TYPE-ID-CALLRESULT
      (let [call-id (second msg)
            result  (last msg)]
        (when-let [rpc-cb (get @rpc-callbacks call-id)]
          (rpc-cb ws true result)
          (swap! rpc-callbacks dissoc call-id)))

      TYPE-ID-CALLERROR
      (let [call-id  (second msg)
            err-info (drop 2 msg)]
        (when-let [rpc-cb (get @rpc-callbacks call-id)]
          (rpc-cb ws false err-info)
          (swap! rpc-callbacks dissoc call-id)))

      TYPE-ID-EVENT
      (let [topic (second msg)
            event (last msg)]
        (on-event ws topic event))

      (.log js/console "Unknown message" data))))

(defn wamp-handler
  [uri & [{:keys [on-open on-close on-event websocket-client
                  reconnect? next-reconnect]
           :or {reconnect? true
                websocket-client websocket/client}}]]
  (websocket-client uri
    {:reconnect? reconnect? :next-reconnect next-reconnect
     :on-message (fn [ws data] (on-message ws data on-open on-event))
     :on-close   on-close
     :protocol   "wamp"}))
