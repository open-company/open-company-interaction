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
            [oc.interaction.async.notification :as notification]
            [oc.interaction.async.watcher :as watcher]
            [oc.interaction.representations.interaction :as interact-rep]
            [oc.interaction.resources.interaction :as interact-res]))

;; ----- Validations -----

(defn valid-new-comment?
  "Determine if the provided body constitutes a valid new comment."
  [conn ctx org-uuid board-uuid resource-uuid]
  (try
    ;; Create the new interaction from the data provided
    (let [interact-map* (:data ctx)
          author-wants-follow? (:author-wants-follow? interact-map*)
          interact-map (dissoc interact-map* :author-wants-follow?)
          ;; Get the sender client-id from the header
          ;; and store it in the ctx to be passed to the watcher channel later
          ;; to skip the sender client when sending the message
          sender-ws-client-id (api-common/get-interaction-client-id ctx)]
      (if (or (empty? (:parent-uuid interact-map))
              (interact-res/get-comment conn (:parent-uuid interact-map)))
        (let [interaction (merge interact-map {
                          :org-uuid org-uuid
                          :board-uuid board-uuid
                          :resource-uuid resource-uuid})
              author (:user ctx)]
          {:new-interaction (interact-res/->comment interaction author)
           :author-wants-follow? author-wants-follow?
           :new-interaction-client-id sender-ws-client-id})
        [false, {:reason "Parent comment does not exist."}]))

    (catch clojure.lang.ExceptionInfo e
      [false, {:reason (.getMessage e)}]))) ; Not a valid new interaction

