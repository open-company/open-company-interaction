(ns oc.interaction.api.common
  "Liberator API for comment resources."
  (:require [if-let.core :refer (if-let* when-let*)]
            [taoensso.timbre :as timbre]
            [oc.lib.html :as lib-html]
            [oc.lib.db.common :as db-common]
            [oc.interaction.representations.interaction :as rep]
            [oc.interaction.resources.interaction :as interact-res]
            [oc.interaction.async.watcher :as watcher]))

;; ----- DB Persistence -----

(def board-table-name "boards")

;; ----- Transformations -----

(defn transform-comment
  [comment-data]
  (update comment-data :body lib-html/sanitize-html))

;; ----- Actions -----

(defn create-interaction 
  "Create an interaction in the DB and publish it to the watcher (WebSockets)."

  ;; For comments, there is no count
  ([conn ctx] (create-interaction conn ctx false))
  
  ([conn ctx reaction-count]
  (timbre/info "Creating interaction.")
  (if-let* [new-interaction (:new-interaction ctx)
            interact-result (if (:body new-interaction)
                              (interact-res/create-comment! conn (transform-comment new-interaction)) ; Create the comment
                              (interact-res/create-reaction! conn new-interaction))] ; Create the reaction
    ;; Interaction creation succeeded
    (let [uuid (:uuid interact-result)
          comment? (:body interact-result)
          interaction (if comment?
                        (rep/render-ws-comment interact-result (:user ctx))
                        interact-result)]
      (timbre/info "Created interaction:" uuid)
      ;; Send the interaction to the watcher for event handling
      (watcher/notify-watcher (if comment? :interaction-comment/add
                                           :interaction-reaction/add)
                              interaction
                              reaction-count
                              ;; Pass the client-id creating the interaction to avoid
                              ;; resending the ws message to it
                              (:new-interaction-client-id ctx))
      ;; Return the new interaction for the request context
      {:created-interaction interact-result})
    
    (do (timbre/error "Failed creating interaction.") false))))

;; ----- Validations -----

(defn resource-exists? [conn org-uuid board-uuid resource-uuid]
  (when-let* [org (first (db-common/read-resources conn "orgs" "uuid" org-uuid))
              board (db-common/read-resource conn "boards" board-uuid)
              board-org? (= (:org-uuid board) org-uuid)
              resource (or (db-common/read-resource conn "interactions" resource-uuid)
                           (db-common/read-resource conn "entries" resource-uuid))]
    (merge resource {:org-slug (:slug org) :board-slug (:slug board)})))

;; ----- Get WS client id ----

(defn get-client-id-from-context [ctx]
  (get-in ctx [:request :headers "oc-interaction-client-id"]))
