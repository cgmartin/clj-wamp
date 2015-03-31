(ns clj-wamp.server-v2
  ^{:author "Christopher Martin, Ryan Sundberg"
    :doc "Clojure implementation of the WebSocket Application Messaging Protocol, version 2"}
  (:use [clojure.core.incubator :only [dissoc-in]]
        [clojure.string :only [split trim lower-case]])
  (:require [clojure.java.io :as io]
            [org.httpkit.server :as httpkit]
            [org.httpkit.timer :as timer]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.data.codec.base64 :as base64]
            [clj-wamp.core :as core]
            [gniazdo.core :as ws])
  (:import [org.httpkit.server AsyncChannel]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private ^:const message-id-table
  {:HELLO 1
   :WELCOME 2
   :ABORT 3
   :CHALLENGE 4
   :AUTHENTICATE 5
   :GOODBYE 6
   :ERROR 8
   :PUBLISH 16
   :PUBLISHED 17
   :SUBSCRIBE 32
   :SUBSCRIBED 33
   :UNSUBSCRIBE 34
   :UNSUBSCRIBED 35
   :EVENT 36
   :CALL 48
   :CANCEL 49
   :RESULT 50
   :REGISTER 64
   :REGISTERED 65
   :UNREGISTER 66
   :UNREGISTERED 67
   :INVOCATION 68
   :INTERRUPT 69
   :YIELD 70})

(defmacro message-id
  [msg-keyword]
  (get message-id-table msg-keyword))

(comment
(def ^:const TYPE-ID-PREFIX      1) ; Client-to-server (Aux)
(def ^:const TYPE-ID-CALL        2) ; Client-to-server (RPC)
(def ^:const TYPE-ID-CALLRESULT  3) ; Server-to-client (RPC)
(def ^:const TYPE-ID-CALLERROR   4) ; Server-to-client (RPC)
(def ^:const TYPE-ID-SUBSCRIBE   5) ; Client-to-server (PubSub)
(def ^:const TYPE-ID-UNSUBSCRIBE 6) ; Client-to-server (PubSub)
(def ^:const TYPE-ID-PUBLISH     7) ; Client-to-server (PubSub)
(def ^:const TYPE-ID-EVENT       8) ; Server-to-client (PubSub)
)

; Predefined URIs
(def ^:const wamp-error-uri-table
  {:invalid-uri "wamp.error.invalid_uri"
   :no-such-procedure "wamp.error.no_such_procedure"
   :procedure-already-exists "wamp.error.procedure_already_exists"
   :no-such-registration "wamp.error.no_such_registration"
   :no-such-subscription "wamp.error.no_such_subscription"
   :invalid-argument "wamp.error.invalid_argument"
   :system-shutdown "wamp.error.system_shutdown"
   :close-realm "wamp.error.close_realm"
   :goodbye-and-out "wamp.error.goodbye_and_out"
   :not-authorized "wamp.error.not_authorized"
   :authorization-failed "wamp.error.authorization_failed"
   :no-such-realm "wamp.error.no_such_realm"
   :no-such-role "wamp.error.no_such_role"
   ; Errors below are not part of the specification
   :internal-error "wamp.error.internal-error"
   :application-error "wamp.error.application_error"
   })

(defmacro wamp-error-uri
  [error-keyword]
  (get wamp-error-uri-table error-keyword))

(comment
  ; v1
(def ^:const URI-WAMP-BASE            "http://api.wamp.ws/")
(def ^:const URI-WAMP-ERROR           (str URI-WAMP-BASE "error#"))
(def ^:const URI-WAMP-PROCEDURE       (str URI-WAMP-BASE "procedure#"))
(def ^:const URI-WAMP-CALL-AUTHREQ    (str URI-WAMP-PROCEDURE "authreq"))
(def ^:const URI-WAMP-CALL-AUTH       (str URI-WAMP-PROCEDURE "auth"))
(def ^:const URI-WAMP-TOPIC           (str URI-WAMP-BASE "topic#"))
(def ^:const URI-WAMP-ERROR-GENERIC   (str URI-WAMP-ERROR "generic"))
(def ^:const DESC-WAMP-ERROR-GENERIC  "generic error")
(def ^:const URI-WAMP-ERROR-INTERNAL  (str URI-WAMP-ERROR "internal"))
(def ^:const DESC-WAMP-ERROR-INTERNAL "internal error")
(def ^:const URI-WAMP-ERROR-NOTFOUND  (str URI-WAMP-ERROR "notfound"))
(def ^:const DESC-WAMP-ERROR-NOTFOUND "not found error")
(def ^:const DESC-WAMP-ERROR-NOAUTH   "unauthorized")
(def ^:const URI-WAMP-ERROR-NOAUTH    (str URI-WAMP-ERROR "unauthorized"))
)

