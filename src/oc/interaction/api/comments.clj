(ns oc.interaction.api.comments
  "Liberator API for comment resources."
  (:require [compojure.core :as compojure :refer (ANY OPTIONS POST DELETE)]
            [liberator.core :refer (defresource by-method)]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.interaction.config :as config]
            [oc.interaction.api.common :as common]
            [oc.interaction.representations.interaction :as interact-rep]
            [oc.interaction.resources.interaction :as interact-res]))

;; ----- Validations -----

(defn valid-new-comment? [conn ctx org-uuid board-uuid topic-slug entry-uuid]
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

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

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
  :exists? (fn [ctx] (if (common/entry-exists? conn org-uuid board-uuid topic-slug entry-uuid)
                        (let [comments (interact-res/get-comments-by-entry conn entry-uuid)]
                          {:existing-comments comments})
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
        (pool/with-pool [conn db-pool] (comment-list conn org-uuid board-uuid topic-slug entry-uuid))))))