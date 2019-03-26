(ns oc.interaction.async.slack-router
  "Consume slack messages from SQS. This SQS queue is subscribed to the Slack
   message SNS topic.
  "
  (:require
   [clojure.core.async :as async :refer (<!! >!!)]
   [cheshire.core :as json]
   [oc.lib.sqs :as sqs]
   [oc.lib.slack :as lib-slack]
   [oc.interaction.async.slack-mirror :as mirror]
   [oc.interaction.async.usage :as usage]
   [oc.interaction.config :as config]
   [taoensso.timbre :as timbre]))

;; ----- core.async -----

(defonce slack-router-chan (async/chan 10000)) ; buffered channel

(defonce slack-router-go (atom nil))

;; ----- Slack handling -----
(defn- has-marker-char? [text]
  (and text (re-find (re-pattern (str "^" lib-slack/marker-char)) text)))

(defn- from-us? [event]
  (or (has-marker-char? (:text event))
      (and (:blocks event)
           (= (:subtype event) "bot_message"))))

(defn slack-event
  "
  Handle a message event from Slack. Ignore events that aren't threaded, or that are from us.

  Idea here is to do very minimal processing and get a 200 back to Slack as fast as possible as this is a 'fire hose'
  of requests. So minimal logging and minimal handling of the request.

  Message events look like:

  {'token' 'IxT9ZaxvjqRdKxYtWdTw21Xv',
   'team_id' 'T06SBMH60',
   'api_app_id' 'A0CHN2UDB',
   'event' {'type' 'message',
            'user' 'U06SBTXJR',
            'text' 'Call me back here',
            'thread_ts' '1494262410.072574',
            'parent_user_id' 'U06SBTXJR',
            'ts' '1494281750.011785',
            'channel' 'C10A1P4H2',
            'event_ts' '1494281750.011785'},
    'type' 'event_callback',
    'authed_users' ['U06SBTXJR'],
    'event_id' 'Ev5B8YSYQ6',
    'event_time' 1494281750}
  "
  [body]
  (let [type (:type body)
        token (:token body)
        event (:event body)
        channel (:channel event)
        thread (:thread_ts event)]

    ;; Token check
    (if-not (= token config/slack-verification-token)

      ;; Eghads! It might be a Slack impersonator!
      (do
        (timbre/warn "Slack verification token mismatch, request provided:" token)
        {:status 403})

      ;; Token check is A-OK
      (cond
        ;; A message to the bot is to a DM channel that starts with D, e.g. "D6DV24ZHP"
        (= \D (first channel))
        (let [text (:text event)]
          (when-not (from-us? event)
            ;; Message from Slack, w/o our marker, needs a bot usage request
            (>!! usage/usage-chan {:body body}))
          {:status 200})

        ;; If there's no thread, we aren't interested in the message
        (nil? thread) {:status 200}

        :else
        ;; if there's a marker starting the message than this message came form us so can be ignored 
        (let [text (:text event)]
          (when-not (from-us? event)
            ;; Message from Slack, not us, with a thread and w/o our marker, might need mirrored as a comment
            (>!! mirror/incoming-chan {:body body}))
          {:status 200})))))

;; ----- SQS handling -----

(defn- read-message-body
  "
  Try to parse as json, otherwise use read-string.
  "
  [msg]
  (try
    (json/parse-string msg true)
    (catch Exception e
      (read-string msg))))

(defn sqs-handler
  "Handle an incoming SQS message to the interaction service."
  [msg done-channel]
  (let [msg-body (read-message-body (:body msg))
        error (if (:test-error msg-body) (/ 1 0) false)] ; a message testing Sentry error reporting
    (timbre/infof "Received message from SQS: %s\n" msg-body)
    (>!! slack-router-chan msg-body))
  (sqs/ack done-channel msg))

;; ----- Event loop -----

(defn- slack-router-loop
  "Start a core.async consumer of the slack router channel."
  []
  (reset! slack-router-go true)
  (async/go (while @slack-router-go
      (timbre/info "Waiting for message on slack router channel...")
      (let [msg (<!! slack-router-chan)]
        (timbre/trace "Processing message on slack router channel...")
        (if (:stop msg)
          (do (reset! slack-router-go false) (timbre/info "Slack router stopped."))
          (try
            (when (:Message msg) ;; data change SNS message
              (let [msg-parsed (json/parse-string (:Message msg) true)]
                (slack-event msg-parsed)))
            (timbre/trace "Processing complete.")
            (catch Exception e
              (timbre/error e))))))))

;; ----- Component start/stop -----

(defn start
 "Stop the core.async slack router channel consumer."
  []
  (timbre/info "Starting slack router...")
  (slack-router-loop))

(defn stop
 "Stop the core.async slack router channel consumer."
  []
  (when @slack-router-go
    (timbre/info "Stopping slack router...")
    (>!! slack-router-chan {:stop true})))
