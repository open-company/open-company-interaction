(ns oc.interaction.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]))

(defn- bool
  "Handle the fact that we may have true/false strings, when we want booleans."
  [val]
  (boolean (Boolean/valueOf val)))

;; ----- System -----

(defonce processors (.availableProcessors (Runtime/getRuntime)))
(defonce core-async-limit (+ 42 (* 2 processors)))

(defonce prod? (= "production" (env :env)))
(defonce intro? (not prod?))

;; ----- Logging (see https://github.com/ptaoussanis/timbre) -----

(defonce log-level (if-let [ll (env :log-level)] (keyword ll) :info))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-interaction) false))
(defonce sentry-release (or (env :release) ""))
(defonce sentry-deploy (or (env :deploy) ""))
(defonce sentry-debug  (boolean (or (bool (env :sentry-debug)) (#{:debug :trace} log-level))))
(defonce sentry-env (or (env :environment) "local"))
(defonce sentry-config {:dsn dsn
                        :release sentry-release
                        :deploy sentry-deploy
                        :debug sentry-debug
                        :environment sentry-env})

;; ----- RethinkDB -----

(defonce migrations-dir "./src/oc/interaction/db/migrations")
(defonce migration-template "./src/oc/interaction/assets/migration.template.edn")

(defonce db-host (or (env :db-host) "localhost"))
(defonce db-port (or (env :db-port) 28015))
(defonce db-name (or (env :db-name) "open_company_storage"))
(defonce db-pool-size (or (env :db-pool-size) (- core-async-limit 21))) ; conservative with the core.async limit

(defonce db-map {:host db-host :port db-port :db db-name})
(defonce db-options (flatten (vec db-map))) ; k/v sequence as clj-rethinkdb wants it

;; ----- HTTP server -----

(defonce hot-reload (bool (or (env :hot-reload) false)))
(defonce interaction-server-port (Integer/parseInt (or (env :port) "3002")))
(defonce ensure-origin  (bool (or (env :oc-ws-ensure-origin) true)))

;; ----- Liberator -----

;; see header response, or http://localhost:3000/x-liberator/requests/ for trace results
(defonce liberator-trace (bool (or (env :liberator-trace) false)))
(defonce pretty? (not prod?)) ; JSON response as pretty?

;; ----- URLs -----

(defonce ui-server-url (or (env :ui-server-url) "http://localhost:3559"))
(defonce web-cdn-url (or (env :web-cdn-url) "https://d16anbmiggbhpz.cloudfront.net"))

;; ----- AWS SQS / SNS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sns-interaction-topic-arn (env :aws-sns-interaction-topic-arn))

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))

;; ----- Slack -----

(defonce slack-verification-token (env :open-company-slack-verification-token))