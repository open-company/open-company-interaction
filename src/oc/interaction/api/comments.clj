(ns oc.interaction.api.comments
  "Liberator API for comment resources."
  (:require [compojure.core :as compojure :refer (ANY OPTIONS POST DELETE)]
            [if-let.core :refer (if-let*)]
            [taoensso.timbre :as timbre]
            [liberator.core :refer (defresource by-method)]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.lib.schema :as lib-schema]
            [oc.interaction.config :as config]
            [oc.interaction.api.common :as common]
            [oc.interaction.async.watcher :as watcher]
            [oc.interaction.representations.interaction :as interact-rep]
            [oc.interaction.resources.interaction :as interact-res]))

;; ----- Validations -----

(defn valid-new-comment?
  "Determine if the provided body constitutes a valid new comment."
  [conn ctx org-uuid board-uuid topic-slug entry-uuid]
  (try
    ;; Create the new interaction from the data provided
    (let [interact-map (:data ctx)
          interaction (merge interact-map {
                        :org-uuid org-uuid
                        :board-uuid board-uuid
                        :topic-slug topic-slug
                        :entry-uuid entry-uuid})
          author (:user ctx)]
      {:new-interaction (interact-res/->comment interaction author)})

    (catch clojure.lang.ExceptionInfo e
      [false, {:reason (.getMessage e)}]))) ; Not a valid new interaction

(defn- valid-comment-update? [conn ctx comment-uuid comment-props]
  (if-let [existing-comment (or (:existing-comment ctx) (interact-res/get-interaction conn comment-uuid))]
    ;; Merge the existing comment with the new updates
    (let [updated-comment (merge existing-comment (interact-res/clean comment-props))]
      (if (lib-schema/valid? interact-res/Comment updated-comment)
        {:existing-comment existing-comment :updated-comment updated-comment}
        [false, {:updated-comment updated-comment}])) ; invalid update
    
    true)) ; no existing comment, so this will fail existence check later

(defn author-match?
  "Determine if the comment specified by the UUID was authored by the current user."
  [conn ctx comment-uuid]
  (if-let* [user-id (-> ctx :user :user-id)
            existing-comment (or (:existing-comment ctx) (interact-res/get-interaction conn comment-uuid))
            author-id (-> existing-comment :author :user-id)]
    (if (= user-id author-id)
      [true, {:existing-comment existing-comment}]
      (do (timbre/warn "Operation by" user-id "not permitted on comment" comment-uuid) false))))

;; ----- Actions -----

(defn- delete-comment [conn ctx comment-uuid]
  (timbre/info "Deleting comment:" comment-uuid)
  (if-let* [existing-comment (:existing-comment ctx)
            _delete-result (interact-res/delete-interaction! conn comment-uuid)]
    (do 
      (timbre/info "Deleted entry:" comment-uuid)
      (watcher/notify-watcher :interaction-comment/delete existing-comment)
      true)
    (do (timbre/info "Failed deleting entry:" comment-uuid) false)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for operations on a specific comment
(defresource comment-item [conn org-uuid board-uuid topic-slug entry-uuid comment-uuid]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :patch :delete]

  ;; Media type client accepts
  :available-media-types [interact-rep/comment-media-type]
  :handle-not-acceptable (api-common/only-accept 406 interact-rep/comment-media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :patch (fn [ctx] (api-common/known-content-type? ctx interact-rep/comment-media-type))
    :delete true})

  ;; Authorization
  :allowed? (fn [ctx] (author-match? conn ctx comment-uuid))

  ;; Validations
  :processable? (by-method {
    :options true
    :patch (fn [ctx] (valid-comment-update? conn ctx comment-uuid))
    :delete true})

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [entry (or (:existing-entry ctx)
                                         (common/entry-exists? conn org-uuid board-uuid topic-slug entry-uuid))
                               existing-comment (or (:existing-comment ctx)
                                                    (interact-res/get-interaction conn comment-uuid))
                               _matches? (and (= (:org-uuid existing-comment) org-uuid)
                                              (= (:board-uuid existing-comment) board-uuid)
                                              (= (:topic-slug existing-comment) topic-slug)
                                              (= (:entry-uuid existing-comment) entry-uuid))]
                        {:existing-entry entry :existing-comment existing-comment :existing? true}
                        false))

  ;; Actions
  :patch! true
  :delete! (fn [ctx] (when (:existing? ctx) (delete-comment conn ctx comment-uuid)))

  ;; Responses
  :handle-ok "foo"
  :handle-no-content (fn [ctx] (when-not (:existing? ctx) (api-common/missing-response)))
)

