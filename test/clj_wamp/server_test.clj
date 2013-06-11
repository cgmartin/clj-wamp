(ns clj-wamp.server-test
  (:use clojure.test
        clj-wamp.server)
  (:require [org.httpkit.server :as httpkit]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

;; :on-open callback test utils

(def ws-opened (atom nil))

(defn ws-on-open-cb []
  (fn [sess-id]
    (reset! ws-opened {:sess-id sess-id})))

(defn ws-opened? [sess-id]
  (let [open-sess-id (@ws-opened :sess-id)]
    (reset! ws-opened nil)
    (= sess-id open-sess-id)))

;; :on-close callback test utils

(def ws-closed (atom nil))

(defn ws-on-close-cb []
  (fn [sess-id status]
    (reset! ws-closed {:sess-id sess-id
                         :status  status})))

(defn ws-closed? [sess-id status]
  (let [close-info @ws-closed]
    (reset! ws-closed nil)
    (or (and (nil? sess-id) (nil? close-info))
      (and
        (= sess-id (close-info :sess-id))
        (= status  (close-info :status))))))

;; :on-call :before-call test utils

(def rpc-before-call (atom nil))

(defn rpc-on-before-call
  [sess-id topic call-id call-params]
  (reset! rpc-before-call {:sess-id     sess-id
                           :topic       topic
                           :call-id     call-id
                           :call-params call-params})
  [sess-id topic call-id call-params])

(defn rpc-before-call?
  [sess-id topic call-id]
  (let [call-info @rpc-before-call]
    (reset! rpc-before-call nil)
    (or
      (and (nil? sess-id) (nil? call-info))
      (and
        (= sess-id (call-info :sess-id))
        (= topic   (call-info :topic))
        (= call-id (call-info :call-id))))))

;; :on-call :on-after-success test utils

(def rpc-after-call-success (atom nil))

(defn rpc-on-after-call-success
  [sess-id topic call-id result]
  (reset! rpc-after-call-success {:sess-id  sess-id
                                  :topic    topic
                                  :call-id  call-id
                                  :result   result})
  [sess-id topic call-id result])

(defn rpc-after-call-success?
  [sess-id topic call-id]
  (let [call-info @rpc-after-call-success]
    (reset! rpc-after-call-success nil)
    (or
      (and (nil? sess-id) (nil? call-info))
      (and
        (= sess-id (call-info :sess-id))
        (= topic   (call-info :topic))
        (= call-id (call-info :call-id))))))

;; :on-call :on-after-error test utils

(def rpc-after-call-error (atom nil))

(defn rpc-on-after-call-error
  [sess-id topic call-id error]
  (reset! rpc-after-call-error {:sess-id sess-id
                                :topic   topic
                                :call-id call-id
                                :error   error})
  [sess-id topic call-id error])

(defn rpc-after-call-error?
  [sess-id topic call-id]
  (let [call-info @rpc-after-call-error]
    (reset! rpc-after-call-error nil)
    (or
      (and (nil? sess-id) (nil? call-info))
      (and
        (= sess-id (call-info :sess-id))
        (= topic   (call-info :topic))
        (= call-id (call-info :call-id))))))

;; test rpc functions

(defn rpc-add [& params]
  {:result (apply + params)})

(defn rpc-give-error [& params]
  {:error {:uri "http://example.com/error#give-error"
           :message "Test error"
           :description "Test error description"}})


;; pubsub handlers

(def pubsub-sub (atom nil))

(defn pubsub-on-sub
  [sess-id topic]
  (reset! pubsub-sub {:sess-id sess-id
                      :topic   topic})
  true)

(defn subscribed? [sess-id topic]
  (let [sub-msg @pubsub-sub]
    (reset! pubsub-sub nil)
    (or
      (and (nil? sess-id) (nil? sub-msg))
      (and
        (= sess-id (sub-msg :sess-id))
        (= topic (sub-msg :topic))))))

(def pubsub-pub (atom nil))

(defn pubsub-on-pub
  [sess-id topic event exclude eligible]
  (reset! pubsub-pub {:sess-id  sess-id
                      :topic    topic
                      :event    event
                      :exclude  exclude
                      :eligible eligible})
  [sess-id topic event exclude eligible])

(defn published? [sess-id topic event]
  (let [pub-msg @pubsub-pub]
    (reset! pubsub-pub nil)
    (or
      (and (nil? sess-id) (nil? pub-msg))
      (and
        (= sess-id (pub-msg :sess-id))
        (= topic (pub-msg :topic))
        (= event (pub-msg :event))))))

;; data received test utils

(def client-msgs (atom []))

(defn client-receive [data]
  (swap! client-msgs conj data))

(defn msg-received? [msg]
  (let [last-msg (last @client-msgs)]
    (reset! client-msgs [])
    (is (= msg last-msg))))

;; topic base urls

(def rpc-base-url "http://example.com/api#")
(def evt-base-url "http://example.com/event#")


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
  (let [close (atom nil)
        send  (atom nil)]
    (with-redefs-fn
      {#'httpkit/on-close   (fn [ch cb] (reset! close cb))
       #'httpkit/on-receive (fn [ch cb] (reset! send cb))}
      #(let [sess-id (http-kit-handler client-receive test-handler-callbacks)]
         ; Test init
         (is (ws-opened? sess-id))
         (is (msg-received? [TYPE-ID-WELCOME, sess-id, 1, project-version]))

         ; Pub/Sub Events
         (@send (json/encode [TYPE-ID-PREFIX, "event", evt-base-url]))
         (@send (json/encode [TYPE-ID-SUBSCRIBE, "event:chat"]))
         (is (subscribed? sess-id (str evt-base-url "chat")))
         (@send (json/encode [TYPE-ID-PUBLISH, "event:chat", "short-topic"]))
         (is (published? sess-id (str evt-base-url "chat") "short-topic"))
         (is (msg-received? [TYPE-ID-EVENT, (str evt-base-url "chat"), "short-topic"]))

         (@send (json/encode [TYPE-ID-PUBLISH, (str evt-base-url "chat"), "full-topic"]))
         (is (published? sess-id (str evt-base-url "chat") "full-topic"))
         (is (msg-received? [TYPE-ID-EVENT, (str evt-base-url "chat"), "full-topic"]))

         (@send (json/encode [TYPE-ID-PUBLISH, "event:chat", "exclude-me", true]))
         (is (published? sess-id (str evt-base-url "chat") "exclude-me"))
         (is (msg-received? nil))

         (@send (json/encode [TYPE-ID-UNSUBSCRIBE, "event:chat"]))
         (@send (json/encode [TYPE-ID-PUBLISH, "event:chat", "unsubscribed"]))
         (is (published? sess-id (str evt-base-url "chat") "unsubscribed"))
         (is (msg-received? nil))

         (@send (json/encode [TYPE-ID-SUBSCRIBE, "event:prefix123"]))
         (is (subscribed? sess-id (str evt-base-url "prefix123")))
         (@send (json/encode [TYPE-ID-PUBLISH, "event:prefix123", "prefix-event"]))
         (is (published? sess-id (str evt-base-url "prefix123") "prefix-event"))
         (is (msg-received? [TYPE-ID-EVENT, (str evt-base-url "prefix123"), "prefix-event"]))

         (@send (json/encode [TYPE-ID-SUBSCRIBE, "event:no-handler"]))
         (is (subscribed? nil nil))      ; callback only run on handlers
         (@send (json/encode [TYPE-ID-PUBLISH, "event:no-handler", "no-handler-event"]))
         (is (published? nil nil nil))   ; callback only run on handlers
         (is (msg-received? [TYPE-ID-EVENT, (str evt-base-url "no-handler"), "no-handler-event"]))

         (@send (json/encode [TYPE-ID-SUBSCRIBE, "event:none"]))
         (is (subscribed? nil nil))
         (@send (json/encode [TYPE-ID-PUBLISH, "event:none", "no-event"]))
         (is (published? nil nil nil))
         (is (msg-received? nil))

         ; RPC Messaging
         (@send (json/encode [TYPE-ID-PREFIX, "api", rpc-base-url]))
         (@send (json/encode [TYPE-ID-CALL, "short-rpc", "api:add", 23, 99]))
         (is (rpc-before-call? sess-id (str rpc-base-url "add") "short-rpc"))
         (is (rpc-after-call-success? sess-id (str rpc-base-url "add") "short-rpc"))
         (is (msg-received? [TYPE-ID-CALLRESULT, "short-rpc", 122]))

         (@send (json/encode [TYPE-ID-CALL, "full-rpc", "http://example.com/api#add", 1, 2]));
         (is (rpc-before-call? sess-id (str rpc-base-url "add") "full-rpc"))
         (is (rpc-after-call-success? sess-id (str rpc-base-url "add") "full-rpc"))
         (is (msg-received? [TYPE-ID-CALLRESULT, "full-rpc", 3]))

         (@send (json/encode [TYPE-ID-CALL, "error-rpc", "api:give-error", 1, 2]))
         (is (rpc-before-call? sess-id (str rpc-base-url "give-error") "error-rpc"))
         (is (rpc-after-call-error? sess-id (str rpc-base-url "give-error") "error-rpc"))
         (is (msg-received? [TYPE-ID-CALLERROR, "error-rpc",
                             "http://example.com/error#give-error",
                             "Test error" "Test error description"]))

         (@send (json/encode [TYPE-ID-CALL, "not-found-rpc", "api:not-found", 1, 2]))
         (is (rpc-before-call? nil nil nil))        ; callback only run on existing calls
         (is (rpc-after-call-error? nil nil nil))   ; callback only run on existing calls
         (is (msg-received? [TYPE-ID-CALLERROR, "not-found-rpc",
                             "http://api.wamp.ws/error#notfound", "not found error"]))

         ; Test close
         (is (not (nil? (get @clients sess-id))))
         (@close "close-status")
         (is (ws-closed? sess-id "close-status"))
         (is (nil? (get @clients sess-id)))
         (is (= {} @topics))
         ))))

