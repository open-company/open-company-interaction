(ns oc.interaction.api.websockets
  "WebSocket server handler."
  (:require [clojure.core.async :as async :refer (>!! <!)]
            [taoensso.sente :as sente]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET POST)]
            [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
            [oc.lib.async.watcher :as watcher]
            [oc.lib.jwt :as jwt]
            [oc.lib.sentry.core :as sentry]
            [oc.interaction.config :as c]))

;; ----- core.async -----

(defonce sender-go (atom true))

;; ----- Sente server setup -----

;; https://github.com/ptaoussanis/sente#on-the-server-clojure-side

(reset! sente/debug-mode?_ (not c/prod?))

(let [{:keys [ch-recv send-fn connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket-server! (get-sch-adapter) 
        {:packer :edn
         :user-id-fn (fn [ring-req] (:client-id ring-req)) ; use the client id as the user id
         :csrf-token-fn (fn [ring-req] (:client-id ring-req))
         :handshake-data-fn (fn [ring-req] (timbre/debug "handshake-data-fn") {:carrot :party})})]
  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def connected-uids connected-uids)) ; Read-only atom of uids with Sente WebSocket connections

;; Uncomment to watch the connection atom for changes
; (add-watch connected-uids :connected-uids
;   (fn [_ _ old new]
;     (when (not= old new)
;       (timbre/debug "[websocket]: atom update" new))))

;; ----- Sente incoming event handling -----

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id) ; Dispatch on event-id

(defn- event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (timbre/trace "[websocket]" event id ?data)
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  ;; Default/fallback case (no other matching handler)
  :default
  
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (timbre/trace "[websocket] unhandled event" event "for" id)
  (when ?reply-fn
    (?reply-fn {:umatched-event-as-echoed-from-from-server event})))

(defmethod -event-msg-handler
  :chsk/handshake
  
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (timbre/trace "[websocket] chsk/handshake" event id ?data)
  (when ?reply-fn
    (?reply-fn {:umatched-event-as-echoed-from-from-server event})))

(defmethod -event-msg-handler
  :auth/jwt
  
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [client-id (-> ring-req :params :client-id)
        jwt-valid? (jwt/valid? (:jwt ?data) c/passphrase)]
    (timbre/info "[websocket] auth/jwt" (if jwt-valid? "valid" "invalid") "by" client-id)
    ;; Get the jwt and disconnect the client if it's not good!
    (when ?reply-fn
      (?reply-fn {:valid jwt-valid?}))))

(defmethod -event-msg-handler
  :watch/board

  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [client-id (-> ring-req :params :client-id)
        board-uuid (:board-uuid ?data)]
    (timbre/info "[websocket] watch/board" board-uuid "by" client-id)
    (>!! watcher/watcher-chan {:watch true :watch-id board-uuid :client-id client-id})
    (when ?reply-fn
      (?reply-fn {:watching board-uuid}))))

(defmethod -event-msg-handler
  :unwatch/board

  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [client-id (-> ring-req :params :client-id)]
    (timbre/info "[websocket] unwatch/board by" client-id)
    (>!! watcher/watcher-chan {:unwatch true :client-id client-id})
    (when ?reply-fn
      (?reply-fn {:unwatching client-id}))))

(defmethod -event-msg-handler
  ;; Client disconnected
  :chsk/uidport-close
  
  [{:as ev-msg :keys [event id ring-req]}]
  (let [client-id (-> ring-req :params :client-id)]
    (timbre/info "[websocket] chsk/uidport-close by" client-id)
    (>!! watcher/watcher-chan {:unwatch true :client-id client-id})))

;; ----- Sente router event loop (incoming from Sente/WebSocket) -----

(defonce router_ (atom nil))

(defn- stop-router! []
  (when-let [stop-fn @router_]
    (stop-fn)))

(defn- start-router! []
  (stop-router!)
  (reset! router_
    (sente/start-server-chsk-router!
      ch-chsk event-msg-handler)))

;; ----- Sender event loop (outgoing to Sente/WebSocket) -----

(defn sender-loop []
  (reset! sender-go true)  
  (async/go (while @sender-go
    (timbre/debug "Sender waiting...")
    (let [message (<! watcher/sender-chan)]
      (timbre/debug "Processing message on sender channel...")
      (if (:stop message)
        (do (reset! sender-go false) (timbre/info "Sender stopped."))
        (async/thread
          (try
            (let [event (:event message)
                  id (:id message)]
              (timbre/info "[websocket] sending:" (first event) "to:" id)
              (chsk-send! id event))
            (catch Exception e
              (timbre/warn e)
              (sentry/capture {:throwable e :message (str "Error processing message on websocket loop: " e) :extra {:event (:event message)}})))))))))

;; ----- Ring routes -----

(defn routes [sys]
  (compojure/routes
    (GET "/interaction-socket/user/:user-id" req (ring-ajax-get-or-ws-handshake req))
    (POST "/interaction-socket/user/:user-id" req (ring-ajax-post req))))

;; ----- Component start/stop -----

(defn start
  "Start the incoming WebSocket frame router and the core.async loop for sending outgoing WebSocket frames."
  []
  (start-router!)
  (sender-loop))

(defn stop
  "Stop the incoming WebSocket frame router and the core.async loop for sending outgoing WebSocket frames."
  []
  (timbre/info "Stopping incoming websocket router...")
  (stop-router!)
  (timbre/info "Router stopped.")
  (when @sender-go
    (timbre/info "Stopping sender...")
    (>!! watcher/sender-chan {:stop true})))