(defn- valid-comment-update? [conn ctx comment-uuid comment-props]
  (if-let [existing-comment (or (:existing-comment ctx) (interact-res/get-interaction conn comment-uuid))]
    ;; Merge the existing comment with the new updates
    (let [updated-comment (merge existing-comment (interact-res/clean comment-props))]
      (if (and (lib-schema/valid? interact-res/Comment updated-comment)
               ;; If parent-uuid is passed make sure it's not different
               (or (not (contains? comment-props :parent-uuid))
                   (and (contains? comment-props :parent-uuid)
                        ;; Using str to avoid errors like "" not equal to nil
                        (= (str (:parent-uuid comment-props)) (str (:parent-uuid existing-comment))))))
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

(defn- update-comment [conn ctx comment-uuid]
  (timbre/info "Updating comment:" comment-uuid)
  (if-let* [existing-comment (:existing-comment ctx)
            updated-comment (:updated-comment ctx)
            _updated-result (interact-res/update-interaction! conn (:uuid updated-comment) updated-comment)]
    (do 
      (timbre/info "Updated comment:" comment-uuid)
      (notification/send-trigger! (notification/->trigger conn
                                                          :update updated-comment
                                                          {:new updated-comment
                                                           :old existing-comment
                                                           :existing-comments (:existing-comments ctx)} (:user ctx)))
      (watcher/notify-watcher :interaction-comment/update updated-comment nil (api-common/get-interaction-client-id ctx))
      {:updated-comment (common/transform-comment updated-comment)})

    (do (timbre/error "Failed updating comment:" comment-uuid) false)))

(defn- delete-comment [conn ctx comment-uuid]
  (timbre/info "Deleting comment:" comment-uuid)
  (if-let* [existing-thread (:existing-comment-thread ctx)
            _delete-result (interact-res/delete-comment-thread! conn comment-uuid)]
    (do 
      (timbre/info "Deleted comment:" comment-uuid)
      (doseq [comment existing-thread]
        (notification/send-trigger! (notification/->trigger conn :delete comment
                                                            {:old comment} (:user ctx)))
        (watcher/notify-watcher :interaction-comment/delete comment nil (api-common/get-interaction-client-id ctx)))
      true)
    (do (timbre/info "Failed deleting comment:" comment-uuid) false)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for operations on a specific comment
(defresource comment-item [conn org-uuid board-uuid resource-uuid comment-uuid]
  (api-common/open-company-id-token-resource config/passphrase) ; verify validity and presence of required JWToken

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
    :patch (fn [ctx] (valid-comment-update? conn ctx comment-uuid (:data ctx)))
    :delete true})

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [resource (or (:existing-resource ctx)
                                         (common/resource-exists? conn org-uuid board-uuid resource-uuid))
                               existing-comment (or (:existing-comment ctx)
                                                    (interact-res/get-interaction conn comment-uuid))
                               existing-comment-thread (or (:existing-thread ctx)
                                                           (interact-res/get-comment-thread conn comment-uuid))
                               _matches? (and (= (:org-uuid existing-comment) org-uuid)
                                              (= (:board-uuid existing-comment) board-uuid)
                                              (= (:resource-uuid existing-comment) resource-uuid))]
                        {:existing-resource resource :existing-comment existing-comment 
                         :existing-comment-thread existing-comment-thread :existing? true}
                        false))

  ;; Actions
  :patch! (fn [ctx] (when (:existing? ctx) (update-comment conn ctx comment-uuid)))
  :delete! (fn [ctx] (when (:existing? ctx) (delete-comment conn ctx comment-uuid)))

  ;; Responses
  :handle-ok (fn [ctx] (interact-rep/render-interaction (:updated-comment ctx) :author))
  :handle-no-content (fn [ctx] (when-not (:existing? ctx) (api-common/missing-response))))

;; A resource for operations on a list of comments
(defresource comment-list [conn org-uuid board-uuid resource-uuid]
  (api-common/open-company-id-token-resource config/passphrase) ; verify validity and presence of required JWToken

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
    :post (fn [ctx] (valid-new-comment? conn ctx org-uuid board-uuid resource-uuid))})

  ;; Existentialism
  :exists? (fn [_] (if-let [resource (common/resource-exists? conn org-uuid board-uuid resource-uuid)]
                        (let [comments (interact-res/get-comments-by-resource conn resource-uuid)]
                          {:existing-resource resource :existing-comments comments})
                        false))

  ;; Actions
  :post! (fn [ctx] (let [result (common/create-interaction conn ctx)
                         new-comment (:created-interaction result)]
                     (notification/send-trigger! (notification/->trigger conn :add new-comment
                                                                         {:new new-comment
                                                                          :existing-comments (:existing-comments ctx)
                                                                          :existing-resource (:existing-resource ctx)}
                                                                         (:user ctx)
                                                                         (:author-wants-follow? ctx)))
                     result))

  ;; Responses
  :handle-ok (fn [ctx] (interact-rep/render-interaction-list org-uuid board-uuid resource-uuid
                        (:existing-comments ctx) (:user ctx)))
  :handle-created (fn [ctx] (let [new-interaction (:created-interaction ctx)]
                              (interact-rep/render-interaction new-interaction :author))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes

      ;; Comment listing and creation
      (ANY "/orgs/:org-uuid/boards/:board-uuid/resources/:resource-uuid/comments"
        [org-uuid board-uuid resource-uuid]
        (pool/with-pool [conn db-pool] (comment-list conn org-uuid board-uuid resource-uuid)))
      (ANY "/orgs/:org-uuid/boards/:board-uuid/resources/:resource-uuid/comments/"
        [org-uuid board-uuid resource-uuid]
        (pool/with-pool [conn db-pool] (comment-list conn org-uuid board-uuid resource-uuid)))

      ;; Comment editing and removal
      (ANY "/orgs/:org-uuid/boards/:board-uuid/resources/:resource-uuid/comments/:comment-uuid"
        [org-uuid board-uuid resource-uuid comment-uuid]
        (pool/with-pool [conn db-pool] (comment-item conn org-uuid board-uuid resource-uuid comment-uuid)))
      (ANY "/orgs/:org-uuid/boards/:board-uuid/resources/:resource-uuid/comments/:comment-uuid/"
        [org-uuid board-uuid resource-uuid comment-uuid]
        (pool/with-pool [conn db-pool] (comment-item conn org-uuid board-uuid resource-uuid comment-uuid))))))

