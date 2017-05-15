(ns oc.interaction.db.migrations
  "Lein main to migrate RethinkDB data."
  (:require [oc.interaction.config :as c]
            [oc.lib.db.migrations :as m])
  (:gen-class))

(defn -main
  "
  Run create or migrate from lein.

  Usage:

  lein create-migration <name>

  lein migrate-db
  "
  [which & args]
  (cond 
    (= which "migrate") (m/migrate c/db-map c/migrations-dir)
    (= which "create") (apply m/create c/migrations-dir c/migration-template args)
    :else (println "Unknown action: " which)))