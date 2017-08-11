(ns oc.interaction.resources.interaction
  "Interaction (comment, reaction, comment reaction) stored in RethinkDB."
  (:require [clojure.walk :refer (keywordize-keys)]
            [if-let.core :refer (if-let*)]
            [oc.lib.db.common :as db-common]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]))

;; ----- RethinkDB metadata -----

(def table-name "interactions")
(def primary-key :uuid)

(def reserved-properties
  "Properties of a resource that can't be specified during a create and are ignored during an update."
  #{:uuid :author :links :created-at :updated-at})

;; ----- Schema -----

(def Interaction {
  :uuid lib-schema/UniqueID
  :org-uuid lib-schema/UniqueID
  :board-uuid lib-schema/UniqueID
  :resource-uuid lib-schema/UniqueID
  :author lib-schema/Author
  :created-at lib-schema/ISO8601
  :updated-at lib-schema/ISO8601})

(def Comment (merge Interaction {:body schema/Str}))

(def Reaction (merge Interaction {:reaction schema/Str}))

(def CommentReaction (merge Interaction {
  :interaction-uuid lib-schema/UniqueID
  :reaction schema/Str}))

;; ----- Utility functions -----

(defn clean
  "Remove any reserved properties from the resource."
  [resource]
  (apply dissoc resource reserved-properties))

;; ----- Interaction CRUD -----

(defn- ->interaction 

  ([interaction-props user]
  (let [ts (db-common/current-timestamp)]
    (-> interaction-props
        keywordize-keys
        clean
        (assoc :uuid (db-common/unique-id))
        (assoc :author (lib-schema/author-for-user user))
        (assoc :created-at ts)
        (assoc :updated-at ts))))

  ([resource interaction-props user]
  (if-let* [resource-uuid (:uuid resource)
            board-uuid (:board-uuid resource)
            org-uuid (:org-uuid resource)
            resource-props {:resource-uuid resource-uuid
                            :board-uuid board-uuid
                            :org-uuid org-uuid}]
    (->interaction (merge resource-props interaction-props) user))))

(schema/defn ^:always-validate ->comment :- Comment
  "
  Take an optional resource, a minimal map describing a comment, and a user then 'fill the blanks' with
  any missing properties and return the Comment map.
  "
  ([comment-props user :- lib-schema/User]
  {:pre [(map? comment-props)]}
  (->interaction comment-props user))

  ([resource comment-props user :- lib-schema/User]
  {:pre [(map? resource)
         (map? comment-props)]}
  (->interaction resource comment-props user)))

(schema/defn ^:always-validate ->reaction :- Reaction
  "
  Take a minimal map describing a reaction and a user and 'fill the blanks' with
  any missing properties.
  "
  [reaction-props user :- lib-schema/User]
  {:pre [(map? reaction-props)]}
  (->interaction reaction-props user))

(schema/defn ^:always-validate ->comment-reaction :- CommentReaction
  "
  Take a minimal map describing a reaction to a comment and a user and 'fill the blanks' with
  any missing properties.
  "
  [reaction-props user :- lib-schema/User]
  {:pre [(map? reaction-props)]}
  (->interaction reaction-props user))

(schema/defn ^:always-validate create-comment!
  "Create a comment in the system. Throws a runtime exception if the comment doesn't conform to the Comment schema."
  [conn interaction :- Comment]
  {:pre [(db-common/conn? conn)]}
  (db-common/create-resource conn table-name interaction (db-common/current-timestamp)))

(schema/defn ^:always-validate create-reaction!
  "Create a reaction in the system. Throws a runtime exception if the reaction doesn't conform to the Reaction schema."
  [conn interaction :- Reaction]
  {:pre [(db-common/conn? conn)]}
  (db-common/create-resource conn table-name interaction (db-common/current-timestamp)))

(schema/defn ^:always-validate create-comment-reaction!
  "
  Create a reaction to a comment in the system. Throws a runtime exception if the reaction doesn't conform to the
  CommentReaction schema.
  "
  [conn interaction :- CommentReaction]
  {:pre [(db-common/conn? conn)]}
  (db-common/create-resource conn table-name interaction (db-common/current-timestamp)))

(schema/defn ^:always-validate get-interaction :- (schema/maybe (schema/either Comment Reaction))
  "Given the uuid of the interaction, retrieve the interaction, or return nil if it doesn't exist."
  ([conn uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/read-resource conn table-name uuid)))

(schema/defn ^:always-validate update-interaction! :- (schema/maybe (schema/either Comment Reaction))
  "
  Given the interaction's UUID and an updated interaction property map, update the interaction
  and return the updated interaction on success.

  Throws an exception if the merge of the prior interaction and the updated interaction property map doesn't conform
  to either the Comment or the Reaction schema.
  "
  [conn uuid :- lib-schema/UniqueID interaction]
  {:pre [(db-common/conn? conn)
         (map? interaction)]}
  (when-let [original-interact (get-interaction conn uuid)]
    (let [updated-interact (merge original-interact (clean interaction))]
      (if (:body updated-interact)
        (schema/validate Comment updated-interact)
        (schema/validate Reaction updated-interact))
      (db-common/update-resource conn table-name primary-key original-interact updated-interact))))

(schema/defn ^:always-validate delete-interaction!
  [conn uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-resource conn table-name uuid))

;; ----- Collection of interactions -----

(schema/defn ^:always-validate get-interactions-by-resource
  "Given the UUID of the resource, return the interactions (sorted by :created-at)."
  [conn resource-uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (sort-by :created-at (db-common/read-resources conn table-name :resource-uuid resource-uuid)))

(schema/defn ^:always-validate get-comments-by-resource
  "Given the UUID of the resource, return the comments (sorted by :created-at)."
  [conn resource-uuid :- lib-schema/UniqueID]
  (filter :body (get-interactions-by-resource conn resource-uuid)))

(schema/defn ^:always-validate get-reactions-by-resource
  "
  Given the UUID of the resource, and optionally a specific reaction unicode character,
  return the reactions (sorted by :created-at).
  "
  ([conn resource-uuid :- lib-schema/UniqueID]
  (filter :reaction (get-interactions-by-resource conn resource-uuid)))
  
  ([conn resource-uuid :- lib-schema/UniqueID reaction-unicode :- lib-schema/NonBlankStr]
  (filter #(= reaction-unicode (:reaction %)) (get-interactions-by-resource conn resource-uuid))))

;; ----- Armageddon -----

(defn delete-all-interactions!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-all-resources! conn table-name))