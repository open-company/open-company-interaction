(ns oc.interaction.async.slack-mirror
  "
  Mirror comments in Slack.

  Use of this mirror is through core/async. A message is sent to the `echo-chan` or `proxy-chan` to
  mirror a comment to Slack. A message is sent to `incoming-chan` to persist a message from Slack as
  a comment.

  Comments are echoed to a Slack thread (defined by a Slack timestamp), the first comment for an resource
  initiates the Slack thread.
  "
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [clojure.walk :refer (keywordize-keys)]
            [clojure.string :as s]
            [clojure.core.cache :as cache]
            [if-let.core :refer (if-let*)]
            [taoensso.timbre :as timbre]
            [jsoup.soup :as soup]
            [oc.lib.db.pool :as pool]
            [oc.lib.slack :as slack]
            [oc.lib.db.common :as db-common]
            [oc.interaction.config :as c]
            [oc.interaction.async.watcher :as watcher]
            [oc.interaction.resources.interaction :as interact-res]))

;; ----- Slack message copy -----

(def echo-intro "commented on:")

;; ----- Slack defaults -----

(def default-slack-author {
  :user-id "0000-0000-0000"
  :name "Slack User"
  :avatar-url (str c/web-cdn-url "/img/ML/happy_face_red.png")})

;; ----- core.async -----

(defonce echo-chan (async/chan 10000)) ; buffered channel
(defonce proxy-chan (async/chan 10000)) ; buffered channel
(defonce incoming-chan (async/chan 10000)) ; buffered channel
(defonce lookup-chan (async/chan 10000)) ; buffered channel
(defonce persist-chan (async/chan 10000)) ; buffered channel

(defonce echo-go (atom nil))
(defonce proxy-go (atom nil))
(defonce incoming-go (atom nil))
(defonce lookup-go (atom nil))
(defonce persist-go (atom nil))

;; ----- Slack user lookup cache -----

(defonce empty-cache (cache/lu-cache-factory {} :threshold 10000))
(defonce SlackUserCache (atom empty-cache))

;; ----- Utility functions -----

(defn- present? [key coll]
  (let [val (get coll key)]
    (if (s/blank? (str val)) false val)))

(defn- lookup-message-author
  "Given a Slack user ID and a bot-token, lookup the user with Slack API and return their info."
  [bot-token slack-user-id]
  (timbre/debug "Slack lookup of:" slack-user-id "with:" bot-token)
  (let [init-author default-slack-author
        user-data (when bot-token (slack/get-user-info bot-token slack-user-id))
        user-name (when user-data
                    (or (present? :real_name_normalized user-data)
                        (present? :real_name user-data)
                        (present? :first_name user-data)
                        (present? :last_name user-data)
                        (present? :name user-data)
                        (:name default-slack-author)))
        avatar-url (when user-data
                      (or (:image_512 user-data)
                          (:image_192 user-data)
                          (:image_72 user-data)
                          (:image_48 user-data)
                          (:image_32 user-data)
                          (:image_24 user-data)
                          (get-in user-data [:profile :image_512])
                          (get-in user-data [:profile :image_192])
                          (get-in user-data [:profile :image_72])
                          (get-in user-data [:profile :image_48])
                          (get-in user-data [:profile :image_32])
                          (get-in user-data [:profile :image_24])
                          (:avatar-url default-slack-author)))]
    (if user-data
      (merge init-author {:name user-name :avatar-url avatar-url})
      init-author)))
 
(defn- link-message
  "
  Make Slack message text that includes an explanation and link to the resource to use before the message text.

  Link structure: /<org-slug>/<board-slug>/<resource-uuid>
  "
  [intro resource interaction]
  (if-let* [org-slug (:org-slug resource)
            board-slug (:board-slug resource)
            resource-uuid (:uuid resource)
            description (or (:headline resource) (:title resource))
            resource-url (s/join "/" [c/ui-server-url org-slug board-slug "post" resource-uuid])]

    (str intro " <" resource-url "|" description ">")
    
    ;; Not expected to be here, but let's not completely crap the bed
    (do 
      (timbre/error "Unable to make Slack link for comment:" (:uuid interaction))
      intro)))

;; ----- DB Persistence -----

(def entry-table-name "entries")

(defn- handle-mirror-result
  "Store Slack thread (ts) in interaction for future replies."
  [db-pool result slack-channel interaction]
  (when-not (:thread slack-channel) ; nothing to do if comment already has a Slack thread
    (pool/with-pool [conn db-pool]
      (if-let* [slack-thread (assoc slack-channel :thread (:ts result))
                original-resource (db-common/read-resource conn entry-table-name (:resource-uuid interaction))]
        (do
          (timbre/info "Persisting slack thread:" slack-thread "to resource:" (:resource-uuid interaction))
          (db-common/update-resource conn entry-table-name :uuid original-resource
              (merge original-resource {:slack-thread slack-thread}) (:updated-at original-resource)))

        (timbre/error "Unable to persist slack thread:" result "to resource:" (:resource-uuid interaction))))))

(defn- handle-message
  "
  Look for an resource for the specified channel-id and thread. If one exists, create a new comment for
  the Slack message.
  "
  [db-pool channel-id thread body]
  (timbre/debug "Checking for slack thread:" thread "on channel:" channel-id)
  (pool/with-pool [conn db-pool]
    (when-let [resource (first (db-common/read-resources conn entry-table-name
                          "slack-thread-channel-id-thread" [[channel-id thread]]))]
      (timbre/debug "Found resource:" (:uuid resource) "for slack thread:" thread "on channel:" channel-id)
      ;; request to lookup the comment's author
      (>!! lookup-chan {:resource resource :message body}))))

