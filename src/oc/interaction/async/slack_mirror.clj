(ns oc.interaction.async.slack-mirror
  "
  Mirror comments in Slack.

  Use of this mirror is through core/async. A message is sent to the `echo-chan` or `proxy-chan` to
  mirror a comment to Slack. A message is sent to `incoming-chan` to persist a message from Slack as
  a comment.

  Comments are echoed to a Slack thread (defined by a Slack timestamp), the first comment for an entry
  initiates the Slack thread.
  "
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [clojure.walk :refer (keywordize-keys)]
            [taoensso.timbre :as timbre]
            [oc.lib.db.pool :as pool]
            [oc.lib.slack :as slack]
            [oc.lib.db.common :as db-common]
            [oc.interaction.async.watcher :as watcher]
            [oc.interaction.resources.interaction :as interact-res]))

;; ----- core.async -----

(defonce echo-chan (async/chan 10000)) ; buffered channel
(defonce proxy-chan (async/chan 10000)) ; buffered channel
(defonce incoming-chan (async/chan 10000)) ; buffered channel
(defonce persist-chan (async/chan 10000)) ; buffered channel

(defonce echo-go (atom nil))
(defonce proxy-go (atom nil))
(defonce incoming-go (atom nil))
(defonce persist-go (atom nil))

;; ----- DB Persistence -----

(def entry-table-name "entries")

(defn- handle-mirror-result
  "Store Slack thread (ts) in interaction for future replies."
  [db-pool result slack-channel interaction]
  (when-not (:thread slack-channel) ; nothing to do if comment already has a Slack thread
    (let [slack-thread (assoc slack-channel :thread (:ts result))]
      (timbre/info "Persisting slack thread:" slack-thread "to entry:" (:entry-uuid interaction))
      (pool/with-pool [conn db-pool]
        (db-common/update-resource conn entry-table-name :uuid (:entry-uuid interaction) {:slack-thread slack-thread})))))

(defn- handle-message
  "
  Look for an entry for the specified channel-id and thread. If one exists, create a new comment for
  the Slack message.
  "
  [db-pool channel-id thread body]
  (timbre/debug "Checking for slack thread:" thread "on channel:" channel-id)
  (pool/with-pool [conn db-pool]
    (when-let [entry (first (db-common/read-resources conn entry-table-name
                            "slack-thread-channel-id-thread" [[channel-id thread]]))]
      (timbre/debug "Found entry:" (:uuid entry) "for slack thread:" thread "on channel:" channel-id)
      (>!! persist-chan {:entry entry :message body})))) ;; request to create the comment

(defn- persist-message-as-comment
  "Persist a new comment as a mirror of the incoming Slack message."
  [db-pool entry message]
  ;; Get the user
  (timbre/info "Looking up Slack user:" (-> message :event :user))
  ;; TODO look up the Slack user

  ;; Create the comment
  (timbre/info "Creating a new comment for entry:" (:uuid entry))
  (pool/with-pool [conn db-pool]
    (if-let [result (interact-res/create-comment! conn (interact-res/->comment entry {:body (-> message :event :text)}
                      {:user-id "0000-0000-0000" :name "Slack user" :avatar-url nil}))]
      (watcher/notify-watcher :interaction-comment/add result))))
  
;; ----- Event loops (outgoing to Slack) -----

(defn- echo-loop 
  "Start a core.async consumer to echo messages to Slack as the user."
  [db-pool]
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
              (if thread
                (timbre/info "Echoing comment to Slack:" uuid "on thread" thread)
                (timbre/info "Echoing comment to Slack:" uuid "as a new thread"))
              (let [result (if thread
                              (slack/echo-comment token channel-id thread text)
                              (slack/echo-comment token channel-id text))]
                (if (:ok result)
                  (do 
                    (timbre/info "Echoed to Slack:" uuid)
                    (handle-mirror-result db-pool result slack-channel interaction))
                  (timbre/error "Unable to echo comment:" uuid "to Slack:" result))))
            (catch Exception e
              (timbre/error e)))))))))

(defn- proxy-loop
  "core.async consumer to proxy messages to Slack as the bot on behalf of the user."
  [db-pool]
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
              (if thread
                (timbre/info "Proxying comment to Slack:" uuid "on thread:" thread)
                (timbre/info "Proxying comment to Slack:" uuid "as a new thread"))
              (let [result (if thread
                              (slack/proxy-comment token channel-id thread text author)
                              (slack/proxy-comment token channel-id text author))]
                (if (:ok result)
                  (do
                    (timbre/info "Proxied to Slack:" uuid)
                    (handle-mirror-result db-pool result slack-channel interaction))
                  (timbre/error "Unable to proxy comment:" uuid "to Slack:" result))))
            (catch Exception e
              (timbre/error e)))))))))

;; ----- Event loops (incoming from Slack) -----

(defn- incoming-loop 
  "core.async consumer to echo messages to Slack as the user."
  [db-pool]
  (reset! incoming-go true)
  (async/go (while @incoming-go
    (timbre/debug "Slack incoming waiting...")
    (let [message (<!! incoming-chan)]
      (timbre/debug "Processing message on incoming channel...")
      (if (:stop message)
        (do (reset! incoming-go false) (timbre/info "Slack incoming stopped."))
        (async/thread
          (try
            (let [body (keywordize-keys (:body message))
                  event (:event body)
                  channel-id (:channel event)
                  thread (:thread_ts event)]
              (handle-message db-pool channel-id thread body))
            (catch Exception e
              (timbre/error e)))))))))

(defn- persist-loop 
  "core.async consumer to persist a Slack message as a comment."
  [db-pool]
  (reset! persist-go true)
  (async/go (while @persist-go
    (timbre/debug "Slack persist waiting...")
    (let [message (<!! persist-chan)]
      (timbre/debug "Processing message on persist channel...")
      (if (:stop message)
        (do (reset! persist-go false) (timbre/info "Slack persist stopped."))
        (async/thread
          (try
            (persist-message-as-comment db-pool (:entry message) (:message message))
            (catch Exception e
              (timbre/error e)))))))))

;; ----- Component start/stop -----

(defn start
  "Start the core.async channel consumers for mirroring comments with Slack."
  [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (echo-loop db-pool)
    (proxy-loop db-pool)
    (incoming-loop db-pool)
    (persist-loop db-pool)))

(defn stop
  "Stop the core.async channel consumers for mirroring comments with Slack."
  []
  (when @echo-go
    (timbre/info "Stopping Slack echo...")
    (>!! echo-chan {:stop true}))
  (when @proxy-go
    (timbre/info "Stopping Slack proxy...")
    (>!! proxy-chan {:stop true}))
  (when @incoming-go
    (timbre/info "Stopping Slack incoming...")
    (>!! incoming-chan {:stop true}))
  (when @persist-go
    (timbre/info "Stopping Slack persist...")
    (>!! persist-chan {:stop true})))