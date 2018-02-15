(ns oc.interaction.async.usage
  "
  Send usage instructions to users DM'ing the bot. Required by Slack.
  "
  (:require [clojure.core.async :as async :refer [<! >!!]]
            [clojure.walk :refer (keywordize-keys)]
            [taoensso.timbre :as timbre]))

;; ----- core.async -----

(defonce usage-chan (async/chan 10000)) ; buffered channel

(defonce usage-go (atom true))

;; ----- Event handling -----

;; You talkin' to me? You talkin' to me??\n
;; Well... you shouldn't be, I'm just a Carrot, I've got no ears!\n
;; Ha! :laugh: I kid of course! But for the most part, I do like to stay deep in the soil, out of your way.\n
;; > *Here's what I do:*\n
;; - I can talk on behalf of your teammates to ensure their comments from Carrot make it into Slack
;; - I can also talk on behalf of your teammates so the posts they share from Carrot make it to Slack
;; - And, I can send you a daily or weekly digest of what's new from your team in Carrot
;; [Carrot Digest Settings]

(defn- handle-message
  "
  "
  [channel-id body]
  (timbre/info "Usage message from the bot!"))
  
;; ----- Event loops (incoming from Slack) -----

(defn- usage-loop 
  "core.async consumer to handle incoming Slack messages."
  []
  (reset! usage-go true)
  (timbre/info "Starting usage reply...")
  (async/go (while @usage-go
    (timbre/debug "Slack usage reply waiting...")
    (let [message (<! usage-chan)]
      (timbre/debug "Processing message on usage channel...")
      (if (:stop message)
        (do (reset! usage-go false) (timbre/info "Slack usage reply stopped."))
        (async/thread
          (timbre/info "Bot time!")
          (try
            (let [body (keywordize-keys (:body message))
                  event (:event body)
                  channel-id (:channel event)]
              (handle-message channel-id body))
            (catch Exception e
              (timbre/error e)))))))))

;; ----- Component start/stop -----

(defn start
  "Start the core.async channel consumers for sending bot usage instructions to Slack."
  []
  (usage-loop))

(defn stop
  "Stop the core.async channel consumers for sending bot usage instructions to Slack."
  []
  (when @usage-go
    (timbre/info "Stopping Slack usage reply...")
    (>!! usage-chan {:stop true})))