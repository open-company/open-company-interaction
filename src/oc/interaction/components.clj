(ns oc.interaction.components
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [org.httpkit.server :as httpkit]
            [oc.lib.db.pool :as pool]
            [oc.lib.async.watcher :as watcher]
            [oc.lib.sqs :as sqs]
            [oc.interaction.async.notification :as notification]
            [oc.interaction.config :as c]
            [oc.interaction.api.websockets :as websockets-api]
            [oc.interaction.async.slack-mirror :as slack-mirror]
            [oc.interaction.async.usage :as usage]
            [oc.interaction.async.slack-router :as slack-router]))

(defrecord HttpKit [options handler]
  component/Lifecycle
  (start [component]
    (timbre/info "[http] starting...")
    (let [handler (get-in component [:handler :handler] handler)
          server  (httpkit/run-server handler options)]
      (websockets-api/start)
      (timbre/info "[http] started")
      (assoc component :http-kit server)))
  (stop [{:keys [http-kit] :as component}]
    (if http-kit
      (do
        (timbre/info "[http] stopping...")
        (http-kit)
        (websockets-api/stop)
        (timbre/info "[http] stopped")
        (dissoc component :http-kit))
      component)))

(defrecord RethinkPool [size regenerate-interval]
  component/Lifecycle

  (start [component]
    (timbre/info "[rehinkdb-pool] starting...")
    (let [pool (pool/fixed-pool (partial pool/init-conn c/db-options) pool/close-conn
                                {:size size :regenerate-interval regenerate-interval})]
      (timbre/info "[rehinkdb-pool] started")
      (assoc component :pool pool)))

  (stop [{:keys [pool] :as component}]
    (if pool
      (do
        (timbre/info "[rethinkdb-pool] stopping...")
        (pool/shutdown-pool! pool)
        (timbre/info "[rethinkdb-pool] stopped")
        (dissoc component :pool))
      component)))

(defrecord SlackMirror [slack-mirror]
  component/Lifecycle

  (start [component]
    (timbre/info "[slack-mirror] starting...")
    (slack-mirror/start component)
    (timbre/info "[slack-mirror] started")
    (assoc component :slack-mirror true))

  (stop [{:keys [slack-mirror] :as component}]
    (if slack-mirror
      (do
        (timbre/info "[slack-mirror] stopping...")
        (slack-mirror/stop)
        (timbre/info "[slack-mirror] stopped")
        (dissoc component :slack-mirror))
      component)))

(defrecord UsageReply [usage-reply]
  component/Lifecycle

  (start [component]
    (timbre/info "[usage-reply] starting...")
    (usage/start)
    (timbre/info "[usage-reply] started")
    (assoc component :usage-reply true))

  (stop [{:keys [usage-reply] :as component}]
    (if usage-reply
      (do
        (timbre/info "[usage-reply] stopping...")
        (usage/stop)
        (timbre/info "[usage-reply] stopped")
        (dissoc component :usage-reply))
      component)))

(defrecord SlackRouter [slack-router-fn]
  component/Lifecycle

  (start [component]
    (timbre/info "[slack-router] starting...")
    (slack-router/start)
    (timbre/info "[slack-router] started")
    (assoc component :slack-router true))

  (stop [{:keys [slack-router] :as component}]
    (if slack-router
      (do
        (timbre/info "[slack-router] stopping...")
        (slack-router/stop)
        (timbre/info "[slack-router] stopped")
        (dissoc component :slack-router))
      component)))

(defrecord AsyncConsumers []
  component/Lifecycle

  (start [component]
    (timbre/info "[async-consumers] starting")
    (notification/start) ; core.async channel consumer for notification events
    (timbre/info "[async-consumers] started")
    (assoc component :async-consumers true))

  (stop [{:keys [async-consumers] :as component}]
    (if async-consumers
      (do
        (timbre/info "[async-consumers] stopping")
        (notification/stop) ; core.async channel consumer for notification events
        (timbre/info "[async-consumers] stopped")
        (dissoc component :async-consumers))
    component)))

(defrecord Handler [handler-fn]
  component/Lifecycle

  (start [component]
    (timbre/info "[handler] starting...")
    (watcher/start) ; core.async channel consumer for watched items (boards watched by websockets) events
    (timbre/info "[handler] started")
    (assoc component :handler (handler-fn component)))

  (stop [component]
    (timbre/info "[handler] stopping...")
    (watcher/stop) ; core.async channel consumer for watched items (boards watched by websockets) events
    (timbre/info "[handler] stopped")
    (dissoc component :handler)))

(defn interaction-system [{:keys [port handler-fn sqs-creds sqs-queue slack-sqs-msg-handler] :as opts}]
  (component/system-map
    :db-pool (map->RethinkPool {:size c/db-pool-size :regenerate-interval 5})
    :async-consumers (component/using
                        (map->AsyncConsumers {})
                        [])
    :slack-router (component/using
                   (map->SlackRouter {:slack-router-fn slack-sqs-msg-handler})
                   [])
    :sqs (sqs/sqs-listener sqs-creds sqs-queue slack-sqs-msg-handler)
    :usage-reply (component/using
                    (map->UsageReply {})
                    [])
    :slack-mirror (component/using
                    (map->SlackMirror {})
                    [:db-pool])
    :handler (component/using
                (map->Handler {:handler-fn handler-fn})
                [:db-pool])
    :server  (component/using
                (map->HttpKit {:options {:port port}})
                [:handler])))

;; ----- REPL usage -----

(comment

  ;; To use the Interaction Service from the REPL
  (require '[com.stuartsierra.component :as component])
  (require '[oc.interaction.config :as config])
  (require '[oc.interaction.components :as components] :reload)
  (require '[oc.interaction.app :as app] :reload)

  (def interact-service (components/interaction-system {:handler-fn app/app :port config/interaction-server-port}))
    
  (def instance (component/start interact-service))

  ;; if you need to change something, just stop the service

  (component/stop instance)

  ;; reload whatever namespaces you need to and start the service again

  (def instance (component/start interact-service))

  )