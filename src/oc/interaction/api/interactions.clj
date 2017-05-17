(ns oc.interaction.api.interactions
  "Liberator API for interaction resources."
  (:require [if-let.core :refer (if-let*)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (ANY OPTIONS POST DELETE)]
            [liberator.core :refer (defresource by-method)]
            [oc.lib.db.pool :as pool]
            [oc.lib.db.common :as db-common]
            [oc.lib.api.common :as api-common]
            [oc.interaction.config :as config]
            [oc.interaction.representations.interaction :as interact-rep]
            [oc.interaction.resources.interaction :as interact-res]))

;; ----- Actions -----

(defn create-interaction [conn ctx]
  (timbre/info "Creating interaction.")
  (if-let* [new-interaction (:new-interaction ctx)
            interact-result (if (:body new-interaction)
                              (interact-res/create-comment! conn new-interaction) ; Create the comment
                              (interact-res/create-reaction! conn new-interaction))] ; Create the reaction
    ;; Interaction creation succeeded
    (let [uuid (:uuid interact-result)]
      (timbre/info "Created interaction:" uuid)
      {:created-interaction interact-result})
    
    (do (timbre/error "Failed creating interaction.") false)))

;; ----- Validations -----

(defn- valid-new-interaction? [conn ctx org-uuid board-uuid topic-slug entry-uuid]
  (try
    ;; Create the new interaction from the data provided
    (let [interact-map (:data ctx)
          interaction (merge interact-map {
                        :org-uuid org-uuid
                        :board-uuid board-uuid
                        :topic-slug topic-slug
                        :entry-uuid entry-uuid})
          author (:user ctx)]
      (if (:body interact-map)
        {:new-interaction (interact-res/->comment interaction author)}
        {:new-interaction (interact-res/->reaction interaction author)}))

    (catch clojure.lang.ExceptionInfo e
      [false, {:reason (.getMessage e)}]))) ; Not a valid new interaction

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for operations on a list of orgs
(defresource interaction-list [conn org-uuid board-uuid topic-slug entry-uuid]
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
    :post (fn [ctx] (valid-new-interaction? conn ctx org-uuid board-uuid topic-slug entry-uuid))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [org (first (db-common/read-resources conn "orgs" "uuid" org-uuid))
                               board (db-common/read-resource conn "boards" board-uuid)
                               board-org? (= (:org-uuid board) org-uuid)
                               topic-board? ((set (:topics board)) topic-slug)
                               entry (db-common/read-resource conn "entries" entry-uuid)
                               entry-topic? (= (:topic-slug entry) topic-slug)]
                        (let [interactions (interact-res/get-interactions-by-entry conn entry-uuid)]
                          {:existing-interactions interactions})
                        false))

  ;; Actions
  :post! (fn [ctx] (create-interaction conn ctx))

  ;; Responses
  :handle-ok (fn [ctx] (interact-rep/render-interaction-list org-uuid board-uuid topic-slug entry-uuid
                          (:existing-interactions ctx) (:user ctx)))
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
      ;; Comment / Reaction listing and creation
      (ANY "/orgs/:org-uuid/boards/:board-uuid/topics/:topic-slug/entries/:entry-uuid/comments"
        [org-uuid board-uuid topic-slug entry-uuid]
        (pool/with-pool [conn db-pool] (interaction-list conn org-uuid board-uuid topic-slug entry-uuid)))
      (ANY "/orgs/:org-uuid/boards/:board-uuid/topics/:topic-slug/entries/:entry-uuid/comments/"
        [org-uuid board-uuid topic-slug entry-uuid]
        (pool/with-pool [conn db-pool] (interaction-list conn org-uuid board-uuid topic-slug entry-uuid))))))