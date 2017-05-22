(ns oc.interaction.resources.interaction
  "Interaction (comment, entry reaction, comment reaction) stored in RethinkDB."
  (:require [clojure.walk :refer (keywordize-keys)]
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
  :topic-slug lib-schema/NonBlankStr
  :entry-uuid lib-schema/UniqueID
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

(defn- ->interaction [interaction-props user]
  (let [ts (db-common/current-timestamp)]
    (-> interaction-props
        keywordize-keys
        clean
        (assoc :uuid (db-common/unique-id))
        (assoc :author (lib-schema/author-for-user user))
        (assoc :created-at ts)
        (assoc :updated-at ts))))

(schema/defn ^:always-validate ->comment :- Comment
  "
  Take a minimal map describing a comment and a user and 'fill the blanks' with
  any missing properties.
  "
  [comment-props user :- lib-schema/User]
  {:pre [(map? comment-props)]}
  (->interaction comment-props user))


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

(schema/defn ^:always-validate delete-interaction!
  [conn uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-resource conn table-name uuid))

;; ----- Collection of interactions -----

(schema/defn ^:always-validate get-interactions-by-entry
  "Given the UUID of the entry, return the interactions (sorted by :created-at)."
  [conn entry-uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/read-resources conn table-name :entry-uuid entry-uuid))

(schema/defn ^:always-validate get-comments-by-entry
  "Given the UUID of the entry, return the comments (sorted by :created-at)."
  [conn entry-uuid :- lib-schema/UniqueID]
  (filter :body (get-interactions-by-entry conn entry-uuid)))

(schema/defn ^:always-validate get-reactions-by-entry
  "
  Given the UUID of the entry, and optionally a specific reaction unicode character,
  return the reactions (sorted by :created-at).
  "
  ([conn entry-uuid :- lib-schema/UniqueID]
  (filter :reaction (get-interactions-by-entry conn entry-uuid)))
  
  ([conn entry-uuid :- lib-schema/UniqueID reaction-unicode :- lib-schema/NonBlankStr]
  (filter #(= reaction-unicode (:reaction %)) (get-interactions-by-entry conn entry-uuid))))

;; ----- Armageddon -----

(defn delete-all-interactions!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-all-resources! conn table-name))