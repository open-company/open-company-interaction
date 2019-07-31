(ns oc.interaction.async.notification
  "
  Async publish of notification events to AWS SNS.
  "
  (:require [clojure.core.async :as async :refer (<! >!!)]
            [taoensso.timbre :as timbre]
            [cheshire.core :as json]
            [amazonica.aws.sns :as sns]
            [schema.core :as schema]
            [oc.lib.db.common :as db-common]
            [oc.lib.schema :as lib-schema]
            [oc.lib.time :as oc-time]
            [oc.interaction.config :as config]
            [oc.interaction.resources.interaction :as interact-res]))

;; ----- core.async -----

(defonce notification-chan (async/chan 10000)) ; buffered channel

(defonce notification-go (atom true))

;; ----- Utility functions -----

(defn- resource-type [content]
  (cond
    (:reaction content) :reaction
    :else :comment))

;; ----- Data schema -----

(defn- notification-type? [notification-type] (#{:add :update :delete} notification-type))

(defn- resource-type? [resource-type] (#{:reaction :comment} resource-type))

(def NotificationTrigger
  "
  A trigger for one of the various types of notifications that are published:

  add - the interaction is newly created, this happens when a comment or reaction is added
  update - the comment should be refreshed, this happens when a comment is updated
  delete - the interaction is deleted, this happens when a comment or reaction is removed

  The notification trigger contains the type of resource as `resource-type` and the content as `new` and/or
  `old` in a key called `content`.

  The user whose actions triggered the notification is included as `user`.

  A timestamp for when the notice was created is included as `notification-at`.
  "
  {:notification-type (schema/pred notification-type?)
   :resource-type (schema/pred resource-type?)
   :uuid lib-schema/UniqueID
   (schema/optional-key :secure-uuid) lib-schema/UniqueID
   :org-uuid lib-schema/UniqueID
   :org {schema/Any schema/Any}
   :board-uuid lib-schema/UniqueID
   :content {
    (schema/optional-key :new) (schema/conditional #(= (resource-type %) :comment) interact-res/Comment
                                                   :else interact-res/Reaction)
    (schema/optional-key :old) (schema/conditional #(= (resource-type %) :comment) interact-res/Comment
                                                   :else interact-res/Reaction)}
   :user lib-schema/User
   :item-publisher lib-schema/User
   :notification-at lib-schema/ISO8601
   (schema/optional-key :notify-users) [lib-schema/Author]})

;; ----- Event handling -----

(defn- handle-notification-message
  [trigger]
  (timbre/debug "Notification request of:" (:notification-type trigger)
               "for:" (:uuid trigger) "to topic:" config/aws-sns-interaction-topic-arn)
  (timbre/trace "Notification request:" trigger)
  (schema/validate NotificationTrigger trigger)
  (timbre/info "Sending request to topic:" config/aws-sns-interaction-topic-arn)
  (sns/publish
    {:access-key config/aws-access-key-id
     :secret-key config/aws-secret-access-key}
     :topic-arn config/aws-sns-interaction-topic-arn
     :subject (str (name (:notification-type trigger))
                   " on " (name (:resource-type trigger))
                   ": " (-> trigger :current :uuid))
     :message (json/generate-string trigger {:pretty true}))
  (timbre/info "Request sent to topic:" config/aws-sns-interaction-topic-arn))

;; ----- Event loop -----

(defn- notification-loop []
  (reset! notification-go true)
  (timbre/info "Starting notification...")
  (async/go (while @notification-go
    (timbre/debug "Notification waiting...")
    (let [message (<! notification-chan)]
      (timbre/debug "Processing message on notification channel...")
      (if (:stop message)
        (do (reset! notification-go false) (timbre/info "Notification stopped."))
        (async/thread
          (try
            (handle-notification-message message)
          (catch Exception e
            (timbre/error e)))))))))

;; ----- Notification triggering -----

(defn ->trigger [conn notification-type interaction content user]
  (let [resource-uuid (or (-> content :new :resource-uuid) (-> content :old :resource-uuid))
        item (or (db-common/read-resource conn "entries" resource-uuid) ; for post reaction or comment
                 (db-common/read-resource conn "interactions" resource-uuid)) ; for comment reaction
        comment-reaction? (if (:resource-uuid item) true false)
        org-uuid (:org-uuid interaction)
        resource-type (resource-type interaction)
        org (first (db-common/read-resources conn "orgs" "uuid" org-uuid))
        should-add-other-users? (and (= notification-type :add)
                                     (= resource-type :comment))
        resource (when should-add-other-users?
                   (:existing-resource content))
        resource-comments (when should-add-other-users?
                            (:existing-comments content))
        all-authors (when should-add-other-users?
                      (map :author resource-comments))
        all-author-ids (when should-add-other-users?
                         (->> all-authors
                              (map :user-id)
                              set
                              (disj (-> resource :publisher :user-id))
                              (disj (-> item :author :user-id))))
        distinct-users (when should-add-other-users?
                         (map (fn [user-id] (first (filterv #(= (:user-id %) user-id) all-authors))) all-author-ids))
        trigger {:notification-type notification-type
                 :resource-type resource-type
                 :uuid (:uuid interaction)
                 :org-uuid (:org-uuid interaction)
                 :org org
                 :board-uuid (:board-uuid interaction)
                 :content content
                 :user user
                 :notify-users distinct-users
                 :item-publisher (if comment-reaction?
                                    (:author item) ; author of the comment
                                    (:publisher item)) ; publisher of the entry (interactions only occur on published entries)
                 :notification-at (oc-time/current-timestamp)}]
    (if comment-reaction? trigger (assoc trigger :secure-uuid (:secure-uuid item)))))

(schema/defn ^:always-validate send-trigger! [trigger :- NotificationTrigger]
  (if (clojure.string/blank? config/aws-sns-interaction-topic-arn)
    (timbre/debug "Skipping a notification for:" (or (-> trigger :content :old :uuid)
                                                     (-> trigger :content :new :uuid)))
    (do
      (timbre/debug "Triggering a notification for:" (or (-> trigger :content :old :uuid)
                                                         (-> trigger :content :new :uuid)))
      (timbre/trace "Sending trigger:" trigger)
      (>!! notification-chan trigger))))

;; ----- Component start/stop -----

(defn start
  "Start the core.async event loop."
  []
  (when-not (clojure.string/blank? config/aws-sns-interaction-topic-arn) ; do we care about getting SNS notifications?
    (notification-loop)))

(defn stop
  "Stop the the core.async event loop."
  []
  (when @notification-go
    (timbre/info "Stopping notification...")
    (>!! notification-chan {:stop true})))