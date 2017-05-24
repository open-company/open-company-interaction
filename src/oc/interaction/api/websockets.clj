(ns oc.interaction.api.websockets
  "WebSocket server handler."
  (:require [clojure.core.async :refer [<! <!! chan go thread]]
            [clojure.core.cache :as cache]
            [taoensso.sente :as sente]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET POST)]
            [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
            [oc.lib.jwt :as jwt]
            [oc.interaction.config :as c]))


;; ----- Utility functions -----

(defn session-uid
  "Function to extract the UUID that Sente needs from the request."
  [req]
  (-> req :session :uid))

;; ----- Sente server setup -----

;; https://github.com/ptaoussanis/sente#on-the-server-clojure-side

(reset! sente/debug-mode?_ (not c/prod?))

(let [{:keys [ch-recv send-fn connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket-server! (get-sch-adapter) 
        {:packer :edn
         :user-id-fn session-uid
         :csrf-token-fn (fn [ring-req] (session-uid ring-req))
         :handshake-data-fn (fn [ring-req] (timbre/debug "handshake-data-fn") {:test :asd})})]
  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def connected-uids connected-uids)) ; Read-only atom of uids with Sente WebSocket connections

;; We can watch the connection atom for changes
; (add-watch connected-uids :connected-uids
;   (fn [_ _ old new]
;     (when (not= old new)
;       (timbre/debug "[websocket]: atom update" new))))

;; ----- Sente event handling -----

(declare send-event)

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
  (timbre/debug "[websocket] default" event id ?data)
  (let [uid (session-uid ring-req)]
    (timbre/debug "[websocket] unhandled event: " event "for" uid)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (timbre/debug "[websocket] chsk/handshake" event id ?data)
  (let [uid (session-uid ring-req)]
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

(defmethod -event-msg-handler :auth/jwt
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [board-uuid (-> ring-req :params :board-uuid)
        jwt-valid? (jwt/valid? (:jwt ?data) c/passphrase)]
    (timbre/info "[websocket] auth/jwt" jwt-valid? "for board" board-uuid)
    ; Get the jwt and disconnect the client if it's not good!
    (when ?reply-fn
      (?reply-fn {:valid jwt-valid?}))))

(defmethod -event-msg-handler
  ; Client disconnected
  :chsk/uidport-close
  [{:as ev-msg :keys [event id]}]
  (timbre/debug "[websocket] :chsk/uidport-close" event id))

;; ----- Actions -----

(defn send-event
  "Send outbound events"
  [req event]
  (let [user-id (session-uid req)]
    (timbre/info "[websocket] sending:" event "to:" user-id)
    (chsk-send! user-id event)))

;; ----- Sente router (event loop) -----

(defonce router_ (atom nil))

(defn  stop-router! [] (when-let [stop-fn @router_] (stop-fn)))

(defn start-router! []
  (stop-router!)
  (reset! router_
    (sente/start-server-chsk-router!
      ch-chsk event-msg-handler)))

;; ----- Ring routes -----

(defn routes [sys]
  (compojure/routes
    (GET "/interaction-socket/boards/:board-uuid" req (ring-ajax-get-or-ws-handshake req))
    (POST "/interaction-socket/boards/:board-uuid" req (ring-ajax-post req))))