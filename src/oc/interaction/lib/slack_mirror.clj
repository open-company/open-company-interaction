(ns oc.interaction.lib.slack-mirror
  "
  Mirror comments in Slack.

  Use of this mirror is through core/async. A message is sent to the `echo-chan` or `proxy-chan` to
  mirror a comment in Slack.
  "
  (:require [clojure.core.async :as async :refer [<!! >!! thread]]
            [defun.core :refer (defun-)]
            [taoensso.timbre :as timbre]
            [oc.lib.slack :as slack]))

(def forever true) ; makes Eastwood happy to have a forever while loop be "conditional" on something

(def echo-chan (async/chan 10000)) ; buffered channel
(def proxy-chan (async/chan 10000)) ; buffered channel

;; ----- Slack echo event loop (outgoing to Slack) -----

(async/go (while forever
  (timbre/debug "Slack echo waiting...")
  (let [message (<!! echo-chan)]
    (timbre/debug "Processing message on echo channel...")
    (try
      (let [slack-user (:slack-user message)
            interaction (:comment message)
            uuid (:uuid interaction)
            slack-channel (:slack-channel message)]
        (thread
          (timbre/debug "Echoing comment to Slack:" uuid)
          (let [result (slack/echo-comment (:token slack-user) (:channel-id slack-channel) (:body interaction))]
            (if (:ok result)
              (timbre/info "Echoed to Slack:" uuid)
              (timbre/error "Unable to echo comment:" uuid "to Slack:" result)))))
      (catch Exception e
        (timbre/error e))))))

;; ----- Slack proxy event loop (outgoing to Slack) -----

(async/go (while forever
  (timbre/debug "Slack proxy waiting...")
  (let [message (<!! proxy-chan)]
    (timbre/debug "Processing message on proxy channel...")
    (try
      (let [slack-bot (:slack-bot message)
            interaction (:comment message)
            uuid (:uuid interaction)
            slack-channel (:slack-channel message)
            author (:author message)]
        (thread
          (timbre/debug "Proxying comment to Slack:" uuid)
          (let [result (slack/proxy-comment (:token slack-bot) (:channel-id slack-channel) (:body interaction) (:name author))]
            (if (:ok result)
              (timbre/info "Proxied to Slack:" uuid)
              (timbre/error "Unable to proxy comment:" uuid "to Slack:" result)))))
      (catch Exception e
        (timbre/error e))))))