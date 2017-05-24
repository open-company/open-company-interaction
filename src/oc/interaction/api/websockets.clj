(ns oc.interaction.api.websockets
  "WebSocket server handler."
  (:require [clojure.core.async :refer [<! <!! chan go thread]]
            [clojure.core.cache :as cache]
            [taoensso.sente :as sente]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET POST)]
            [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]))


;; Sente server setup https://github.com/ptaoussanis/sente#on-the-server-clojure-side

(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket! (get-sch-adapter) {})]
  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def connected-uids connected-uids)) ; Read-only atom of uids with Sente WebSocket connections

;; ----- Utility functions -----

(def session-map (atom (cache/ttl-cache-factory {} :ttl (* 5 60 1000))))

(defn keep-alive
  "Given a UID, keep it alive."
  [uid]
  (timbre/info "[websocket] keep-alive" uid (java.util.Date.))
  (when-let [token (get @session-map uid)]
    (swap! session-map assoc uid token)))

(defn add-token
  "Given a UID and a token, remember it."
  [uid token]
  (timbre/info "[websocket] add-token" uid token (java.util.Date.))
  (swap! session-map assoc uid token))

(defn get-token
  "Given a UID, retrieve the associated token, if any."
  [uid]
  (let [token (get @session-map uid)]
    (timbre/info "[websocket] get-token" uid token (java.util.Date.))
    token))

(defn unique-id
  "Return a really unique ID (for an unsecured session ID).
  No, a random number is not unique enough. Use a UUID for real!"
  []
  (rand-int 10000))

(defn session-uid
  "Function to extract the UID that Sente needs from the request."
  [req]
  (-> req :session :uid))

(defn session-status
  "Tell the client what state this user's session is in."
  [req]
  (when-let [uid (session-uid req)]
    (chsk-send! uid [:session/state (if (get-token uid) :secure :open)])))

;; ----- Event Handling -----

(declare send-event)

(defmulti handle-event
  "Handle events based on the event ID."
  (fn [[ev-id ev-arg] req]
    (let [params (:params req)]
      (timbre/info "[websocket] handle-event/" ev-id " - " ev-arg
        "from user-id:" (:user-id params) "client-id:" (:client-id params)))
    ev-id))

(defmethod handle-event :chsk/ws-ping
  [_ req]
  (timbre/trace "[websocket] ping")
  (session-status req))

(defmethod handle-event :auth/jwt
  [event req]
  (timbre/info "[websocket] Received auth/jwt")
  (timbre/info "[websocket] event:" event)
  (timbre/info "[websocket] request:" req))

;; Handle unknown events.
;; Note: this includes the Sente implementation events like:
;; - :chsk/uidport-open
;; - :chsk/uidport-close
(defmethod handle-event :default
  [event req]
  (timbre/trace "[websocket] unknown event:" event)
  nil)

;; ----- Actions -----

(defn send-event
  "Send outbound events"
  [req event]
  (let [user-id (session-uid req)]
    (timbre/info "[websocket] sending:" event "to:" user-id)
    (chsk-send! user-id event)))

(defn event-loop
  "Loop to handle inbound events."
  []
  (go (loop [{:keys [client-uuid ring-req event] :as data} (<! ch-chsk)]
        (timbre/info "[websocket] received:" event)
        (thread (handle-event event ring-req))
        (recur (<! ch-chsk)))))

;; ----- Routes -----

(defn routes [sys]
  (compojure/routes
    (GET  "/interaction-socket" req (ring-ajax-get-or-ws-handshake req))
    (POST "/interaction-socket" req (ring-ajax-post req))))

(comment

  ;; Test from Clojure (JVM) REPL
  (require '[gniazdo.core :as ws])

  (def socket
    (ws/connect
      (str "ws://localhost:3002/interaction-socket?client-id=" (java.util.UUID/randomUUID))
      :on-receive #(println "Received:" %)))

  (ws/close socket)

)