(ns oc.interaction.async.slack-mirror
  "
  Mirror comments in Slack.

  Use of this mirror is through core/async. A message is sent to the `echo-chan` or `proxy-chan` to
  mirror a comment in Slack.

  Comments are echoed to a Slack thread (defined by a Slack timestamp), the first comment for an entry
  initiates the Slack thread.
  "
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [taoensso.timbre :as timbre]
            [oc.lib.db.pool :as pool]
            [oc.lib.slack :as slack]
            [oc.interaction.resources.interaction :as interact-res]))

;; ----- core.async -----

(defonce echo-go (atom true))
(defonce proxy-go (atom true))

(defonce echo-chan (async/chan 10000)) ; buffered channel
(defonce proxy-chan (async/chan 10000)) ; buffered channel


;; TODO move this into its own channel
(defn handle-result
  "Store Slack thread (ts) in interaction for future replies."
  [db-pool result slack-channel interaction]
  (when-not (:slack-thread interaction) ; nothing to do if comment already has a Slack thread
    (let [slack-thread (assoc slack-channel :thread (:ts result))]
      (timbre/info "Persisting slack thread:" slack-thread "to interaction:" (:uuid interaction))
      (pool/with-pool [conn db-pool]

      ))))

;; ----- Event loops (outgoing to Slack) -----

(defn echo-loop [db-pool]
  (reset! echo-go true)
  (async/go (while @echo-go
    (timbre/debug "Slack echo waiting...")
    (let [message (<!! echo-chan)]
      (timbre/debug "Processing message on echo channel...")
      (if (:stop message)
        (do (reset! echo-go false) (timbre/info "Slack echo stopped."))
        (async/thread
          (try
            (let [slack-user (:slack-user message)
                  token (:token slack-user)
                  interaction (:comment message)
                  uuid (:uuid interaction)
                  slack-channel (:slack-channel message)
                  channel-id (:channel-id slack-channel)
                  thread (:thread slack-channel)
                  text (:body interaction)]
              (timbre/info "Echoing comment to Slack:" uuid)
              (let [result (if thread
                              (slack/echo-comment token channel-id thread text)
                              (slack/echo-comment token channel-id text))]
                (if (:ok result)
                  (do 
                    (timbre/info "Echoed to Slack:" uuid)
                    (handle-result db-pool result slack-channel interaction))
                  (timbre/error "Unable to echo comment:" uuid "to Slack:" result))))
            (catch Exception e
              (timbre/error e)))))))))

(defn proxy-loop [db-pool]
  (reset! proxy-go true)
  (async/go (while @proxy-go
    (timbre/debug "Slack proxy waiting...")
    (let [message (<!! proxy-chan)]
      (timbre/debug "Processing message on proxy channel...")
      (if (:stop message)
        (do (reset! proxy-go false) (timbre/info "Slack proxy stopped."))
        (async/thread
          (try
            (let [slack-bot (:slack-bot message)
                  token (:token slack-bot)
                  interaction (:comment message)
                  uuid (:uuid interaction)
                  slack-channel (:slack-channel message)
                  channel-id (:channel-id slack-channel)
                  thread (:thread slack-channel)
                  text (:body interaction)
                  author (-> message :author :name)]
              (timbre/info "Proxying comment to Slack:" uuid)
              (let [result (if thread
                              (slack/proxy-comment token channel-id thread text author)
                              (slack/proxy-comment token channel-id text author))]
                (if (:ok result)
                  (do
                    (timbre/info "Proxied to Slack:" uuid)
                    (handle-result db-pool result slack-channel interaction))
                  (timbre/error "Unable to proxy comment:" uuid "to Slack:" result))))
            (catch Exception e
              (timbre/error e)))))))))

;; ----- Component start/stop -----

(defn start
  "Start the core.async channel consumers for mirroring comments to Slack."
  [sys]
  (let [db-pool (:db-pool sys)]
    (echo-loop db-pool)
    (proxy-loop db-pool)))

(defn stop
  "Stop the core.async channel consumers for mirroring comments to Slack."
  []
  (when @echo-go
    (timbre/info "Stopping Slack echo...")
    (>!! echo-chan {:stop true}))
  (when @proxy-go
    (timbre/info "Stopping Slack proxy...")
    (>!! proxy-chan {:stop true})))