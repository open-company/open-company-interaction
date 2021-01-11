(ns oc.interaction.app
  "Namespace for the HTTP application which serves the REST API."
  (:gen-class)
  (:require
    [oc.lib.sentry.core :as sentry]
    [taoensso.timbre :as timbre]
    [ring.logger.timbre :refer (wrap-with-logger)]
    [liberator.dev :refer (wrap-trace)]
    [ring.middleware.keyword-params :refer (wrap-keyword-params)]
    [ring.middleware.params :refer (wrap-params)]
    [ring.middleware.reload :refer (wrap-reload)]
    [ring.middleware.cors :refer (wrap-cors)]
    [compojure.core :as compojure :refer (GET)]
    [com.stuartsierra.component :as component]
    [oc.interaction.components :as components]
    [oc.interaction.config :as c]
    [oc.interaction.api.comments :as comments-api]
    [oc.interaction.api.reactions :as reactions-api]
    [oc.interaction.api.websockets :as websockets-api]
    [oc.lib.middleware.wrap-ensure-origin :refer (wrap-ensure-origin)]))

;; ----- Request Routing -----

(defn routes [sys]
  (compojure/routes
    (GET "/ping" [] {:body "OpenCompany Interaction Service: OK" :status 200}) ; Up-time monitor
    (GET "/---error-test---" [] (/ 1 0))
    (GET "/---500-test---" [] {:body "Testing bad things." :status 500})
    (comments-api/routes sys)
    (reactions-api/routes sys)
    (websockets-api/routes sys)))

;; ----- System Startup -----

(defn echo-config [port]
  (println (str "\n"
    "Running on port: " port "\n"
    "Database: " c/db-name "\n"
    "Database pool: " c/db-pool-size "\n"
    "AWS SNS notification topic ARN: " c/aws-sns-interaction-topic-arn "\n"
    "Hot-reload: " c/hot-reload "\n"
    "Trace: " c/liberator-trace "\n"
    "Log level: " c/log-level "\n"
    "Ensure origin: " c/ensure-origin "\n"
    "Sentry: " c/dsn "\n"
    "  env: " c/sentry-env "\n"
    (when-not (clojure.string/blank? c/sentry-release)
      (str "  release: " c/sentry-release "\n"))
    "\n"
    (when c/intro? "Ready to serve...\n"))))

;; Ring app definition
(defn app [sys]
  (cond-> (routes sys)
    ; important that this is first
    c/dsn             (sentry/wrap c/sentry-config)
    c/prod?           wrap-with-logger
    true              wrap-keyword-params
    true              wrap-params
    c/liberator-trace (wrap-trace :header :ui)
    true              (wrap-cors #".*")
    c/ensure-origin   (wrap-ensure-origin c/ui-server-url)
    c/hot-reload      wrap-reload))

(defn start
  "Start an instance of the Interaction service."
  [port]

  ;; Stuff logged at error level goes to Sentry
  (if c/dsn
    (timbre/merge-config!
      {:level (keyword c/log-level)
       :appenders {:sentry (sentry/sentry-appender c/sentry-config)}})
    (timbre/merge-config! {:level (keyword c/log-level)}))

  ;; Start the system
  (-> {:sentry c/sentry-config
       :handler-fn app
       :port port
       :sqs-creds {:access-key c/aws-access-key-id
                   :secret-key c/aws-secret-access-key}}
    components/interaction-system
    component/start)

  ;; Echo config information
  (println (str "\n" 
    (when c/intro? (str (slurp (clojure.java.io/resource "ascii_art.txt")) "\n"))
    "OpenCompany Interaction Service\n"))
  (echo-config port))

(defn -main []
  (start c/interaction-server-port))