(ns clj-wamp.server-test
  (:use clojure.test
        clj-wamp.server)
  (:require [org.httpkit.server :as httpkit]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

;; :on-open callback test utils

(def ws-opened (atom nil))

(defn ws-on-open-cb []
  (fn [sess-id]       ; TODO?
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
        (not (nil? close-info))
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
        (not (nil? call-info))
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
        (not (nil? call-info))
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
        (not (nil? call-info))
        (= sess-id (call-info :sess-id))
        (= topic   (call-info :topic))
        (= call-id (call-info :call-id))))))

;; test rpc functions

(defn rpc-add [sess-id & params]
  {:result (apply + params)})

(defn rpc-give-error [sess-id & params]
  {:error {:uri "http://example.com/error#give-error"
           :message "Test error"
           :description "Test error description"}})

(defn rpc-as-is [f]
  (fn [sess-id & params]
    (apply f params)))


;; subscription handlers

(def sub (atom nil))
(def sub-after (atom nil))

(defn on-sub?
  [sess-id topic]
  (reset! sub {:sess-id sess-id
               :topic   topic})
  true)

(defn subscribed? [sess-id topic]
  (let [sub-msg @sub]
    (reset! sub nil)
    (or
      (and (nil? sess-id) (nil? sub-msg))
      (and
        (not (nil? sub-msg))
        (= sess-id (sub-msg :sess-id))
        (= topic   (sub-msg :topic))))))

(defn on-after-sub
  [sess-id topic]
  (reset! sub-after {:sess-id sess-id
                     :topic   topic}))

(defn after-sub?
  [sess-id topic]
  (let [sub-msg @sub-after]
    (reset! sub-after nil)
    (or
      (and (nil? sess-id) (nil? sub-msg))
      (and
        (not (nil? sub-msg))
        (= sess-id (sub-msg :sess-id))
        (= topic   (sub-msg :topic))))))

;; publish handlers

(def pub (atom nil))
(def pub-after (atom nil))

(defn on-pub
  [sess-id topic event exclude eligible]
  (reset! pub {:sess-id  sess-id
               :topic    topic
               :event    event
               :exclude  exclude
               :eligible eligible})
  [sess-id topic event exclude eligible])

(defn published? [sess-id topic event]
  (let [pub-msg @pub]
    (reset! pub nil)
    (or
      (and (nil? sess-id) (nil? pub-msg))
      (and
        (not (nil? pub-msg))
        (= sess-id (pub-msg :sess-id))
        (= topic   (pub-msg :topic))
        (= event   (pub-msg :event))))))

(defn on-after-pub
  [sess-id topic event exclude eligible]
  (reset! pub-after {:sess-id  sess-id
                     :topic    topic
                     :event    event
                     :exclude  exclude
                     :eligible eligible}))

(defn after-pub? [sess-id topic event]
  (let [pub-msg @pub-after]
    (reset! pub-after nil)
    (or
      (and (nil? sess-id) (nil? pub-msg))
      (and
        (not (nil? pub-msg))
        (= sess-id (pub-msg :sess-id))
        (= topic   (pub-msg :topic))
        (= event   (pub-msg :event))))))

;; unsubscribe handlers

(def unsub (atom nil))

(defn on-unsub
  [sess-id topic]
  (reset! unsub {:sess-id sess-id
                 :topic   topic})
  true)

(defn unsubscribed? [sess-id topic]
  (let [unsub-msg @unsub]
    (reset! unsub nil)
    (or
      (and (nil? sess-id) (nil? unsub-msg))
      (and
        (= sess-id (unsub-msg :sess-id))
        (= topic   (unsub-msg :topic))))))

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

(defn rpc-url [path] (str rpc-base-url path))
(defn evt-url [path] (str evt-base-url path))

(def test-handler-callbacks
  {:on-open  (ws-on-open-cb)
   :on-close (ws-on-close-cb)
   :on-call {(rpc-url "add")        rpc-add        ; returns a map with result
             (rpc-url "subtract")   (rpc-as-is -)  ; fn returns value as-is
             (rpc-url "give-error") rpc-give-error
             :on-before             rpc-on-before-call
             :on-after-error        rpc-on-after-call-error
             :on-after-success      rpc-on-after-call-success}
   :on-subscribe {(evt-url "prefix*")    on-sub?
                  (evt-url "chat")       on-sub?
                  (evt-url "no-handler") true
                  :on-after              on-after-sub}
   :on-publish   {(evt-url "prefix*")    on-pub
                  (evt-url "chat")       on-pub
                  (evt-url "no-handler") true
                  :on-after              on-after-pub}
   :on-unsubscribe on-unsub})