;; A resource for operations on a list of comments
(defresource comment-list [conn org-uuid board-uuid topic-slug entry-uuid]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :post]

  ;; Media type client accepts
  :available-media-types (by-method {
                            :get [interact-rep/comment-collection-media-type]
                            :post [interact-rep/comment-media-type]})
  :handle-not-acceptable (by-method {
                            :get (api-common/only-accept 406 interact-rep/comment-collection-media-type)
                            :post (api-common/only-accept 406 interact-rep/comment-media-type)})
  
  ;; Media type client sends
  :known-content-type? (by-method {
                          :options true
                          :get true
                          :post (fn [ctx] (api-common/known-content-type? ctx interact-rep/comment-media-type))})

  ;; Authorization
  ;; TODO
  :allowed? true

  ;; Validations
  :processable? (by-method {
    :options true
    :get true
    :post (fn [ctx] (valid-new-comment? conn ctx org-uuid board-uuid topic-slug entry-uuid))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [entry (common/entry-exists? conn org-uuid board-uuid topic-slug entry-uuid)]
                        (let [comments (interact-res/get-comments-by-entry conn entry-uuid)]
                          {:existing-entry entry :existing-comments comments})
                        false))

  ;; Actions
  :post! (fn [ctx] (common/create-interaction conn ctx))

  ;; Responses
  :handle-ok (fn [ctx] (interact-rep/render-interaction-list org-uuid board-uuid topic-slug entry-uuid
                          (:existing-comments ctx) (:user ctx)))
  :handle-created (fn [ctx] (let [new-interaction (:created-interaction ctx)]
                              (api-common/location-response
                                (interact-rep/url new-interaction)
                                (interact-rep/render-interaction new-interaction :author)
                                interact-rep/comment-media-type)))
  :handle-unprocessable-entity (fn [ctx]
    (api-common/unprocessable-entity-response (:reason ctx))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes

      ;; Comment listing and creation
      (ANY "/orgs/:org-uuid/boards/:board-uuid/topics/:topic-slug/entries/:entry-uuid/comments"
        [org-uuid board-uuid topic-slug entry-uuid]
        (pool/with-pool [conn db-pool] (comment-list conn org-uuid board-uuid topic-slug entry-uuid)))
      (ANY "/orgs/:org-uuid/boards/:board-uuid/topics/:topic-slug/entries/:entry-uuid/comments/"
        [org-uuid board-uuid topic-slug entry-uuid]
        (pool/with-pool [conn db-pool] (comment-list conn org-uuid board-uuid topic-slug entry-uuid)))

      ;; Comment editing and removal
      (ANY "/orgs/:org-uuid/boards/:board-uuid/topics/:topic-slug/entries/:entry-uuid/comments/:comment-uuid"
        [org-uuid board-uuid topic-slug entry-uuid comment-uuid]
        (pool/with-pool [conn db-pool] (comment-item conn org-uuid board-uuid topic-slug entry-uuid comment-uuid)))
      (ANY "/orgs/:org-uuid/boards/:board-uuid/topics/:topic-slug/entries/:entry-uuid/comments/:comment-uuid/"
        [org-uuid board-uuid topic-slug entry-uuid comment-uuid]
        (pool/with-pool [conn db-pool] (comment-item conn org-uuid board-uuid topic-slug entry-uuid comment-uuid))))))