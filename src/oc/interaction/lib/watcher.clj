(ns oc.interaction.lib.watcher
  "Use Redis to track which web socket connections are 'watching' which items."
  (:require [clojure.core.async :as async :refer [<! <!!]]
            [defun.core :refer (defun-)]
            [taoensso.carmine :as car]
            [taoensso.sente :as sente]
            [taoensso.timbre :as timbre]))

(def watcher-chan (async/chan 10000)) ; buffered channel

(def forever true) ; makes Eastwood happy to have a forever while loop be "conditional" on something

;; ----- Storage constants -----

(def week (* 60 60 24 7)) ; 1 week in seconds for Redis key expiration

;; ----- Redis "Schema" -----

(def watch-prefix "watch:")
(defn watch-key [watch-id] (str watch-prefix watch-id))

;; ----- Redis connection -----

(def server-conn {:pool {} :spec {:host "127.0.0.1" :port 6379}}) ; See `wcar` docstring for opts
(defmacro wcar* [& body] `(car/wcar server-conn ~@body))

;; ----- Event Handling -----

; (defn send-event
;   "Send outbound events with Sente"
;   [id event]
;   (timbre/info "[websocket] sending:" event "to:" id)
;     (sente/chsk-send! id event)))

(defun- handle-watch-message
  "Handle 3 types of messages: watch, unwatch, send"
  
  ([message :guard :watch]
  (let [watch-id (:watch-id message)
        client-id (:client-id message)
        redis-key (watch-key watch-id)]
    (timbre/info "Watch request for" watch-id "by" client-id)
    (wcar*
      (car/multi)
       (car/sadd redis-key client-id)
        (car/expire redis-key week) ; renew key expiration
      (car/exec))))

  ([message :guard :unwatch]
  (let [watch-id (:watch-id message)
        client-id (:client-id message)]
    (timbre/info "Stop watch request for" watch-id "by" client-id)
    (wcar*
      (car/srem (watch-key watch-id) client-id))))

  ([message :guard :send]
  (timbre/info "Send request for" (:watch-id message)))

  ([message]
  (timbre/warn "Unknown request in watch channel" message)))

;; ----- Watcher Event Loop -----

(async/go (while forever
  (timbre/debug "Watcher waiting...")
  (let [message (<!! watcher-chan)]
    (timbre/debug "Processing message on watcher channel...")
    (try
      (handle-watch-message message)
      (catch Exception e
        (timbre/error e))))))