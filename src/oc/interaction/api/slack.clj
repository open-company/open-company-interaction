(ns oc.interaction.api.slack
  "WebSocket server handler."
  (:require [clojure.core.async :as async :refer (>!!)]
            [compojure.core :as compojure :refer (defroutes POST)]
            [taoensso.timbre :as timbre]
            [oc.lib.slack :as lib-slack]
            [oc.interaction.config :as config]
            [oc.interaction.async.slack-mirror :as mirror]))

(defn- slack-event
  "
  Handle a message event from Slack. Ignore events that aren't threaded, or that are from us.

  Idea here is to do very minimal processing and get a 200 back to Slack as fast as possible as this is a 'firehose'
  of requests. So minimal logging and minimal handling of the request.

  Message events look like:

  {'token' 'IxT9ZaxvjqRdKxYtWdTw21Xv',
   'team_id' 'T06SBMH60', 
   'api_app_id' 'A0CHN2UDB',
   'event' {'type' 'message',
            'user' 'U06SBTXJR',
            'text' 'Call me back here',
            'thread_ts' '1494262410.072574', 
            'parent_user_id' 'U06SBTXJR', 
            'ts' '1494281750.011785', 
            'channel' 'C10A1P4H2', 
            'event_ts' '1494281750.011785'}, 
    'type' 'event_callback', 
    'authed_users' ['U06SBTXJR'], 
    'event_id' 'Ev5B8YSYQ6', 
    'event_time' 1494281750}
  "
  [request]
  (let [body (:body request)
        type (get body "type")
        token (get body "token")
        thread (get-in body ["event" "thread_ts"])]

    ;; Token check    
    (if-not (= token config/slack-verification-token)
      
      ;; Eghads! It might be a Slack impersonator!
      (do 
        (timbre/warn "Slack verification token mismatch, request provided:" token)
        {:status 403})

      ;; Token check is A-OK
      (cond 
        ;; This is a check of the web hook by Slack, echo back the challeng
        (= type "url_verification") (do 
          (timbre/info "Slack challenge:" body) 
          {:status 200 :body (get body "challenge")}) ; Slack, we're good
        
        ;; If there's no thread, we aren't interested in the message
        (nil? thread) {:status 200}

        :else 
        ;; if there's a marker starting the message than this message came form us so can be ignored 
        (let [text (get-in body ["event" "text"])]
          (when-not (and text (re-find (re-pattern (str "^" lib-slack/marker-char)) text))
            ;; Message from Slack, not us, with a thread and w/o our marker, might need mirrored as a comment
            (>!! mirror/incoming-chan {:body body}))
          {:status 200})))))

;; ----- Request Routing -----

(defn routes [sys]
  (compojure/routes
    (POST "/slack-event" [:as request] (slack-event request))))