(ns oc.interaction.api.common
  "Liberator API for comment resources."
  (:require [clojure.core.async :refer (>!!)]
            [if-let.core :refer (if-let* when-let*)]
            [taoensso.timbre :as timbre]
            [oc.lib.db.common :as db-common]
            [oc.interaction.resources.interaction :as interact-res]
            [oc.interaction.representations.interaction :as interact-rep]
            [oc.interaction.lib.watcher :as watcher]))

;; ----- Actions -----

(defn create-interaction 
  "Create an interaction in the DB and publish it to the watcher."

  ;; For comments, there is no count
  ([conn ctx] (create-interaction conn ctx 0))
  
  ([conn ctx reaction-count]
  (timbre/info "Creating interaction.")
  (if-let* [new-interaction (:new-interaction ctx)
            interact-result (if (:body new-interaction)
                              (interact-res/create-comment! conn new-interaction) ; Create the comment
                              (interact-res/create-reaction! conn new-interaction))] ; Create the reaction
    ;; Interaction creation succeeded
    (let [uuid (:uuid interact-result)
          comment? (:body interact-result)]
      (timbre/info "Created interaction:" uuid)
      ;; Send the interaction to the watcher for event handling
      (timbre/info "Sending interaction to watcher:" uuid)
      (>!! watcher/watcher-chan {:send true
                                 :watch-id (:board-uuid interact-result)
                                 :event (if comment? :interaction-comment/add :interaction-reaction/add)
                                 :payload {:topic (:topic-slug interact-result)
                                           :entry-uuid (:entry-uuid interact-result)
                                           :count (+ reaction-count 1)
                                           :interaction (interact-rep/interaction-representation interact-result :none)}})
      ;; Return the new interaction for the request context
      {:created-interaction interact-result})
    
    (do (timbre/error "Failed creating interaction.") false))))

;; ----- Validations -----

(defn entry-exists? [conn org-uuid board-uuid topic-slug entry-uuid]
  (when-let* [org (first (db-common/read-resources conn "orgs" "uuid" org-uuid))
              board (db-common/read-resource conn "boards" board-uuid)
              board-org? (= (:org-uuid board) org-uuid)
              topic-board? ((set (:topics board)) topic-slug)
              entry (db-common/read-resource conn "entries" entry-uuid)
              entry-topic? (= (:topic-slug entry) topic-slug)]
    true))