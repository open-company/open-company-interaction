(ns oc.interaction.api.common
  "Liberator API for comment resources."
  (:require [clojure.core.async :refer (>!!)]
            [defun.core :refer (defun-)]
            [if-let.core :refer (if-let* when-let*)]
            [taoensso.timbre :as timbre]
            [oc.lib.db.common :as db-common]
            [oc.interaction.resources.interaction :as interact-res]
            [oc.interaction.representations.interaction :as interact-rep]
            [oc.interaction.async.watcher :as watcher]
            [oc.interaction.async.slack-mirror :as mirror]))

;; TODO hard coding of Slack mirror, replace once we have config of Slack mirror
(def slack-mirror {:slack-org-id "T06SBMH60" :channel-name "bot-testing" :channel-id "C10A1P4H2"})

;; ----- Utility Functions -----

(defn- slack-user
  "Do we have a user ID and token for the mirroring Slack org?"
  [user]
  (let [slack-org-id (keyword (:slack-org-id slack-mirror))]
    (-> user :slack-users slack-org-id)))

(defn- slack-bot
  "Do we have a Slack bot for the mirroring Slack org?"
  [user]
  (let [slack-org-id (:slack-org-id slack-mirror)
        slack-bots (-> user :slack-bots vals concat flatten)]
    (some #(if (= (:slack-org-id %) slack-org-id) % false) slack-bots)))

;; ----- Actions -----

(defn notify-watcher
  "Given an event, an interaction and an optional reeaction count, notify the watcher with core.async."
  [event interaction reaction-count]
  (timbre/info "Sending:" event "to the watcher for:" (:uuid interaction))
  (let [initial-payload {:topic (:topic-slug interaction)
                         :entry-uuid (:entry-uuid interaction)
                         :interaction (interact-rep/interaction-representation interaction :none)}
        payload (if reaction-count (assoc initial-payload :count reaction-count) initial-payload)]
    (>!! watcher/watcher-chan {:send true
                               :watch-id (:board-uuid interaction)
                               :event event
                               :payload payload})))

(defn- echo-comment
  "Given a decoded JWToken and a comment, mirror it to Slack as the user."
  [user entry interaction]
  (>!! mirror/echo-chan {:slack-user (slack-user user)
                         :comment interaction
                         :slack-channel (assoc slack-mirror :thread (-> entry :slack-thread :thread))}))

(defn- proxy-comment
  "Given a decoded JWToken and a comment, mirror it to Slack on behalf of the user."
  [user entry interaction]
  (>!! mirror/proxy-chan {:slack-bot (slack-bot user)
                          :comment interaction
                          :slack-channel (assoc slack-mirror :thread (-> entry :slack-thread :thread))
                          :author user}))

(defun- notify-mirror
  "Given a decoded JWToken and a comment, mirror it to Slack if we can."
  
  ;; no mirror Slack channel
  ;; TODO this case once we have config of Slack mirror

  ;; we can mirror it to Slack as the user
  ([user :guard slack-user entry interaction]
  (timbre/info "Using Slack user to mirror comment:" (:uuid interaction) "of entry:" (:uuid entry))
  (echo-comment user entry interaction))

  ;; we can mirror it to Slack by proxy with the bot
  ([user :guard slack-bot entry interaction]
  (timbre/info "Using Slack bot to mirror comment:" (:uuid interaction)  "of entry:" (:uuid entry))
  (proxy-comment user entry interaction))

  ;; no slack user or bot
  ([_user _entry interaction] (timbre/debug "Skipping Slack mirroring of comment:" (:uuid interaction))))

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
      (notify-watcher (if comment? :interaction-comment/add :interaction-reaction/add) interact-result reaction-count)
      ;; Send the a comment to the mirror for mirroring to Slack
      (when comment? (notify-mirror (:user ctx) (:existing-entry ctx) interact-result))
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
    entry))