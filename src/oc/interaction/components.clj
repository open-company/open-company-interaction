(ns oc.interaction.components
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [org.httpkit.server :as httpkit]
            [oc.lib.db.pool :as pool]
            [oc.lib.async.watcher :as watcher]
            [oc.interaction.config :as c]
            [oc.interaction.api.websockets :as websockets-api]
            [oc.interaction.async.slack-mirror :as slack-mirror]))

(defrecord HttpKit [options handler]
  component/Lifecycle
  (start [component]
    (let [handler (get-in component [:handler :handler] handler)
          server  (httpkit/run-server handler options)]
      (websockets-api/start)
      (assoc component :http-kit server)))
  (stop [{:keys [http-kit] :as component}]
    (if http-kit
      (do
        (http-kit)
        (websockets-api/stop)
        (dissoc component :http-kit))
      component)))

(defrecord RethinkPool [size regenerate-interval]
  component/Lifecycle
  (start [component]
    (timbre/info "[rehinkdb-pool] starting")
    (let [pool (pool/fixed-pool (partial pool/init-conn c/db-options) pool/close-conn
                                {:size size :regenerate-interval regenerate-interval})]
      (timbre/info "[rehinkdb-pool] started")
      (assoc component :pool pool)))
  (stop [{:keys [pool] :as component}]
    (if pool
      (do
        (pool/shutdown-pool! pool)
        (dissoc component :pool))
      component)))

(defrecord Handler [handler-fn]
  component/Lifecycle
  (start [component]
    (timbre/info "[handler] starting")
    (watcher/start) ; core.async channel consumer for watched items (boards watched by websockets) events
    (assoc component :handler (handler-fn component)))
  (stop [component]
    (watcher/stop) ; core.async channel consumer for watched items (boards watched by websockets) events
    (dissoc component :handler)))

(defrecord SlackMirror [slack-mirror]
  component/Lifecycle
  (start [component]
    (timbre/info "[slack-mirror] starting")
    (slack-mirror/start component)
    (assoc component :slack-mirror true))
  (stop [{:keys [slack-mirror] :as component}]
    (if slack-mirror
      (do
        (slack-mirror/stop)
        (dissoc component :slack-mirror))
      component)))

(defn interaction-system [{:keys [port handler-fn] :as opts}]
  (component/system-map
    :db-pool (map->RethinkPool {:size c/db-pool-size :regenerate-interval 5})
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