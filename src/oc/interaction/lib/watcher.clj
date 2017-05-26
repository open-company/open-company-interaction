(ns oc.interaction.lib.watcher
  "
  Track which web socket connections are 'watching' which items.

  Use of this watcher is through core/async. A message is sent to the `watcher-chan` to
  register interest, unregister interest and send something to all that have registered interest.

  The sending to registered listeners is the resposibility of the user of this watcher and is
  done by consuming the core.async `sender-chan`.
  "
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [defun.core :refer (defun-)]
            [taoensso.timbre :as timbre]))

(def watcher-chan (async/chan 10000)) ; buffered channel
(def sender-chan (async/chan 10000)) ; buffered channel

(def forever true) ; makes Eastwood happy to have a forever while loop be "conditional" on something

;; ----- Storage atom and functions -----

(def watchers (atom {}))

(defn add-watcher [watch-id client-id]
  (let [item-watchers (or (get @watchers watch-id) #{})]
    (swap! watchers assoc watch-id (conj item-watchers client-id))))

(defn remove-watcher [watch-id client-id]
  (let [item-watchers (or (get @watchers watch-id) #{})]
    (swap! watchers assoc watch-id (disj item-watchers client-id))))

(defn watchers-for [watch-id]
  (vec (get @watchers watch-id)))

;; ----- Event handling -----

(defn send-event
  "Send outbound events with Sente"
  [id event payload]
  (timbre/debug "Send request to:" id)
  (>!! sender-chan {:id id :event [event payload]}))

(defun- handle-watch-message
  "Handle 3 types of messages: watch, unwatch, send"
  
  ([message :guard :watch]
  ;; Register interest by the specified client in the specified item by storing the client ID
  (let [watch-id (:watch-id message)
        client-id (:client-id message)]
    (timbre/info "Watch request for:" watch-id "by:" client-id)
    (add-watcher watch-id client-id)))

  ([message :guard :unwatch]
  ;; Unregister interest by the specified client in the specified item by removing the client ID
  (let [watch-id (:watch-id message)
        client-id (:client-id message)]
    (timbre/info "Stop watch request for:" watch-id "by:" client-id)
    (remove-watcher watch-id client-id)))

  ([message :guard :send]
  ;; For every client that's registered interest in the specified item, send them the specified event
  (let [watch-id (:watch-id message)]
    (timbre/info "Send request for:" watch-id)
    (let [client-ids (watchers-for watch-id)]
      (timbre/debug "Send request to:" client-ids)
      (doseq [client-id client-ids]
        (send-event client-id (:event message) (:payload message))))))

  ([message]
  (timbre/warn "Unknown request in watch channel" message)))

;; ----- Watcher event loop -----

(async/go (while forever
  (timbre/debug "Watcher waiting...")
  (let [message (<!! watcher-chan)]
    (timbre/debug "Processing message on watcher channel...")
    (try
      (handle-watch-message message)
      (catch Exception e
        (timbre/error e))))))

;; ----- Utility function -----

(defn clear-watchers []
  )