(ns clj-wamp.server-test
  (:use clojure.test
        clj-wamp.server)
  (:require [org.httpkit.server :as httpkit]
            [cheshire.core :as json]))

(def wamp-opened (atom nil))
(def wamp-closed (atom nil))

(defn ws-on-open-cb []
  (fn [sess-id]
    (reset! wamp-opened {:sess-id sess-id})))

(defn ws-on-close-cb []
  (fn [sess-id status]
    (reset! wamp-closed {:sess-id sess-id
                         :status  status})))


(defn ws-rpc-add [& params]
  {:result (apply + params)})

(defn ws-rpc-give-error [& params]
  {:error {:uri "http://example.com/error#give-error"
           :message "Test error"
           :description "Test error description"}})

(def ws-rpc-not-found-error
  {:uri "http://example.com/error#not-found"
   :message "RPC topic not found"})

(def test-handler-callbacks
  {:on-open  (ws-on-open-cb)
   :on-close (ws-on-close-cb)
   :on-call {"http://example.com/api#add" ws-rpc-add
             "http://example.com/api#give-error" ws-rpc-give-error
             :not-found-error ws-rpc-not-found-error}})


(deftest http-kit-handler-test
  (let [close-cb    (atom nil)
        receive-cb  (atom nil)
        client-msgs (atom [])]
    (with-redefs-fn
      {#'httpkit/on-close   (fn [ch cb] (reset! close-cb cb))
       #'httpkit/on-receive (fn [ch cb] (reset! receive-cb cb))}
      #(let [sess-id (http-kit-handler
                       (fn [data] (swap! client-msgs conj data))
                       test-handler-callbacks)]
         ; Test init
         (is (= sess-id (get @wamp-opened :sess-id)))
         (is (= [TYPE-ID-WELCOME, sess-id, 1, project-version] (last @client-msgs)))

         ; Pub/Sub Events
         (@receive-cb (json/encode [TYPE-ID-PREFIX, "event", "http://example.com/event#"]))
         (@receive-cb (json/encode [TYPE-ID-SUBSCRIBE, "event:chat"]))
         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "event:chat", "short-topic"]))
         (is (= [TYPE-ID-EVENT, "http://example.com/event#chat", "short-topic"]
               (last @client-msgs)))
         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "http://example.com/event#chat", "full-topic"]))
         (is (= [TYPE-ID-EVENT, "http://example.com/event#chat", "full-topic"]
               (last @client-msgs)))
         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "event:chat", "exclude-me", true]))
         (is (not= [TYPE-ID-EVENT, "http://example.com/event#chat", "exclude-me"]
               (last @client-msgs)))
         (@receive-cb (json/encode [TYPE-ID-UNSUBSCRIBE, "event:chat"]))
         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "event:chat", "unsubscribed"]))
         (is (not= [TYPE-ID-EVENT, "http://example.com/event#chat", "unsubscribed"]
               (last @client-msgs)))

         ; RPC Messaging
         (@receive-cb (json/encode [TYPE-ID-PREFIX, "api", "http://example.com/api#"]))
         (@receive-cb (json/encode [TYPE-ID-CALL, "short-rpc", "api:add", 23, 99]));
         (is (= [TYPE-ID-CALLRESULT, "short-rpc", 122] (last @client-msgs)))
         (@receive-cb (json/encode [TYPE-ID-CALL, "full-rpc", "http://example.com/api#add", 1, 2]));
         (is (= [TYPE-ID-CALLRESULT, "full-rpc", 3] (last @client-msgs)))
         (@receive-cb (json/encode [TYPE-ID-CALL, "error-rpc", "api:give-error", 1, 2]))
         (is (= [TYPE-ID-CALLERROR, "error-rpc", "http://example.com/error#give-error",
                 "Test error" "Test error description"] (last @client-msgs)))
         (@receive-cb (json/encode [TYPE-ID-CALL, "not-found-rpc", "api:not-found", 1, 2]))
         (is (= [TYPE-ID-CALLERROR, "not-found-rpc", "http://example.com/error#not-found",
                 "RPC topic not found"] (last @client-msgs)))

         ; Test close
         (is (not (nil? (get @clients sess-id))))
         (@close-cb "close-status")
         (is (= {:sess-id sess-id
                 :status  "close-status"} @wamp-closed))
         (is (nil? (get @clients sess-id)))
         (is (= {} @topics))
         ))))

