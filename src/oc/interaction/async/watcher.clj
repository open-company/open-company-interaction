(ns oc.interaction.async.watcher
  "
  Small wrapper around oc.lib.watcher for building notification events.
  "
  (:require [clojure.core.async :as async :refer [>!!]]
            [taoensso.timbre :as timbre]
            [oc.lib.async.watcher :as watcher]
            [oc.interaction.representations.interaction :as interact-rep]))

;; ----- Actions -----

(defn notify-watcher
  "Given an event, an interaction and an optional reaction count, notify the watcher with core.async."
  
  ([event interaction] (notify-watcher event interaction false nil))

  ([event interaction reaction-count]
    (notify-watcher event interaction reaction-count nil))

  ([event interaction reaction-count sender-ws-client-id]
  (timbre/info "Sending:" event "to the watcher for:" (:uuid interaction) "skipping client:" sender-ws-client-id)
  (let [initial-payload {:resource-uuid (:resource-uuid interaction)
                         :interaction (interact-rep/interaction-representation interaction :none)}
        payload (if reaction-count (assoc initial-payload :count reaction-count) initial-payload)]
    (>!! watcher/watcher-chan {:send true
                               :watch-id (:board-uuid interaction)
                               :event event
                               :sender-ws-client-id sender-ws-client-id
                               :payload payload}))))