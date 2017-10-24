(ns oc.interaction.api.common
  "Liberator API for comment resources."
  (:require [clojure.core.async :refer (>!!)]
            [if-let.core :refer (if-let* when-let*)]
            [taoensso.timbre :as timbre]
            [oc.lib.db.common :as db-common]
            [oc.interaction.resources.interaction :as interact-res]
            [oc.interaction.async.watcher :as watcher]
            [oc.interaction.async.slack-mirror :as mirror]))

;; ----- DB Persistence -----

(def board-table-name "boards")

;; ----- Utility Functions -----

(defn- slack-user
  "Do we have a user ID and token for the mirroring Slack org?"
  [user channel]
  (let [slack-org-id (keyword (:slack-org-id channel))]
    (-> user :slack-users slack-org-id)))

(defn- slack-bot
  "Do we have a Slack bot for the mirroring Slack org?"
  [user channel]
  (let [slack-org-id (:slack-org-id channel)
        slack-bots (-> user :slack-bots vals concat flatten)]
    (some #(if (= (:slack-org-id %) slack-org-id) % false) slack-bots)))

;; ----- Actions -----

(defn- echo-comment
  "Given a decoded JWToken (user), a resource, and a comment (interaction), mirror the comment to Slack as the user."
  [user resource channel interaction]
  (>!! mirror/echo-chan {:slack-bot (slack-bot user channel)
                         :slack-user (slack-user user channel)
                         :comment interaction
                         :resource resource
                         :slack-channel (assoc channel :thread (-> resource :slack-thread :thread))}))

(defn- proxy-comment
  "Given a decoded JWToken (user), a resource, and a comment (interaction), mirror it to Slack on behalf of the user."
  [user resource channel interaction]
  (>!! mirror/proxy-chan {:slack-bot (slack-bot user channel)
                          :comment interaction
                          :resource resource
                          :slack-channel (assoc channel :thread (-> resource :slack-thread :thread))
                          :author user}))

(defn- notify-mirror
  "Given a decoded JWToken (user), a resource, and a comment (interaction), mirror it to Slack if we can."
  
  ;; Get the Slack channel for the resource (if there is one)
  ([conn user resource interaction]
    (if-let* [board (db-common/read-resource conn board-table-name (:board-uuid resource))
              channel (:slack-mirror board)]
      (notify-mirror conn user resource channel interaction)
      (timbre/debug "Skipping Slack mirroring of comment (no Slack mirror):" (:uuid interaction))))

  ([conn user resource channel interaction]
  (cond
    ;; we can mirror it to Slack as the user
    (slack-user user channel)
    (do (timbre/info "Using Slack user to mirror comment:" (:uuid interaction) "of resource:" (:uuid resource))
        (echo-comment user resource channel interaction))

    ;; we can mirror it to Slack by proxy with the bot
    (slack-bot user channel)
    (do
      (timbre/info "Using Slack bot to mirror comment:" (:uuid interaction)  "of resource:" (:uuid resource))
      (proxy-comment user resource channel interaction))

    ;; no slack user or bot
    :else
    (timbre/debug "Skipping Slack mirroring of comment (no user or bot):" (:uuid interaction)))))

(defn create-interaction 
  "Create an interaction in the DB and publish it to the watcher (WebSockets) and Slack mirror."

  ;; For comments, there is no count
  ([conn ctx] (create-interaction conn ctx false))
  
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
      (watcher/notify-watcher (if comment? :interaction-comment/add
                                           :interaction-reaction/add)
                              interact-result
                              reaction-count)
      ;; Send the a comment to the mirror for mirroring to Slack
      (when comment? (notify-mirror conn (:user ctx) (:existing-resource ctx) interact-result))
      ;; Return the new interaction for the request context
      {:created-interaction interact-result})
    
    (do (timbre/error "Failed creating interaction.") false))))

;; ----- Validations -----

(defn resource-exists? [conn org-uuid board-uuid resource-uuid]
  (when-let* [org (first (db-common/read-resources conn "orgs" "uuid" org-uuid))
              board (db-common/read-resource conn "boards" board-uuid)
              board-org? (= (:org-uuid board) org-uuid)
              resource (db-common/read-resource conn "entries" resource-uuid)]
    (merge resource {:org-slug (:slug org) :board-slug (:slug board)})))