;; Topic utils

(def client-topics (ref {}))
(def topic-clients (ref {}))

(defn topic-subscribe
  "Subscribes a websocket session to a topic."
  [topic sess-id]
  (dosync
    (alter topic-clients assoc-in [topic sess-id] true)
    (alter client-topics assoc-in [sess-id topic] true)))

(defn topic-unsubscribe
  "Unsubscribes a websocket session from a topic."
  [topic sess-id]
  (dosync
    (alter topic-clients dissoc-in [topic sess-id])
    (alter client-topics dissoc-in [sess-id topic])))

(defn- topic-send!
  "Sends an event to *all* websocket clients subscribed to a topic."
  [topic & data]
  (dosync
    (doseq [[sess-id _] (@topic-clients topic)]
      (apply core/send! sess-id data))))

(defn- topic-broadcast!
  "Send an event to websocket clients subscribed to a topic,
  except those excluded."
  [topic excludes & data]
  (let [excludes (if (sequential? excludes) excludes [excludes])]
    (dosync
      (doseq [[sess-id _] (@topic-clients topic)]
        (if (not-any? #{sess-id} excludes)
          (apply core/send! sess-id data))))))

(defn- topic-emit!
  "Sends an event to a specific list of websocket clients subscribed
  to a topic."
  [topic includes & data]
  (let [includes (if (sequential? includes) includes [includes])]
    (dosync
      (doseq [[sess-id _] (@topic-clients topic)]
        (if (some #{sess-id} includes)
          (apply core/send! sess-id data))))))

(defn get-topic-clients [topic]
  "Returns all client session ids within a topic."
  (if-let [clients (@topic-clients topic)]
    (keys clients)))

;; WAMP websocket send! utils

(defn- send!
  "Sends data to a websocket client."
  [sess-id & data]
  (dosync
    (let [channel-or-fn (core/get-client-channel sess-id)
          json-data     (json/encode data {:escape-non-ascii true})]
      (log/trace "Sending data:" data)
      (if (fn? channel-or-fn) ; application callback?
        (channel-or-fn data)
        (when channel-or-fn
          (httpkit/send! channel-or-fn json-data))))))

(defn send-welcome!
  "Sends a WAMP welcome message to a websocket client.
  [WELCOME, Session|id, Details|dict]"
  [sess-id]
  (core/send! sess-id (message-id :WELCOME) sess-id
              {:version core/project-version
               :roles
               {:dealer {}
                ;:callee {}
                }}))

(defn send-abort!
  "Sends an ABORT message to abort opening a session."
  [sess-id details-dict reason-uri]
  (core/send! sess-id (message-id :ABORT) details-dict reason-uri))

(defn send-goodbye!
  "Send a GOODBYE message"
  [sess-id details-dict reason-uri]
  (core/send! sess-id (message-id :GOODBYE) details-dict reason-uri))

(defn send-call-result!
  "Sends a WAMP call result message to a websocket client.
   [RESULT, CALL.Request|id, Details|dict]
   [RESULT, CALL.Request|id, Details|dict, YIELD.Arguments|list]
   [RESULT, CALL.Request|id, Details|dict, YIELD.Arguments|list, YIELD.ArgumentsKw|dict]"
  [sess-id call-id result]
  (core/send! sess-id (message-id :CALL) call-id result))

(defn send-error!
  "Sends a WAMP call error message to a websocket client.
   [ERROR, CALL, CALL.Request|id, Details|dict, Error|uri]
   [ERROR, CALL, CALL.Request|id, Details|dict, Error|uri, Arguments|list]
   [ERROR, CALL, CALL.Request|id, Details|dict, Error|uri, Arguments|list, ArgumentsKw|dict]"
  [sess-id call-id error-info error-uri]
  (core/send! sess-id (message-id :ERROR) call-id error-info error-uri))

(defn send-event!
  "Sends an event message to all clients in topic.
  [ TYPE_ID_EVENT , topicURI , event ]"
  [topic event]
  (topic-send! topic (message-id :EVENT) topic event))

(defn broadcast-event!
  "Sends an event message to all clients in a topic but those excluded."
  [topic event excludes]
  (topic-broadcast! topic excludes (message-id :EVENT) topic event))

(defn emit-event!
  "Sends an event message to specific clients in a topic"
  [topic event includes]
    (topic-emit! topic includes (message-id :EVENT) topic event))

;; WAMP callbacks

(defn- callback-rewrite
  "Utility for rewriting params with an optional callback fn."
  [callback & params]
  (if (fn? callback)
    (apply callback params)
    (when (or (nil? callback) (true? callback))
      params)))

(defn- on-close
  "Clean up clients and topics upon disconnect."
  [sess-id close-cb unsub-cb]
  (fn [status]
    (when (fn? close-cb) (close-cb sess-id status))
    (doseq [topic (dosync
                    (if-let [sess-topics (@client-topics sess-id)]
                      (for [[topic _] sess-topics]
                        (do
                          (topic-unsubscribe topic sess-id)
                          topic))))]
      (when (fn? unsub-cb) (unsub-cb sess-id topic)))
    (core/del-client sess-id)))

(defn- call-success
  [sess-id topic call-id result on-after-cb]
  (let [cb-params [sess-id topic call-id result]
        cb-params (apply callback-rewrite on-after-cb cb-params)
        [sess-id topic call-id result] cb-params]
    (send-call-result! sess-id call-id result)))

(defn- call-error
  [sess-id topic call-id error on-after-cb]
  (let [cb-params [sess-id topic call-id error]
        cb-params (apply callback-rewrite on-after-cb cb-params)
        [sess-id topic call-id error] cb-params
        {err-uri :uri err-msg :message err-desc :description kill :kill} error
        err-uri (if (nil? err-uri) (wamp-error-uri :application-error) err-uri)
        err-msg (if (nil? err-msg) "Application error" err-msg)]
    (send-error! sess-id call-id {:message err-msg
                                  :description err-desc} err-uri)
    (when kill (core/close-channel sess-id))))

; Optional session id for rpc calls
(def ^:dynamic *call-sess-id* nil)

;; WAMP-CRA Authentication

(defn hmac-sha-256
  "Generates a HMAC SHA256 hash."
  [^String key ^String data]
  (let [hmac-key (SecretKeySpec. (.getBytes key) "HmacSHA256")
        hmac     (doto (Mac/getInstance "HmacSHA256") (.init hmac-key))
        result   (.doFinal hmac (.getBytes data))]
    (String. (base64/encode result) "UTF-8")))

(defn auth-challenge
  "Generates a challenge hash used by the client to sign the secret."
  [sess-id auth-key auth-secret]
  (throw (Exception. "Fix hazard here"))
  (let [hmac-key (str auth-secret "-" (System/currentTimeMillis) "-" sess-id)]
    (hmac-sha-256 hmac-key auth-key)))

(defn- auth-sig-match?
  "Check whether the client signature matches the server's signature."
  [sess-id signature]
  (if-let [auth-sig (get-in @core/client-auth [sess-id :sig])]
    (= signature auth-sig)))

(defn- add-client-auth-sig
  "Stores the authorization signature on the server-side for later
  comparison with the client."
  [sess-id auth-key auth-secret challenge]
  (let [sig (hmac-sha-256 challenge auth-secret)]
    (dosync
      (alter core/client-auth assoc sess-id {:sig   sig
                                        :key   auth-key
                                        :auth? false}))
    sig))

(defn- add-client-auth-anon
  "Stores anonymous client metadata with the session."
  [sess-id]
  (dosync (alter core/client-auth assoc sess-id {:key :anon :auth? false})))

(defn client-auth-requested?
  "Checks if the authreq call has already occurred."
  [sess-id]
  (not (nil? (get-in @core/client-auth [sess-id :key]))))

(defn client-authenticated?
  "Checks if authentication has occurred."
  [sess-id]
  (get-in @core/client-auth [sess-id :auth?]))

(defn- permission?
  "Checks if a topic and category has permissions in a permission map.
    {:all true} ; or
    {:rpc true} ; or
    {:rpc {\"http://mytopic\" true}"
  [perms category topic]
  (or (get perms :all)
    (true? (get perms category))
    (get-in perms [category topic])))

(defn authorized?
  "Checks if the session is authorized for a message category and topic."
  [sess-id category topic perm-cb]
  (if-let [auth-key (get-in @core/client-auth [sess-id :key])]
    (let [perms (perm-cb sess-id auth-key)]
      (permission? perms category topic))))

(defn- create-call-authreq
  "Creates a callback for the authreq RPC call."
  [allow-anon? secret-cb]
  (fn [& [auth-key extra]]
    (dosync
      (if (client-authenticated? *call-sess-id*)
        {:error {:uri (wamp-error-uri :authorization-failed)
                 :message "already authenticated"}}
        (if (client-auth-requested? *call-sess-id*)
          {:error {:uri (wamp-error-uri :authorization-failed)
                   :message "authentication request already issued - authentication pending"}}

          (if (nil? auth-key)
            ; Allow anonymous auth?
            (if-not allow-anon?
              {:error {:uri (wamp-error-uri :not-authorized)
                       :message "authentication as anonymous is forbidden"}}
              (do
                (add-client-auth-anon *call-sess-id*)
                nil)) ; return nil
            ; Non-anonymous auth
            (if-let [auth-secret (secret-cb *call-sess-id* auth-key extra)]
              (let [challenge (auth-challenge *call-sess-id* auth-key auth-secret)]
                (add-client-auth-sig *call-sess-id* auth-key auth-secret challenge)
                challenge) ; return the challenge
              {:error {:uri (wamp-error-uri :authorization-failed)
                       :message "authentication key does not exist"}})))))))

(defn- expand-auth-perms
  "Expands shorthand permission maps into full topic permissions."
  [perms wamp-cbs]
  (let [wamp-perm-keys {:on-call      :rpc,
                        :on-subscribe :subscribe,
                        :on-publish   :publish}]
    (reduce conj
      (for [[wamp-key perm-key] wamp-perm-keys]
        {perm-key
         (into {}
           (remove nil?
             (for [[topic _] (get wamp-cbs wamp-key)]
               (if (permission? perms perm-key topic)
                 {topic true}))))}))))

(defn- create-call-auth
  "Creates a callback for the auth RPC call."
  [perm-cb wamp-cbs]
  (fn [& [signature]]
    (dosync
      (if (client-authenticated? *call-sess-id*)
        {:error {:uri (wamp-error-uri :authorization-failed)
                 :message "already authenticated"}}
        (if (not (client-auth-requested? *call-sess-id*))
          {:error {:uri (wamp-error-uri :authorization-failed)
                   :message "no authentication previously requested"}}
          (let [auth-key (get-in @core/client-auth [*call-sess-id* :key])]
            (if (or (= :anon auth-key) (auth-sig-match? *call-sess-id* signature))
              (do
                (alter core/client-auth assoc-in [*call-sess-id* :auth?] true)
                (expand-auth-perms (perm-cb *call-sess-id* auth-key) wamp-cbs))
              (do
                ; remove previous auth data, must request and authenticate again
                (alter core/client-auth dissoc *call-sess-id*)
                {:error {:uri (wamp-error-uri :not-authorized)
                         :message "signature for authentication request is invalid"}}))))))))

(defn- init-cr-auth
  "Initializes the authorization RPC calls (if configured)."
  [callbacks]
  callbacks
  #_(if-let [auth-cbs (callbacks :on-auth)]
    (let [allow-anon? (auth-cbs :allow-anon?)
          secret-cb   (auth-cbs :secret)
          perm-cb     (auth-cbs :permissions)]
      (merge-with merge callbacks
        {:on-call {URI-WAMP-CALL-AUTHREQ (create-call-authreq allow-anon? secret-cb)
                   URI-WAMP-CALL-AUTH    (create-call-auth perm-cb callbacks)}}))
    callbacks))

(defn- auth-timeout
  "Closes the session if the client has not authenticated."
  [sess-id]
  (when-not (client-authenticated? sess-id)
    (core/close-channel sess-id)))

(defn- init-auth-timer
  "Ensure authentication occurs within a certain time period or else
  force close the session. Default timeout is 20000 ms (20 sec).
  Set timeout to 0 to disable."
  [callbacks sess-id]
  (when-let [auth-cbs (callbacks :on-auth)]
    (let [timeout-ms (auth-cbs :timeout 20000)]
      (if (> timeout-ms 0)
        (timer/schedule-task timeout-ms (auth-timeout sess-id))))))

;; WAMP PubSub/RPC callbacks

(defn- on-invocation
  "Handle WAMP call (RPC) messages"
  [callbacks sess-id topic call-id call-opts call-params call-kw-params]
  (if-let [rpc-cb (callbacks topic)]
    (try
      (let [cb-params [sess-id topic call-id call-params]
            cb-params (apply callback-rewrite (callbacks :on-before) cb-params)
            [sess-id topic call-id call-params] cb-params
            rpc-result (binding [*call-sess-id* sess-id]  ; bind optional sess-id
                         (apply rpc-cb call-params))      ; use fn's own arg signature
            error      (:error  rpc-result)
            result     (:result rpc-result)]
        (if (and (nil? error) (nil? result))
          ; No map with result or error? Assume successful rpc-result as-is
          (invocation-success sess-id topic call-id rpc-result (callbacks :on-after-success))
          (if (nil? error)
            (invocation-success sess-id topic call-id result (callbacks :on-after-success))
            (invocation-error   sess-id topic call-id error  (callbacks :on-after-error)))))
      (catch Exception e
        (call-error sess-id topic call-id
          {:uri (wamp-error-uri :internal-error)
           :message "Internal error" 
           :description (.getMessage e)}
          (callbacks :on-after-error))
        (log/error "RPC Exception:" topic call-params e)))
    (call-error sess-id topic call-id
      {:uri (wamp-error-uri :no-such-procedure)
       :message (str "No such procedure: '" topic "'")}
      (callbacks :on-after-error))))

(defn- map-key-or-prefix
  "Finds a map value by key or lookup by string key prefix (ending with *)."
  [m k]
  (if-let [v (m k)] v
    (some #(when (not (nil? %)) %)
      (for [[mk mv] m]
        (when (and (not (keyword? mk)) (not (false? mv))
                (= \* (last mk))
                (= (seq (take (dec (count mk)) k)) (butlast mk)))
          mv)))))

(defn- on-subscribe
  [callbacks sess-id topic]
  (dosync
    (when (nil? (get-in @topic-clients [topic sess-id]))
      (when-let [topic-cb (map-key-or-prefix callbacks topic)]
        (when (or (true? topic-cb) (topic-cb sess-id topic))
          (let [on-after-cb (callbacks :on-after)]
            (topic-subscribe topic sess-id)
            (when (fn? on-after-cb)
              (on-after-cb sess-id topic))))))))

(defn- get-publish-exclude [sess-id exclude]
  (if (= Boolean (type exclude))
    (when (true? exclude) [sess-id])
    exclude))

(defn- on-publish
  "Handles WAMP publish messages, sending event messages back out
  to clients subscribed to the topic.
  [ TYPE_ID_PUBLISH , topicURI , event [, exclude [, eligible ]]"
  ([callbacks sess-id topic event]
    (on-publish callbacks sess-id topic event false nil))
  ([callbacks sess-id topic event exclude]
    (on-publish callbacks sess-id topic event exclude nil))
  ([callbacks sess-id topic event exclude eligible]
    (when-let [pub-cb (map-key-or-prefix callbacks topic)]
      (let [cb-params [sess-id topic event exclude eligible]
            cb-params (apply callback-rewrite pub-cb cb-params)
            on-after-cb (callbacks :on-after)]
        (when (sequential? cb-params)
          (let [[sess-id topic event exclude eligible] cb-params
                exclude (get-publish-exclude sess-id exclude)]
            (if-not (nil? eligible)
              (emit-event! topic event eligible)
              (broadcast-event! topic event exclude))
            (when (fn? on-after-cb)
              (on-after-cb sess-id topic event exclude eligible))))))))

(defn registration-id
  [uri]
  (hash uri))

(defn- call->invocation
  [arguments]
  [(message-id :INVOCATION)
   (nth arguments 1) ; request id
   (registration-id (nth arguments 3)) ; uri
   (nth arguments 2) ; options
   (nth arguments 4) ; list args
   (nth arguments 5)]) ; kw args

(defn- handle-message
  [sess-id callbacks data-seq]
  (let [[msg-type & msg-params] arguments 
        on-call-cbs  (callbacks :on-call)
        on-sub-cbs   (callbacks :on-subscribe)
        on-unsub-cb  (callbacks :on-unsubscribe)
        on-pub-cbs   (callbacks :on-publish)
        perm-cb      (get-in callbacks [:on-auth :permissions])]
    (condp = msg-type

      (message-id :HELLO) (send-welcome! sess-id)

      (message-id :GOODBYE)
      (send-goodbye! sess-id (nth msg-params 1) (nth msg-params 2))

      ; [INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict]
      ; [INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict, CALL.Arguments|list]
      ; [INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict, CALL.Arguments|list, CALL.ArgumentsKw|dict]
      (message-id :INVOCATION)
      (when (map? on-call-cbs)
        (let [[call-id call-opts topic-uri call-params call-kw-params] msg-params
              topic (core/get-topic sess-id topic-uri)]
          (if (or (nil? perm-cb)
                  ;(= URI-WAMP-CALL-AUTHREQ topic)
                  ;(= URI-WAMP-CALL-AUTH topic)
                  (authorized? sess-id :rpc topic perm-cb))
            (on-invocation on-call-cbs sess-id topic call-id call-opts call-params call-kw-params)
            (invocation-error sess-id topic call-id
                              {:uri (wamp-error-uri :not-authorized) :message "Access denied"}
                              (on-call-cbs :on-after-error)))))


      (comment 
        ; v1 messages 

        1 ;TYPE-ID-PREFIX
        (apply add-topic-prefix sess-id msg-params)

        2 ;TYPE-ID-CALL
        (if (map? on-call-cbs)
          (let [[call-id topic-uri & call-params] msg-params
                topic (core/get-topic sess-id topic-uri)]
            (if (or (nil? perm-cb)
                    (= URI-WAMP-CALL-AUTHREQ topic)
                    (= URI-WAMP-CALL-AUTH topic)
                    (authorized? sess-id :rpc topic perm-cb))
              (apply on-invocation on-call-cbs sess-id topic call-id call-params)
              (invocation-error sess-id topic call-id
                          {:uri URI-WAMP-ERROR-NOAUTH :message DESC-WAMP-ERROR-NOAUTH}
                          (on-call-cbs :on-after-error)))))

        5 ;TYPE-ID-SUBSCRIBE
        (let [topic (core/get-topic sess-id (first msg-params))]
          (if (or (nil? perm-cb) (authorized? sess-id :subscribe topic perm-cb))
            (on-subscribe on-sub-cbs sess-id topic)))

        6 ;TYPE-ID-UNSUBSCRIBE
        (let [topic (core/get-topic sess-id (first msg-params))]
          (dosync
            (when (true? (get-in @topic-clients [topic sess-id]))
              (topic-unsubscribe topic sess-id)
              (when (fn? on-unsub-cb) (on-unsub-cb sess-id topic)))))

        7 ;TYPE-ID-PUBLISH
        (let [[topic-uri event & pub-args] msg-params
              topic (core/get-topic sess-id topic-uri)]
          (if (or (nil? perm-cb) (authorized? sess-id :publish topic perm-cb))
            (apply on-publish on-pub-cbs sess-id topic event pub-args))))

      ; default: Unknown message type
      (log/warn "Unknown message type" arguments))))

#_(defn http-kit-handler
  "Sets up the necessary http-kit websocket event handlers
  for use with the WAMP sub-protocol. Returns a WAMP client session id.

  Example usage:

    (http-kit/with-channel req channel
      (if-not (:websocket? req)
        (http-kit/close channel)
        (http-kit-handler channel
          {:on-open        on-open-fn
           :on-close       on-close-fn

           :on-auth        {:allow-anon?     false         ; allow anonymous authentication?
                            :timeout         20000         ; close connection if not authenticated
                                                           ; (default 20 secs)
                            :secret          auth-secret-fn
                            :permissions     auth-permissions-fn}

           :on-call        {(rpc-url \"add\")      +         ; map topics to rpc functions
                            (rpc-url \"echo\")     identity
                            :on-before           on-before-call-fn
                            :on-after-error      on-after-call-error-fn
                            :on-after-success    on-after-call-success-fn}

           :on-subscribe   {(evt-url \"chat\")     on-subscribe-fn? ; allowed to subscribe?
                            (evt-url \"prefix*\")  true             ; match topics by prefix
                            (evt-url \"sub-only\") true             ; implicitly allowed
                            (evt-url \"pub-only\") false            ; subscription is denied
                            :on-after            on-after-subscribe-fn};

           :on-publish     {(evt-url \"chat\")     on-publish-fn   ; custom event broker
                            (evt-url \"prefix*\")  true            ; pass events through as-is
                            (evt-url \"sub-only\") false           ; publishing is denied
                            (evt-url \"pub-only\") true
                            :on-after            on-after-publish-fn}

           :on-unsubscribe on-unsubscribe-fn})))

  Callback signatures:

    (on-open-fn sess-id)
    (on-close-fn sess-id status)

    (auth-secret-fn sess-id auth-key auth-extra)
      Provide the authentication secret for the key (ie. username) and
      (optionally) extra information from the client. Return nil if the key
      does not exist.

    (auth-permissions-fn sess-id auth-key)
      Returns a map of permissions the session is granted when the authentication
      succeeds for the given key.

      The permission map should be comprised of the topics that are allowed
      for each category:

        {:rpc       {\"http://example/rpc#call\"    true}
         :subscribe {\"http://example/event#allow\" true
                     \"http://example/event#deny\"  false}
         :publish   {\"http://example/event#allow\" true}}

      ...or you can allow all category topics:

        {:all true}

      ...or allow/deny all topics within a category:

        {:rpc       true
         :subscribe false
         :publish   true}

    (rpc-call ...)
      Can have any signature. The parameters received from the client will be applied as-is.
      The client session is also available in the bound *call-sess-id* var.
      The function may return a value as is, or in a result map: {:result \"my result\"},
      or as an error map: {:error {:uri \"http://example.com/error#give-error\"
                                   :message \"Test error\"
                                   :description \"Test error description\"
                                   :kill false}} ; true will close the connection after send

    (on-before-call-fn sess-id topic call-id call-params)
      To allow call, return params as vector: [sess-id topic call-id call-params]
      To deny, return nil/false.

    (on-after-call-error-fn sess-id topic call-id error)
      Return params as vector: [sess-id topic call-id error]

    (on-after-call-success-fn sess-id topic call-id result)
      Return params as vector: [sess-id topic call-id result]

    (on-subscribe-fn? sess-id topic)
      Return true to allow client to subscribe, false to deny.

    (on-after-subscribe-fn sess-id topic)
      No return values required.

    (on-publish-fn sess-id topic event exclude eligible)
      To allow publish, return params as vector: [sess-id topic event exclude eligible]
      To deny, return nil/false.

    (on-after-publish-fn sess-id topic event exclude eligible)
      No return values required.

    (on-unsubscribe-fn sess-id topic)
      No return values required."
  [channel callbacks-map]
  (let [callbacks-map (init-cr-auth callbacks-map)
        cb-on-open    (callbacks-map :on-open)
        sess-id       (core/add-client channel)]
    (httpkit/on-close channel   (on-close sess-id
                                  (callbacks-map :on-close)
                                  (callbacks-map :on-unsubscribe)))
    (httpkit/on-receive channel (on-message sess-id callbacks-map))
    (when (fn? cb-on-open) (cb-on-open sess-id))
    (init-auth-timer callbacks-map sess-id)
    sess-id))