(defn- persist-message-as-comment
  "Persist a new comment as a mirror of the incoming Slack message."
  [db-pool resource message author]
  ;; Create the comment
  (timbre/info "Creating a new comment for resource:" (:uuid resource))
  (pool/with-pool [conn db-pool]
    (if-let [result (interact-res/create-comment! conn (interact-res/->comment resource 
                        {:body (-> message :event :text)} author))]
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
            (let [slack-bot (:slack-bot message)
                  bot-token (:token slack-bot)
                  slack-user (:slack-user message)
                  token (:token slack-user)
                  interaction (:comment message)
                  resource (:resource message)
                  uuid (:uuid interaction)
                  channel (:slack-channel message)
                  slack-channel (if bot-token (assoc channel :bot-token bot-token) channel)
                  channel-id (:channel-id slack-channel)
                  thread (:thread slack-channel)
                  text (.text (soup/parse (:body interaction)))]
              (if thread
                (timbre/info "Echoing comment to Slack:" uuid "on thread" thread)
                (timbre/info "Echoing comment to Slack:" uuid "as a new thread"))
              (let [result (if thread
                              (slack/echo-message token channel-id thread text)
                              (slack/echo-message token channel-id
                                (link-message echo-intro resource interaction) text))]
                (if (:ok result)
                  ;; Echo was successful
                  (do 
                    (timbre/info "Echoed to Slack:" uuid)
                    (handle-mirror-result db-pool result slack-channel interaction))
                  ;; Echo was NOT successful
                  (if bot-token
                    ;; Can't echo directly with this user, let's try proxying instead
                    (do
                      (timbre/info "Unable to echo comment:" uuid "to Slack:" result "trying to proxy instead")
                      (>!! proxy-chan message))
                    ;; No bot, so nothing we can do but error out the echo request
                    (timbre/error "Unable to echo comment:" uuid "to Slack:" result)))))
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
                  bot-token (:token slack-bot)
                  token (:token slack-bot)
                  interaction (:comment message)
                  resource (:resource message)
                  uuid (:uuid interaction)
                  channel (:slack-channel message)
                  slack-channel (if bot-token (assoc channel :bot-token bot-token) channel)
                  channel-id (:channel-id slack-channel)
                  thread (:thread slack-channel)
                  author (-> interaction :author :name)
                  text (str "*" author "* said:\n> " (.text (soup/parse (:body interaction))))]
              (if thread
                (timbre/info "Proxying comment to Slack:" uuid "on thread:" thread)
                (timbre/info "Proxying comment to Slack:" uuid "as a new thread"))
              (let [result (if thread
                              (slack/proxy-message token channel-id thread text)
                              (slack/proxy-message token channel-id
                                (link-message (str "*" author "* " echo-intro) resource interaction) text))]
                (if (:ok result)
                  (do
                    (timbre/info "Proxied to Slack:" uuid)
                    (handle-mirror-result db-pool result slack-channel interaction))
                  (timbre/error "Unable to proxy comment:" uuid "to Slack:" result))))
            (catch Exception e
              (timbre/error e)))))))))

;; ----- Event loops (incoming from Slack) -----

(defn- incoming-loop 
  "core.async consumer to handle incoming Slack messages."
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

(defn- lookup-loop 
  "core.async consumer to lookup Slack users using Slack API and our cache."
  []
  (reset! lookup-go true)
  (async/go (while @lookup-go
    (timbre/debug "Slack lookup waiting...")
    (let [message (<!! lookup-chan)]
      (timbre/debug "Processing message on lookup channel...")
      (if (:stop message)
        (do (reset! lookup-go false) (timbre/info "Slack lookup stopped."))
        (async/thread
          (try
            (let [bot-token (-> message :resource :slack-thread :bot-token)
                  slack-user-id (-> message :message :event :user)]

              (if (cache/has? @SlackUserCache slack-user-id) ; lookup comment's author in cache

                (do
                  (timbre/debug "Slack user cache hit on:" slack-user-id)
                  (reset! SlackUserCache (cache/hit @SlackUserCache slack-user-id))) ; update cache with a hit
            
                (let [_log (timbre/debug "Slack user cache miss on:" slack-user-id)
                      author (lookup-message-author bot-token slack-user-id)]
                  (timbre/debug "Caching Slack user:" slack-user-id "as:" author)
                  (reset! SlackUserCache (cache/miss @SlackUserCache slack-user-id author)))) ; update cache

              (>!! persist-chan (assoc message :author (cache/lookup @SlackUserCache slack-user-id))))
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
            (persist-message-as-comment db-pool (:resource message) (:message message) (:author message))
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
    (lookup-loop)
    (persist-loop db-pool)))

(defn stop
  "Stop the core.async channel consumers for mirroring comments with Slack."
  []
  (timbre/info "Clearing the Slack user cache...")
  (reset! SlackUserCache empty-cache)
  (when @echo-go
    (timbre/info "Stopping Slack echo...")
    (>!! echo-chan {:stop true}))
  (when @proxy-go
    (timbre/info "Stopping Slack proxy...")
    (>!! proxy-chan {:stop true}))
  (when @incoming-go
    (timbre/info "Stopping Slack incoming...")
    (>!! incoming-chan {:stop true}))
  (when @lookup-go
    (timbre/info "Stopping Slack lookup...")
    (>!! lookup-chan {:stop true}))
  (when @persist-go
    (timbre/info "Stopping Slack persist...")
    (>!! persist-chan {:stop true})))