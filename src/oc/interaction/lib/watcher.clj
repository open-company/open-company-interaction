(ns oc.interaction.lib.watcher
  "
  Use Redis to track which web socket connections are 'watching' which items.

  Use of this watcher is through core/async. A message is sent to the `watcher-chan` to
  register interest, unregister interest and send something to all that have registered interest.

  The sending to registered listeners is the resposibility of the user of this watcher and is
  done by consuming the core.async `sender-chan`.
  "
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [defun.core :refer (defun-)]
            [taoensso.carmine :as car]
            [taoensso.timbre :as timbre]))

(def watcher-chan (async/chan 10000)) ; buffered channel
(def sender-chan (async/chan 10000)) ; buffered channel

(def forever true) ; makes Eastwood happy to have a forever while loop be "conditional" on something

;; ----- Storage constants -----

(def week (* 60 60 24 7)) ; 1 week in seconds for Redis key expiration

;; ----- Redis "schema" -----

(def watch-prefix "watch:")
(defn watch-key [watch-id] (str watch-prefix watch-id))

;; ----- Redis connection -----

(def server-conn {:pool {} :spec {:host "127.0.0.1" :port 6379}}) ; See `wcar` docstring for opts
(defmacro wcar* [& body] `(car/wcar server-conn ~@body))

;; ----- Event handling -----

(defn send-event
  "Send outbound events with Sente"
  [id event payload]
  (timbre/debug "Send request to:" id)
  (>!! sender-chan {:id id :event [event payload]}))

(defun- handle-watch-message
  "Handle 3 types of messages: watch, unwatch, send"
  
  ([message :guard :watch]
  ;; Register interest by the specified client in the specified item by storing the client ID in a Redis set
  (let [watch-id (:watch-id message)
        client-id (:client-id message)
        redis-key (watch-key watch-id)]
    (timbre/info "Watch request for:" watch-id "by:" client-id)
    (wcar*
      (car/multi)
        (car/sadd redis-key client-id)
        (car/expire redis-key week) ; renew set's expiration
      (car/exec))))

  ([message :guard :unwatch]
  ;; Unregister interest by the specified client in the specified item by removing the client ID from the Redis set
  (let [watch-id (:watch-id message)
        client-id (:client-id message)]
    (timbre/info "Stop watch request for:" watch-id "by:" client-id)
    (wcar*
      (car/srem (watch-key watch-id) client-id))))

  ([message :guard :send]
  ;; For every client that's registered interest in the specified item, send them the specified event
  (let [watch-id (:watch-id message)]
    (timbre/info "Send request for:" watch-id)
    (let [client-ids (wcar* (car/smembers (watch-key watch-id)))]
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