(deftest http-kit-handler-test
  (let [close (atom nil)
        send  (atom nil)]
    (with-redefs-fn
      {#'httpkit/on-close   (fn [ch cb] (reset! close cb))
       #'httpkit/on-receive (fn [ch cb] (reset! send cb))}
      #(let [sess-id (http-kit-handler client-receive test-handler-callbacks)]
         ; Test init
         (is (ws-opened? sess-id))
         (msg-received? [TYPE-ID-WELCOME, sess-id, 1, project-version])

         ; Pub/Sub Events
         (@send (json/encode [TYPE-ID-PREFIX, "event", evt-base-url]))
         (@send (json/encode [TYPE-ID-SUBSCRIBE, "event:chat"]))
         (is (subscribed? sess-id (evt-url "chat")))
         (is (after-sub?  sess-id (evt-url "chat")))
         (@send (json/encode [TYPE-ID-PUBLISH, "event:chat", "short-topic"]))
         (is (published? sess-id (evt-url "chat") "short-topic"))
         (is (after-pub? sess-id (evt-url "chat") "short-topic"))
         (msg-received? [TYPE-ID-EVENT, (str evt-base-url "chat"), "short-topic"])

         (@send (json/encode [TYPE-ID-PUBLISH, (evt-url "chat"), "full-topic"]))
         (is (published? sess-id (evt-url "chat") "full-topic"))
         (is (after-pub? sess-id (evt-url "chat") "full-topic"))
         (msg-received? [TYPE-ID-EVENT, (evt-url "chat"), "full-topic"])

         (@send (json/encode [TYPE-ID-PUBLISH, "event:chat", "exclude-me", true]))
         (is (published? sess-id (evt-url "chat") "exclude-me"))
         (is (after-pub? sess-id (evt-url "chat") "exclude-me"))
         (msg-received? nil)

         (@send (json/encode [TYPE-ID-UNSUBSCRIBE, "event:chat"]))
         (is (unsubscribed? sess-id (evt-url "chat")))
         (@send (json/encode [TYPE-ID-PUBLISH, "event:chat", "unsubscribed"]))
         (is (published? sess-id (evt-url "chat") "unsubscribed"))
         (is (after-pub? sess-id (evt-url "chat") "unsubscribed"))
         (msg-received? nil)

         (@send (json/encode [TYPE-ID-SUBSCRIBE, "event:prefix123"]))
         (is (subscribed? sess-id (evt-url "prefix123")))
         (is (after-sub?  sess-id (evt-url "prefix123")))
         (@send (json/encode [TYPE-ID-PUBLISH, "event:prefix123", "prefix-event"]))
         (is (published? sess-id (evt-url "prefix123") "prefix-event"))
         (is (after-pub? sess-id (evt-url "prefix123") "prefix-event"))
         (msg-received? [TYPE-ID-EVENT, (evt-url "prefix123"), "prefix-event"])

         (@send (json/encode [TYPE-ID-SUBSCRIBE, "event:no-handler"]))
         (is (subscribed? nil nil))      ; callback only run on handlers
         (is (after-sub?  sess-id (evt-url "no-handler")))
         (@send (json/encode [TYPE-ID-PUBLISH, "event:no-handler", "no-handler-event"]))
         (is (published? nil nil nil))   ; callback only run on handlers
         (is (after-pub? sess-id (evt-url "no-handler") "no-handler-event"))
         (msg-received? [TYPE-ID-EVENT, (evt-url "no-handler"), "no-handler-event"])

         (@send (json/encode [TYPE-ID-SUBSCRIBE, "event:none"]))
         (is (subscribed? nil nil))
         (is (after-sub?  nil nil))
         (@send (json/encode [TYPE-ID-PUBLISH, "event:none", "no-event"]))
         (is (published? nil nil nil))
         (is (after-pub? nil nil nil))
         (msg-received? nil)

         ; RPC Messaging
         (@send (json/encode [TYPE-ID-PREFIX, "api", rpc-base-url]))
         (@send (json/encode [TYPE-ID-CALL, "short-rpc", "api:add", 23, 99]))
         (is (rpc-before-call? sess-id (rpc-url "add") "short-rpc"))
         (is (rpc-after-call-success? sess-id (rpc-url "add") "short-rpc"))
         (msg-received? [TYPE-ID-CALLRESULT, "short-rpc", 122])

         (@send (json/encode [TYPE-ID-CALL, "full-rpc", "http://example.com/api#add", 1, 2]));
         (is (rpc-before-call? sess-id (rpc-url "add") "full-rpc"))
         (is (rpc-after-call-success? sess-id (rpc-url "add") "full-rpc"))
         (msg-received? [TYPE-ID-CALLRESULT, "full-rpc", 3])

         (@send (json/encode [TYPE-ID-CALL, "as-is-rpc", "api:subtract", 13, 7]))
         (is (rpc-before-call? sess-id (rpc-url "subtract") "as-is-rpc"))
         (is (rpc-after-call-success? sess-id (rpc-url "subtract") "as-is-rpc"))
         (msg-received? [TYPE-ID-CALLRESULT, "as-is-rpc", 6])

         (@send (json/encode [TYPE-ID-CALL, "exception-rpc", "api:add", 23, "abc"]))
         (is (rpc-before-call? sess-id (rpc-url "add") "exception-rpc"))
         (is (rpc-after-call-error? sess-id (rpc-url "add") "exception-rpc"))
         (msg-received? [TYPE-ID-CALLERROR, "exception-rpc",
                         "http://api.wamp.ws/error#internal",
                         "internal error" "java.lang.String cannot be cast to java.lang.Number"])

         (@send (json/encode [TYPE-ID-CALL, "error-rpc", "api:give-error", 1, 2]))
         (is (rpc-before-call? sess-id (rpc-url "give-error") "error-rpc"))
         (is (rpc-after-call-error? sess-id (rpc-url "give-error") "error-rpc"))
         (msg-received? [TYPE-ID-CALLERROR, "error-rpc",
                         "http://example.com/error#give-error",
                         "Test error" "Test error description"])

         (@send (json/encode [TYPE-ID-CALL, "not-found-rpc", "api:not-found", 1, 2]))
         (is (rpc-before-call? nil nil nil))        ; callback only run on existing calls
         (is (rpc-after-call-error? sess-id (rpc-url "not-found") "not-found-rpc"))
         (msg-received? [TYPE-ID-CALLERROR, "not-found-rpc",
                         "http://api.wamp.ws/error#notfound", "not found error"])

         ; Test close
         (is (not (nil? (get @clients sess-id))))
         (@close "close-status")
         (is (ws-closed? sess-id "close-status"))
         (is (nil? (get @clients sess-id)))
         (is (= {} @topics))
         ))))

