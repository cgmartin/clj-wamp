(ns clj-wamp.client-test
  (:require-macros [cemerick.cljs.test :refer (is deftest are testing)]
                   [cljs.core :refer (with-redefs)])
  (:require [cemerick.cljs.test :as t]
            [clj-wamp.client :as client]
            [clj-wamp.websocket :as websocket]))

;; on-open utility

(def wamp-opened (atom nil))

(defn- on-open [ws sess-id]
  (reset! wamp-opened {:sess-id sess-id}))

(defn- wamp-opened? [sess-id]
  (let [open-sess-id (@wamp-opened :sess-id)]
    (reset! wamp-opened nil)
    (is (= sess-id open-sess-id))))

;; on-closed utility

(def wamp-closed (atom nil))

(defn- on-close []
  (reset! wamp-closed true))

(defn- wamp-closed? []
  (let [closed? @wamp-closed]
    (reset! wamp-closed nil)
    (is (true? closed?))))

;; on-event utility

(def wamp-event (atom nil))

(defn- on-event [ws topic event]
  (reset! wamp-event {:topic topic :event event}))

(defn- wamp-event? [topic & [event]]
  (let [event-in @wamp-event]
    (reset! wamp-event nil)
    (is (= topic (event-in :topic)))
    (when event
      (is (= event (event-in :event))))))

;; messages sent to server

(def wamp-out (atom []))

(defn wamp-sent? [messages]
  (let [sent @wamp-out]
    (reset! wamp-out [])
    (is (= messages sent))))

(defn wamp-sent-re? [re]
  (let [sent (first @wamp-out)
        match (re-matches re sent)]
    (swap! wamp-out rest)
    (is match)
    (second match)))

(deftype WebSocketMock [close-cb wamp-out]
  Object
  (close [this] (close-cb))
  (send [this msg] (swap! wamp-out conj msg)))

;; messages received from server

(def wamp-in (atom nil))

(defn receive! [data]
  (@wamp-in data))

(defn websocket-client-mock [uri & [opts]]
  (let [ws (WebSocketMock. (opts :on-close) wamp-out)]
    (reset! wamp-in (fn [data] ((opts :on-message) ws data)))
    ws))


(deftest wamp-handler-test
  (let [ws (client/wamp-handler "ws://localhost:8080/ws"
             {:websocket-client websocket-client-mock
              :on-open  on-open
              :on-close on-close
              :on-event on-event})]
    ; on open
    (receive! "[0,\"1375307829812-1\",1,\"clj-wamp/1.1.0\"]")
    (wamp-opened? "1375307829812-1")

    ; prefix
    (client/prefix! ws "event" "http://clj-wamp-cljs/event#")
    (wamp-sent? ["[1,\"event\",\"http://clj-wamp-cljs/event#\"]"])

    ; subscribe
    (client/subscribe! ws "event:chat")
    (wamp-sent? ["[5,\"event:chat\"]"])

    ; publish
    (client/publish! ws "event:chat" "foo")
    (wamp-sent? ["[7,\"event:chat\",\"foo\"]"])

    ; on event
    (receive! "[8,\"http://clj-wamp-cljs/event#chat\",\"foo\"]")
    (wamp-event? "http://clj-wamp-cljs/event#chat" "foo")

    ; unsubscribe
    (client/unsubscribe! ws "event:chat")
    (wamp-sent? ["[6,\"event:chat\"]"])

    ; rpc - success
    (let [rpc-result (atom nil)]
      (client/rpc! ws "rpc:echo" ["test"]
        (fn [ws ok? r]
          (reset! rpc-result {:success? ok? :result r})))
      (let [call-id (wamp-sent-re? #"\[2,\"(.+)\",\"rpc:echo\",\"test\"\]")]
        (receive! (str "[3,\"" call-id "\",\"test\"]"))
        (is (= {:success? true :result "test"} @rpc-result))))

    ; rpc - failure
    (let [rpc-result (atom nil)]
      (client/rpc! ws "rpc:echo" ["test"]
        (fn [ws ok? r]
          (reset! rpc-result {:success? ok? :result r})))
      (let [call-id (wamp-sent-re? #"\[2,\"(.+)\",\"rpc:echo\",\"test\"\]")]
        (receive! (str "[4,\"" call-id "\",\"http://api.wamp.ws/error#internal\","
                    "\"internal error\",\"An exception\"]"))
        (is (= {:success? false
                :result ["http://api.wamp.ws/error#internal"
                         "internal error" "An exception"]}
              @rpc-result))))))


(deftest authsign-test
  (is (= "UTAHauAu+QJDfg5k2v2hUIjxsrPBGnRp+Wdy1M3Kbks="
        (client/authsign "foo" "qnscAdgRlkIhAUPY44oiexBKtQbGY0orf7OV1I50"))))