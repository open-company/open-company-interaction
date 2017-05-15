(ns dev
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as ctnrepl]
            [oc.lib.db.pool :as pool]
            [oc.interaction.config :as c]
            [oc.interaction.app :as app]
            [oc.interaction.components :as components]))

(def system nil)
(def conn nil)

(defn init
  ([] (init c/interaction-server-port))
  ([port]
  (alter-var-root #'system (constantly (components/interaction-system {:handler-fn app/app
                                                                   :port port})))))

(defn bind-conn! []
  (alter-var-root #'conn (constantly (pool/claim (get-in system [:db-pool :pool])))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system (fn [s] (when s (component/stop s)))))

(defn go
  ([] (go c/interaction-server-port))
  ([port]
  (init port)
  (start)
  (bind-conn!)))

(defn reset []
  (stop)
  (ctnrepl/refresh :after 'user/go))