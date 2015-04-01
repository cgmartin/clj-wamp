(ns clj-wamp.v2
  (:require
    [clojure.tools.logging :as log]
    [cheshire.core :as json]
    [gniazdo.core :as ws]
    [clj-wamp.core :as core]))

(def subprotocol-id "wamp.2.json")

(def ^:const message-id-table
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

(defn invert-map 
  [dict]
  (into {} (map (fn [[k v]] [v k]) dict)))

(def reverse-message-id-table (invert-map message-id-table))

(defn reverse-message-id [msg-num]
  (get reverse-message-id-table msg-num))

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
   :bad-request "wamp.error.bad-request"})

(defmacro error-uri
  [error-keyword]
  (get wamp-error-uri-table error-keyword))

(defn new-request
  []
  (core/new-rand-id))

(defn send!
  [{:keys [debug?] :as instance} msg-data]
  (let [json-str (json/encode msg-data)]
    (when debug?
      (log/debug "Sending WAMP message" json-str))
    (when-let [socket @(:socket instance)]
      (ws/send-msg socket json-str))))

(defn hello
  "[HELLO, Realm|uri, Details|dict]"
  [instance]
  (send! instance
         [(message-id :HELLO)
          (:realm instance)
          {:roles
           {:callee {}
            :publisher {}}}]))

(defn abort
  "[ABORT, Details|dict, Reason|uri]"
  [instance details uri]
  (send! instance [(message-id :ABORT) details uri]))

(defn goodbye
  "[GOODBYE, Details|dict, Reason|uri]"
  [instance details uri]
  (send! instance [(message-id :GOODBYE) details uri]))

(defn error
 "[ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri]
  [ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri, Arguments|list]
  [ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri, Arguments|list, ArgumentsKw|dict]" 
  [instance request-type request-id details uri]
  (send! instance
         [(message-id :ERROR) request-type request-id details uri]))

(defn publish
  "[PUBLISH, Request|id, Options|dict, Topic|uri]
   [PUBLISH, Request|id, Options|dict, Topic|uri, Arguments|list]
   [PUBLISH, Request|id, Options|dict, Topic|uri, Arguments|list, ArgumentsKw|dict]"
  [instance request-id options uri seq-args kw-args]
  (let [args [(message-id :PUBLISH) request-id options uri]
        args (if (some? seq-args) (conj args seq-args) args)
        args (if (some? kw-args) (conj args kw-args) args)]
  (send! instance args)))

(defn register
  "[REGISTER, Request|id, Options|dict, Procedure|uri]"
  [instance request-id options uri]
  (send! instance [(message-id :REGISTER) request-id options uri]))

(defn yield
  "[YIELD, INVOCATION.Request|id, Options|dict]
   [YIELD, INVOCATION.Request|id, Options|dict, Arguments|list]
   [YIELD, INVOCATION.Request|id, Options|dict, Arguments|list, ArgumentsKw|dict]"
  [instance request-id options seq-args kw-args]
  (let [args [(message-id :YIELD) request-id options]
        args (if (some? seq-args) (conj args seq-args) args)
        args (if (some? kw-args) (conj args kw-args) args)]
  (send! instance args)))

(defn- register-next!
  [instance]
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (if-let [[reg-uri reg-fn] (first unregistered)]
             (let [req-id (core/new-rand-id)]
               (register instance req-id {} reg-uri)
               [(dissoc unregistered reg-uri) registered (assoc pending req-id [reg-uri reg-fn])])
             [unregistered registered pending]))))

(defn- exception-message
  [{:keys [debug?] :as instance} ex]
  (if debug?
    {:message (.getMessage ex)
     :stacktrace (map str (.getStackTrace ex))}
    {:message "Application error"}))

(defn perform-invocation
  [instance req-id rpc-fn options seq-args kw-args]
  (try 
    (let [return (if (some? kw-args)
                   (rpc-fn kw-args)
                   (if (some? seq-args)
                     (apply rpc-fn seq-args)
                     (rpc-fn)))]
      (if-let [return-error (:error return)]
        (let [return-error-uri (if (:uri return-error) (:uri return-error) (error-uri :application-error))]
          (error instance (message-id :INVOCATION) req-id (dissoc return-error :uri) return-error-uri))
        (cond
          (:result return) (yield instance req-id {} [(:result return)] nil)
          (:list-result return) (yield instance req-id {} (vec (:list-result return)) nil)
          (:map-result return) (yield instance req-id {} [] (:map-result return))
          :else (yield instance req-id {} [return] nil))))
    (catch Throwable e
      (error instance (message-id :INVOCATION) req-id (exception-message instance e)))))

(defmulti handle-error (fn [instance data] (reverse-message-id (second data))))

(defmethod handle-error nil
  [instance data]
  (log/error "Error received from router" data)
  nil)

(defmethod handle-error :PUBLISH
  [instance data]
  "[ERROR, PUBLISH, PUBLISH.Request|id, Details|dict, Error|uri]"
  (log/error "Router failed to publish event" data)
  nil)

(defmethod handle-error :REGISTER
  [instance data]
  "[ERROR, REGISTER, REGISTER.Request|id, Details|dict, Error|uri]"
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (let [req-id (nth data 2)
                 [reg-uri reg-fn] (get pending req-id)]
             (log/error "Failed to register RPC method:" reg-uri)
             [(assoc unregistered reg-uri reg-fn) registered (dissoc pending req-id)])))
  nil)

(defmulti handle-message (fn [instance data] (reverse-message-id (first data))))

(defmethod handle-message nil
  [instance data]
  (error instance 0 0 {:message "Invalid message type"} (error-uri :bad-request)))

(defmethod handle-message :WELCOME
  [instance data]
  (register-next! instance))

(defmethod handle-message :ABORT 
  [instance data]
  (log/warn "Received ABORT message from router")
  (when-let [socket @(:socket instance)]
    (ws/close socket)))

(defmethod handle-message :GOODBYE
  [instance data]
  (goodbye instance {} (error-uri :goodbye-and-out))
  (when-let [socket @(:socket instance)]
    (ws/close socket)))

(defmethod handle-message :ERROR
  [instance data]
  "[ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri]
   [ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri, Arguments|list]
   [ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri, Arguments|list, ArgumentsKw|dict]" 
  (handle-error instance data))

(defmethod handle-message :PUBLISHED
  [instance data]
  nil)

(defmethod handle-message :REGISTERED
  [instance data]
  "[REGISTERED, REGISTER.Request|id, Registration|id]"
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (let [req-id (nth data 1)
                 reg-id (nth data 2)
                 [reg-uri reg-fn] (get pending req-id)]
             [unregistered (assoc registered reg-id reg-fn) (dissoc pending req-id)])))
  (register-next! instance))

(defmethod handle-message :INVOCATION
  [instance data]
  "[INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict]
   [INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict, CALL.Arguments|list]
   [INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict, CALL.Arguments|list, CALL.ArgumentsKw|dict]"
  (let [[_ registered _] @(:registrations instance)
        req-id (nth data 1)
        reg-id (nth data 2)
        reg-fn (get registered reg-id)]
    (if (some? reg-fn)
      (perform-invocation instance req-id reg-fn (nth data 3) (nth data 4 []) (nth data 5 nil))
      (error instance (message-id :INVOCATION) req-id 
             {:message "Unregistered RPC"
              :reg-id reg-id}
             (error-uri :no-such-registration)))))
