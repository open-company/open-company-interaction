(ns oc.interaction.lib.watcher
  "Use Redis to track which web socket connections are 'watching' which items."
  (:require [clojure.core.async :as async :refer [<! <!!]]
            [defun.core :refer (defun-)]
            [taoensso.sente :as sente]
            [taoensso.timbre :as timbre]))

(def watcher-chan (async/chan 10000)) ; buffered channel

(def forever true) ; makes Eastwood happy to have a forever while loop be "conditional" on something

;; ----- Event Handling -----

; (defn send-event
;   "Send outbound events with Sente"
;   [id event]
;   (timbre/info "[websocket] sending:" event "to:" id)
;     (sente/chsk-send! id event)))

(defun- handle-watch-message
  "Handle 3 types of messages: watch, unwatch, send"
  
  ([message :guard :watch]
  (timbre/info "Watch request for" (:watch-id message) "by" (:client-id message)))

  ([message :guard :unwatch]
  (timbre/info "Stop watch request for" (:watch-id message) "by" (:client-id message)))

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