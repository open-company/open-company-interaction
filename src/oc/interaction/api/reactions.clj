(ns oc.interaction.api.reactions
  "Liberator API for reaction resources."
  (:require [if-let.core :refer (if-let*)]
            [compojure.core :as compojure :refer (ANY OPTIONS POST DELETE)]
            [liberator.core :refer (defresource by-method)]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.interaction.config :as config]
            [oc.interaction.api.common :as common]
            [oc.interaction.async.watcher :as watcher]
            [oc.interaction.representations.interaction :as interact-rep]
            [oc.interaction.resources.interaction :as interact-res]))

;; ----- Utility functions -----

(defn- reaction-for-user [reactions user-id]
  (some #(when (= (-> % :author :user-id) user-id) %) reactions))

;; ----- Actions -----

(defn- create-reaction [conn ctx org-uuid board-uuid topic-slug entry-uuid reaction-unicode reaction-count]
  (let [interaction-map {:org-uuid org-uuid
                         :board-uuid board-uuid
                         :topic-slug topic-slug
                         :entry-uuid entry-uuid
                         :reaction reaction-unicode}
        author (:user ctx)]
    (common/create-interaction conn {:new-interaction (interact-res/->reaction interaction-map author)} reaction-count)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for operations on a reaction
(defresource reaction [conn org-uuid board-uuid topic-slug entry-uuid reaction-unicode]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :put :delete]

  ;; Media type client accepts
  :available-media-types [interact-rep/reaction-media-type]
  :handle-not-acceptable (api-common/only-accept 406 interact-rep/reaction-media-type)

  ;; Authorization
  ;; TODO
  :allowed? true

  ;; Existentialism
  :put-to-existing? true
  :exists? (fn [ctx] (if (common/entry-exists? conn org-uuid board-uuid topic-slug entry-uuid)
                        (let [reactions (interact-res/get-reactions-by-entry conn entry-uuid reaction-unicode)]
                          {:existing-reactions reactions
                           :existing-reaction (reaction-for-user reactions (-> ctx :user :user-id))})
                        false))

  ;; Actions
  :malformed? false
  :put! (fn [ctx] (if (:existing-reaction ctx)
                    true
                    (create-reaction conn ctx
                                          org-uuid
                                          board-uuid
                                          topic-slug
                                          entry-uuid
                                          reaction-unicode
                                          (-> ctx :existing-reactions count inc))))
  :delete! (fn [ctx] (let [existing-reaction (:existing-reaction ctx)]
                        (when existing-reaction
                          (interact-res/delete-interaction! conn (:uuid existing-reaction))
                          (watcher/notify-watcher :interaction-reaction/delete
                                                  existing-reaction
                                                  (-> ctx :existing-reactions count dec)))))

  ;; Responses
  :respond-with-entity? true
  :handle-created (fn [ctx] (if-let* [reaction (or (:created-interaction ctx) (:existing-reaction ctx))
                                      existing-reactions (:existing-reactions ctx)
                                      reactions (if (:created-interaction ctx)
                                                  (conj existing-reactions reaction)
                                                  existing-reactions)]
                              (interact-rep/render-reaction org-uuid board-uuid topic-slug entry-uuid reaction-unicode
                                reactions true)
                              (api-common/missing-response)))
  :handle-ok (fn [ctx] (if (:existing-reaction ctx)
                          (interact-rep/render-reaction org-uuid board-uuid topic-slug entry-uuid reaction-unicode
                            (interact-res/get-reactions-by-entry conn entry-uuid reaction-unicode) false)
                          (api-common/missing-response))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; Reaction create/delete
      (ANY "/orgs/:org-uuid/boards/:board-uuid/topics/:topic-slug/entries/:entry-uuid/reactions/:reaction-unicode/on"
        [org-uuid board-uuid topic-slug entry-uuid reaction-unicode]
        (pool/with-pool [conn db-pool] (reaction conn org-uuid board-uuid topic-slug entry-uuid reaction-unicode))))))