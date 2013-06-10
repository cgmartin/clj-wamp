(ns clj-wamp.server-test
  (:use clojure.test
        clj-wamp.server)
  (:require [org.httpkit.server :as httpkit]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(def wamp-opened (atom nil))
(def wamp-closed (atom nil))

(defn ws-on-open-cb []
  (fn [sess-id]
    (reset! wamp-opened {:sess-id sess-id})))

(defn ws-on-close-cb []
  (fn [sess-id status]
    (reset! wamp-closed {:sess-id sess-id
                         :status  status})))

(def rpc-before-call (atom nil))

(defn rpc-on-before-call
  [sess-id topic call-id call-params]
  (reset! rpc-before-call {:sess-id     sess-id
                           :topic       topic
                           :call-id     call-id
                           :call-params call-params})
  [sess-id topic call-id call-params])

(def rpc-after-call-success (atom nil))

(defn rpc-on-after-call-success
  [sess-id topic call-id result]
  (reset! rpc-after-call-success {:sess-id  sess-id
                                  :topic    topic
                                  :call-id  call-id
                                  :result   result})
  [sess-id topic call-id result])

(def rpc-after-call-error (atom nil))

(defn rpc-on-after-call-error
  [sess-id topic call-id error]
  (reset! rpc-after-call-error {:sess-id sess-id
                                :topic   topic
                                :call-id call-id
                                :error   error})
  [sess-id topic call-id error])

(defn rpc-add [& params]
  {:result (apply + params)})

(defn rpc-give-error [& params]
  {:error {:uri "http://example.com/error#give-error"
           :message "Test error"
           :description "Test error description"}})

(def rpc-not-found-error
  {:uri "http://example.com/error#not-found"
   :message "RPC topic not found"})

(def rpc-base-url "http://example.com/api#")
(def evt-base-url "http://example.com/event#")


(def pubsub-sub (atom nil))

(defn pubsub-on-sub
  [sess-id topic]
  (reset! pubsub-sub {:sess-id sess-id
                      :topic   topic})
  true)

(def pubsub-pub (atom nil))

(defn pubsub-on-pub
  [sess-id topic event exclude eligible]
  (reset! pubsub-pub {:sess-id  sess-id
                      :topic    topic
                      :event    event
                      :exclude  exclude
                      :eligible eligible})
  [sess-id topic event exclude eligible])

(def test-handler-callbacks
  {:on-open  (ws-on-open-cb)
   :on-close (ws-on-close-cb)
   :on-call {(str rpc-base-url "add")        rpc-add
             (str rpc-base-url "give-error") rpc-give-error
             :on-before        rpc-on-before-call
             :on-after-error   rpc-on-after-call-error
             :on-after-success rpc-on-after-call-success}
   :on-subscribe {(str evt-base-url "prefix*") pubsub-on-sub
                  (str evt-base-url "chat")    pubsub-on-sub
                  (str evt-base-url "no-handler") true}
   :on-publish   {(str evt-base-url "prefix*") pubsub-on-pub
                  (str evt-base-url "chat")    pubsub-on-pub
                  (str evt-base-url "no-handler") true}})


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
         (is (= "http://example.com/event#chat" (@pubsub-sub :topic)))
         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "event:chat", "short-topic"]))
         (is (= "short-topic" (@pubsub-pub :event)))
         (is (= [TYPE-ID-EVENT, "http://example.com/event#chat", "short-topic"]
               (last @client-msgs)))

         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "http://example.com/event#chat", "full-topic"]))
         (is (= "full-topic" (@pubsub-pub :event)))
         (is (= [TYPE-ID-EVENT, "http://example.com/event#chat", "full-topic"]
               (last @client-msgs)))

         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "event:chat", "exclude-me", true]))
         (is (= "exclude-me" (@pubsub-pub :event)))
         (is (not= [TYPE-ID-EVENT, "http://example.com/event#chat", "exclude-me"]
               (last @client-msgs)))

         (@receive-cb (json/encode [TYPE-ID-UNSUBSCRIBE, "event:chat"]))
         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "event:chat", "unsubscribed"]))
         (is (= "unsubscribed" (@pubsub-pub :event)))
         (is (not= [TYPE-ID-EVENT, "http://example.com/event#chat", "unsubscribed"]
               (last @client-msgs)))

         (@receive-cb (json/encode [TYPE-ID-SUBSCRIBE, "event:prefix123"]))
         (is (= "http://example.com/event#prefix123" (@pubsub-sub :topic)))
         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "event:prefix123", "prefix-event"]))
         (is (= "prefix-event" (@pubsub-pub :event)))
         (is (= [TYPE-ID-EVENT, "http://example.com/event#prefix123", "prefix-event"]
               (last @client-msgs)))

         (@receive-cb (json/encode [TYPE-ID-SUBSCRIBE, "event:no-handler"]))
         (is (not= "http://example.com/event#no-handler" (@pubsub-sub :topic)))
         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "event:no-handler", "no-handler-event"]))
         (is (not= "no-handler-event" (@pubsub-pub :event)))
         (is (= [TYPE-ID-EVENT, "http://example.com/event#no-handler", "no-handler-event"]
               (last @client-msgs)))

         (@receive-cb (json/encode [TYPE-ID-SUBSCRIBE, "event:none"]))
         (is (not= "http://example.com/event#none" (@pubsub-sub :topic)))
         (@receive-cb (json/encode [TYPE-ID-PUBLISH, "event:none", "no-event"]))
         (is (not= "no-event" (@pubsub-pub :event)))
         (is (not= [TYPE-ID-EVENT, "http://example.com/event#none", "no-event"]
               (last @client-msgs)))

         ; RPC Messaging
         (@receive-cb (json/encode [TYPE-ID-PREFIX, "api", "http://example.com/api#"]))
         (@receive-cb (json/encode [TYPE-ID-CALL, "short-rpc", "api:add", 23, 99]));
         (is (= "short-rpc" (@rpc-before-call :call-id)))
         (is (= "short-rpc" (@rpc-after-call-success :call-id)))
         (is (= [TYPE-ID-CALLRESULT, "short-rpc", 122] (last @client-msgs)))

         (@receive-cb (json/encode [TYPE-ID-CALL, "full-rpc", "http://example.com/api#add", 1, 2]));
         (is (= "full-rpc" (@rpc-before-call :call-id)))
         (is (= "full-rpc" (@rpc-after-call-success :call-id)))
         (is (= [TYPE-ID-CALLRESULT, "full-rpc", 3] (last @client-msgs)))

         (@receive-cb (json/encode [TYPE-ID-CALL, "error-rpc", "api:give-error", 1, 2]))
         (is (= "error-rpc" (@rpc-before-call :call-id)))
         (is (= "error-rpc" (@rpc-after-call-error :call-id)))
         (is (= [TYPE-ID-CALLERROR, "error-rpc", "http://example.com/error#give-error",
                 "Test error" "Test error description"] (last @client-msgs)))

         (@receive-cb (json/encode [TYPE-ID-CALL, "not-found-rpc", "api:not-found", 1, 2]))
         (is (not= "not-found-rpc" (@rpc-before-call :call-id))) ; callback only run on existing calls
         (is (not= "not-found-rpc" (@rpc-after-call-error :call-id))) ; callback only run on existing calls
         (is (= [TYPE-ID-CALLERROR, "not-found-rpc", "http://api.wamp.ws/error#notfound",
                 "not found error"] (last @client-msgs)))

         ; Test close
         (is (not (nil? (get @clients sess-id))))
         (@close-cb "close-status")
         (is (= {:sess-id sess-id
                 :status  "close-status"} @wamp-closed))
         (is (nil? (get @clients sess-id)))
         (is (= {} @topics))
         ))))

