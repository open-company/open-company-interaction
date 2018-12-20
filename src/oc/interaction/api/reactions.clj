(ns oc.interaction.api.reactions
  "Liberator API for reaction resources."
  (:require [if-let.core :refer (if-let*)]
            [compojure.core :as compojure :refer (ANY OPTIONS POST)]
            [liberator.core :refer (defresource by-method)]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.interaction.config :as config]
            [oc.interaction.api.common :as common]
            [oc.interaction.async.notification :as notification]
            [oc.interaction.async.watcher :as watcher]
            [oc.interaction.representations.interaction :as interact-rep]
            [oc.interaction.resources.interaction :as interact-res]))

;; ----- Utility functions -----

(defn- reaction-for-user [reactions user-id]
  (some #(when (= (-> % :author :user-id) user-id) %) reactions))

;; ----- Actions -----

(defn- create-reaction [conn ctx org-uuid board-uuid resource-uuid reaction-unicode reaction-count]
  (let [interaction-map {:org-uuid org-uuid
                         :board-uuid board-uuid
                         :resource-uuid resource-uuid
                         :reaction reaction-unicode}
        author (:user ctx)
        result (common/create-interaction conn {:new-interaction (interact-res/->reaction interaction-map author)}
                                                reaction-count)
        new-reaction (:created-interaction result)]
    (notification/send-trigger! (notification/->trigger conn :add new-reaction
                                                        {:new new-reaction} (:user ctx)))
    result))

(defn- delete-reaction [conn ctx]
  (let [existing-reaction (:existing-reaction ctx)]
    (when existing-reaction
      (interact-res/delete-interaction! conn (:uuid existing-reaction))
      (notification/send-trigger! (notification/->trigger conn :delete existing-reaction
                                                          {:old existing-reaction} (:user ctx)))
      (watcher/notify-watcher :interaction-reaction/delete
                              existing-reaction
                              (-> ctx :existing-reactions count dec)))))

;; ----- Responses -----

(defn- reaction-response
  [ctx org-uuid board-uuid resource-uuid]
  (if-let* [reaction (or (:created-interaction ctx) (:existing-reaction ctx))
                                  existing-reactions (or (:existing-reactions ctx) [])
                                  reactions (if (:created-interaction ctx)
                                              (conj existing-reactions reaction)
                                              existing-reactions)]
                          (interact-rep/render-reaction org-uuid board-uuid resource-uuid (:reaction reaction)
                            reactions true)
                          (api-common/missing-response)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for operations on a reaction
(defresource reaction [conn org-uuid board-uuid resource-uuid reaction-unicode]
  (api-common/open-company-id-token-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :put :delete]

  ;; Media type client accepts
  :available-media-types [interact-rep/reaction-media-type]
  :handle-not-acceptable (api-common/only-accept 406 interact-rep/reaction-media-type)

  ;; Authorization
  ;; TODO
  :allowed? true

  ;; Existentialism
  :put-to-existing? true
  :exists? (fn [ctx] (if (common/resource-exists? conn org-uuid board-uuid resource-uuid)
                        (let [reactions (interact-res/get-reactions-by-resource conn resource-uuid reaction-unicode)]
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
                                          resource-uuid
                                          reaction-unicode
                                          (-> ctx :existing-reactions count inc))))
  :delete! (fn [ctx] (delete-reaction conn ctx))

  ;; Responses
  :respond-with-entity? true
  :handle-created (fn [ctx] (reaction-response ctx org-uuid board-uuid resource-uuid))
  :handle-ok (fn [ctx] (if (:existing-reaction ctx)
                          (interact-rep/render-reaction org-uuid board-uuid resource-uuid reaction-unicode
                            (interact-res/get-reactions-by-resource conn resource-uuid reaction-unicode) false)
                          (api-common/missing-response))))


;; A resource for creating a new reaction
(defresource new-reaction [conn org-uuid board-uuid resource-uuid]
  (api-common/open-company-id-token-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :post]

  ;; Media type client accepts
  :available-media-types [interact-rep/reaction-media-type]
  :handle-not-acceptable (api-common/only-accept 406 interact-rep/reaction-media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :post (fn [ctx] (api-common/known-content-type? ctx "text/plain"))})

  ;; Authorization
  ;; TODO
  :allowed? true

  ;; Validations
  :malformed? false
  :processable? (by-method {
    :options true
    :post (fn [ctx] (if-let* [reaction-unicode (-> ctx :request :body slurp)
                              _string (string? reaction-unicode)
                              ;; TODO need to verify it's just 1 Unicode char, counting code points doesn't
                              ;; work because something like 🇫🇰 is 2, for now just verify it's 4 or less code points
                              _length (<= (.codePointCount reaction-unicode 0 (count reaction-unicode)) 4)]
                          {:reaction-unicode reaction-unicode}
                          [false {:reason "Provide a single unicode character in the request body as a reaction."}]))})

  ;; Existentialism
  :exists? (fn [ctx] (if (common/resource-exists? conn org-uuid board-uuid resource-uuid)
                        (let [reactions (interact-res/get-reactions-by-resource conn resource-uuid (:reaction-unicode ctx))]
                          {:existing-reactions reactions
                           :existing-reaction (reaction-for-user reactions (-> ctx :user :user-id))
                           :existing? true})
                        false))

  ;; Actions
  :post! (fn [ctx] (when (:existing? ctx) ; when the resource does exist
                      (if (:existing-reaction ctx)
                        true ; this reaction already exists
                        (create-reaction conn ctx
                                              org-uuid
                                              board-uuid
                                              resource-uuid
                                              (:reaction-unicode ctx)
                                              (-> ctx :existing-reactions count inc)))))

  ;; Responses
  :respond-with-entity? true
  :new? (fn [ctx] (if (:existing-reaction ctx) false true))
  :handle-created (fn [ctx] (reaction-response ctx org-uuid board-uuid resource-uuid))
  :handle-ok (fn [ctx] (reaction-response ctx org-uuid board-uuid resource-uuid))
  :handle-unprocessable-entity (fn [ctx]
    (api-common/unprocessable-entity-response (:reason ctx))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; Reaction create
      (OPTIONS "/orgs/:org-uuid/boards/:board-uuid/resources/:resource-uuid/reactions"
        [org-uuid board-uuid resource-uuid]
        (pool/with-pool [conn db-pool] (new-reaction conn org-uuid board-uuid resource-uuid)))
      (OPTIONS "/orgs/:org-uuid/boards/:board-uuid/resources/:resource-uuid/reactions/"
        [org-uuid board-uuid resource-uuid]
        (pool/with-pool [conn db-pool] (new-reaction conn org-uuid board-uuid resource-uuid)))
      (POST "/orgs/:org-uuid/boards/:board-uuid/resources/:resource-uuid/reactions"
        [org-uuid board-uuid resource-uuid]
        (pool/with-pool [conn db-pool] (new-reaction conn org-uuid board-uuid resource-uuid)))
      (POST "/orgs/:org-uuid/boards/:board-uuid/resources/:resource-uuid/reactions/"
        [org-uuid board-uuid resource-uuid]
        (pool/with-pool [conn db-pool] (new-reaction conn org-uuid board-uuid resource-uuid)))
      ;; Existing reaction create/delete for the user
      (ANY "/orgs/:org-uuid/boards/:board-uuid/resources/:resource-uuid/reactions/:reaction-unicode/on"
        [org-uuid board-uuid resource-uuid reaction-unicode]
        (pool/with-pool [conn db-pool] (reaction conn org-uuid board-uuid resource-uuid reaction-unicode)))
      (ANY "/orgs/:org-uuid/boards/:board-uuid/resources/:resource-uuid/reactions/:reaction-unicode/on/"
        [org-uuid board-uuid resource-uuid reaction-unicode]
        (pool/with-pool [conn db-pool] (reaction conn org-uuid board-uuid resource-uuid reaction-unicode))))))