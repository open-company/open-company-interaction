(ns oc.interaction.api.websockets
  "WebSocket server handler."
  (:require [clojure.core.async :as async :refer (>!! <!!)]
            [taoensso.sente :as sente]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET POST)]
            [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
            [oc.lib.jwt :as jwt]
            [oc.interaction.config :as c]
            [oc.interaction.async.watcher :as watcher]))

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

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (timbre/debug "[websocket]" event id ?data)
  (-event-msg-handler ev-msg) ; Handle event-msgs on a single thread
  ;; (future (-event-msg-handler ev-msg)) ; Handle event-msgs on a thread pool
  )

(defmethod -event-msg-handler
  ;; Default/fallback case (no other matching handler)
  :default
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (timbre/debug "[websocket] unhandled event " event "for" id)
  (when ?reply-fn
    (?reply-fn {:umatched-event-as-echoed-from-from-server event})))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (timbre/debug "[websocket] chsk/handshake" event id ?data)
  (when ?reply-fn
    (?reply-fn {:umatched-event-as-echoed-from-from-server event})))

(defmethod -event-msg-handler :auth/jwt
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [board-uuid (-> ring-req :params :board-uuid)
        client-id (-> ring-req :params :client-id)
        jwt-valid? (jwt/valid? (:jwt ?data) c/passphrase)]
    (timbre/info "[websocket] auth/jwt" (if jwt-valid? "valid" "invalid") "for board:" board-uuid "by" client-id)
    (when jwt-valid?
      (>!! watcher/watcher-chan {:watch true :watch-id board-uuid :client-id client-id}))
    ;; Get the jwt and disconnect the client if it's not good!
    (when ?reply-fn
      (?reply-fn {:valid jwt-valid?}))))

(defmethod -event-msg-handler
  ;; Client disconnected
  :chsk/uidport-close
  [{:as ev-msg :keys [event id ring-req]}]
  (let [board-uuid (-> ring-req :params :board-uuid)
        client-id (-> ring-req :params :client-id)]
    (timbre/info "[websocket] chsk/uidport-close for board" board-uuid "by" client-id)
    (>!! watcher/watcher-chan {:unwatch true :watch-id board-uuid :client-id client-id})))

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
  (async/go (while @sender-go
    (timbre/debug "Sender waiting...")
    (let [message (<!! watcher/sender-chan)]
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
              (timbre/error e)))))))))

;; ----- Ring routes -----

(defn routes [sys]
  (compojure/routes
    (GET "/interaction-socket/boards/:board-uuid" req (ring-ajax-get-or-ws-handshake req))
    (POST "/interaction-socket/boards/:board-uuid" req (ring-ajax-post req))))

;; ----- Component start/stop -----

(defn start
  "Start the incoming WebSocket frame router and the cor.async loop for sending outgoing WebSocket frames."
  []
  (start-router!)
  (sender-loop))

(defn stop
  "Stop the incoming WebSocket frame router and the cor.async loop for sending outgoing WebSocket frames."
  []
  (timbre/info "Stopping incoming websocket router...")
  (stop-router!)
  (timbre/info "Router stopped.")
  (when @sender-go
    (timbre/info "Stopping sender...")
    (>!! watcher/sender-chan {:stop true})))