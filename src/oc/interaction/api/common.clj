(ns oc.interaction.api.common
  "Liberator API for comment resources."
  (:require [if-let.core :refer (if-let* when-let*)]
            [taoensso.timbre :as timbre]
            [oc.lib.db.common :as db-common]
            [oc.interaction.config :as config]
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

(defn entry-exists? [conn org-uuid board-uuid topic-slug entry-uuid]
  (when-let* [org (first (db-common/read-resources conn "orgs" "uuid" org-uuid))
              board (db-common/read-resource conn "boards" board-uuid)
              board-org? (= (:org-uuid board) org-uuid)
              topic-board? ((set (:topics board)) topic-slug)
              entry (db-common/read-resource conn "entries" entry-uuid)
              entry-topic? (= (:topic-slug entry) topic-slug)]
    true))