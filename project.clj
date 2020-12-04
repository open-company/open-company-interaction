(defproject open-company-interaction "0.1.0-SNAPSHOT"
  :description "OpenCompany Interaction Service"
  :url "https://github.com/open-company/open-company-interaction"
  :license {
    :name "GNU Affero General Public License Version 3"
    :url "https://www.gnu.org/licenses/agpl-3.0.en.html"
  }

  :min-lein-version "2.9.1"

  ;; JVM memory
  :jvm-opts ^:replace ["-Xms512m" "-Xmx3072m" "-server"]

  ;; All profile dependencies
  :dependencies [
    [org.clojure/clojure "1.10.2-alpha3"] ; Lisp on the JVM http://clojure.org/documentation
    [org.clojure/core.cache "1.0.207"] ; Clojure in-memory caching https://github.com/clojure/core.cache
    [org.clojure/tools.cli "1.0.194"] ; Command-line parsing https://github.com/clojure/tools.cli
    [ring/ring-devel "2.0.0-alpha-1"] ; Web application library https://github.com/ring-clojure/ring
    [ring/ring-core "2.0.0-alpha1"] ; Web application library https://github.com/ring-clojure/ring
    [ring/ring-json "0.5.0" :exclusions [cheshire]] ; JSON request/response https://github.com/ring-clojure/ring-json
    [jumblerg/ring.middleware.cors "1.0.1"] ; CORS library https://github.com/jumblerg/ring.middleware.cors
    [ring-logger-timbre "0.7.6" :exclusions [com.taoensso/encore]] ; Ring logging https://github.com/nberger/ring-logger-timbre
    [compojure "1.6.2"] ; Web routing https://github.com/weavejester/compojure
    [clj-http "3.10.3"] ; HTTP client https://github.com/dakrone/clj-http
    [clj-soup/clojure-soup "0.1.3"] ; Clojure wrapper for jsoup HTML parser https://github.com/mfornos/clojure-soup

    ;; Library for OC projects https://github.com/open-company/open-company-lib
    ;; ************************************************************************
    ;; ****************** NB: don't go under 0.17.29-alpha60 ******************
    ;; ***************** (JWT schema changes, more info here: *****************
    ;; ******* https://github.com/open-company/open-company-lib/pull/82) ******
    ;; ************************************************************************
    [open-company/lib "0.17.29-alpha63" :exclusions [riddley org.jsoup/jsoup commons-codec clj-http org.apache.httpcomponents/httpclient org.clojure/tools.logging]]
    ;; ************************************************************************
    ;; In addition to common functions, brings in the following common dependencies used by this project:
    ;; httpkit - Web server http://http-kit.org/
    ;; core.async - Async programming and communication https://github.com/clojure/core.async
    ;; defun - Erlang-esque pattern matching for Clojure functions https://github.com/killme2008/defun
    ;; if-let - More than one binding for if/when macros https://github.com/LockedOn/if-let
    ;; Component - Component Lifecycle https://github.com/stuartsierra/component
    ;; Liberator - WebMachine (REST API server) port to Clojure https://github.com/clojure-liberator/liberator
    ;; RethinkDB - RethinkDB client for Clojure https://github.com/apa512/clj-rethinkdb
    ;; Schema - Data validation https://github.com/Prismatic/schema
    ;; Timbre - Pure Clojure/Script logging library https://github.com/ptaoussanis/timbre
    ;; Amazonica - A comprehensive Clojure client for the AWS API https://github.com/mcohen01/amazonica
    ;; Raven - Interface to Sentry error reporting https://github.com/sethtrain/raven-clj
    ;; Cheshire - JSON encoding / decoding https://github.com/dakrone/cheshire
    ;; clj-jwt - A Clojure library for JSON Web Token(JWT) https://github.com/liquidz/clj-jwt
    ;; clj-time - Date and time lib https://github.com/clj-time/clj-time
    ;; Environ - Get environment settings from different sources https://github.com/weavejester/environ
    ;; Sente - WebSocket server https://github.com/ptaoussanis/sente
  ]

  ;; All profile plugins
  :plugins [
    [lein-ring "0.12.5"] ; Common ring tasks https://github.com/weavejester/lein-ring
    [lein-environ "1.2.0"] ; Get environment settings from different sources https://github.com/weavejester/environ
  ]

  :profiles {

    ;; QA environment and dependencies
    :qa {
      :env {
        :db-name "open_company_storage_qa"
        :liberator-trace "false"
        :hot-reload "false"
        :oc-ws-ensure-origin "false" ; local
        :open-company-auth-passphrase "this_is_a_qa_secret" ; JWT secret
      }
      :dependencies [
        [midje "1.9.9"] ; Example-based testing https://github.com/marick/Midje
        [ring-mock "0.1.5"] ; Test Ring requests https://github.com/weavejester/ring-mock
      ]
      :plugins [
        [lein-midje "3.2.2"] ; Example-based testing https://github.com/marick/lein-midje
        [jonase/eastwood "0.3.11"] ; Linter https://github.com/jonase/eastwood
        ;; NB: Skip Kibit 0.1.7 as it has a regression: https://github.com/jonase/kibit/issues/231
        [lein-kibit "0.1.8"] ; Static code search for non-idiomatic code https://github.com/jonase/kibit
      ]
    }

    ;; Dev environment and dependencies
    :dev [:qa {
      :env ^:replace {
        :db-name "open_company_storage_dev"
        :liberator-trace "true" ; liberator debug data in HTTP response headers
        :hot-reload "true" ; reload code when changed on the file system
        :oc-ws-ensure-origin "true" ; local
        :open-company-auth-passphrase "this_is_a_dev_secret" ; JWT secret
        :aws-access-key-id "CHANGE-ME"
        :aws-secret-access-key "CHANGE-ME"
        :aws-sns-interaction-topic-arn "" ; SNS topic to publish notifications (optional)        
        :log-level "debug"
      }
      :plugins [
        [lein-bikeshed "0.5.2"] ; Check for code smells https://github.com/dakrone/lein-bikeshed
        [lein-checkall "0.1.1"] ; Runs bikeshed, kibit and eastwood https://github.com/itang/lein-checkall
        [lein-pprint "1.3.2"] ; pretty-print the lein project map https://github.com/technomancy/leiningen/tree/master/lein-pprint
        [lein-ancient "0.6.15"] ; Check for outdated dependencies https://github.com/xsc/lein-ancient
        [lein-spell "0.1.0"] ; Catch spelling mistakes in docs and docstrings https://github.com/cldwalker/lein-spell
        [lein-deps-tree "0.1.2"] ; Print a tree of project dependencies https://github.com/the-kenny/lein-deps-tree
        [venantius/yagni "0.1.7"] ; Dead code finder https://github.com/venantius/yagni
      ]  
    }]
    :repl-config [:dev {
      :dependencies [
        [org.clojure/tools.nrepl "0.2.13"] ; Network REPL https://github.com/clojure/tools.nrepl
        [aprint "0.1.3"] ; Pretty printing in the REPL (aprint ...) https://github.com/razum2um/aprint
      ]
      ;; REPL injections
      :injections [
        (require '[aprint.core :refer (aprint ap)]
                 '[clojure.stacktrace :refer (print-stack-trace)]
                 '[clj-time.core :as t]
                 '[clj-time.format :as f]
                 '[clojure.string :as s]
                 '[rethinkdb.query :as r]
                 '[cheshire.core :as json]
                 '[ring.mock.request :refer (request body content-type header)]
                 '[schema.core :as schema]
                 '[oc.lib.schema :as lib-schema]
                 '[oc.lib.jwt :as jwt]
                 '[oc.lib.db.common :as db-common]
                 '[oc.interaction.app :refer (app)]
                 '[oc.interaction.config :as config]
                 '[oc.interaction.resources.interaction :as interaction]
                 '[oc.interaction.representations.interaction :as interact-rep]
                 )
      ]
    }]

    ;; Production environment
    :prod {
      :env {
        :db-name "open_company_storage"
        :env "production"
        :liberator-trace "false"
        :hot-reload "false"
      }
    }
  }

  :repl-options {
    :welcome (println (str "\n" (slurp (clojure.java.io/resource "ascii_art.txt")) "\n"
                      "OpenCompany Interaction REPL\n"
                      "\nReady to do your bidding... I suggest (go) or (go <port>) or (go-db) as your first command.\n"))
    :init-ns dev
  }

  :aliases {
    "build" ["do" "clean," "deps," "compile"] ; clean and build code
    "create-migration" ["run" "-m" "oc.interaction.db.migrations" "create"] ; create a data migration
    "migrate-db" ["run" "-m" "oc.interaction.db.migrations" "migrate"] ; run pending data migrations
    "start*" ["do" "migrate-db," "run"] ; start the service
    "start" ["with-profile" "dev" "do" "start*"] ; start a development server
    "start!" ["with-profile" "prod" "do" "start*"] ; start a server in production
    "autotest" ["with-profile" "qa" "do" "migrate-db," "midje" ":autotest"] ; watch for code changes and run affected tests
    "test!" ["with-profile" "qa" "do" "clean," "build," "migrate-db," "midje"] ; build, init the DB and run all tests
    "repl" ["with-profile" "+repl-config" "repl"]
    "spell!" ["spell" "-n"] ; check spelling in docs and docstrings
    "bikeshed!" ["bikeshed" "-v" "-m" "120"] ; code check with max line length warning of 120 characters
    "ancient" ["ancient" ":all" ":allow-qualified"] ; check for out of date dependencies
  }

  ;; ----- Code check configuration -----

  :eastwood {
    ;; Disable some linters that are enabled by default:
    ;; constant-test - just seems mostly ill-advised, logical constants are useful in something like a `->cond`
    ;; deprecations - the useful `either` from Prismatic schema is deprecated, we'll eventually switch to clojure.spec
    ;; wrong-arity - Eastwood can't decipder the arity of some Amazonica SQS fns
    ;; implicit-dependencies - uhh, just seems dumb
    :exclude-linters [:constant-test :deprecations :wrong-arity :implicit-dependencies]

    ;; Enable some linters that are disabled by default
    :add-linters [:unused-namespaces :unused-private-vars] ; :unused-locals]

    ;; Exclude testing namespaces
    :tests-paths ["test"]
    :exclude-namespaces [:test-paths]
  }

  :zprint {:old? false}
  
  ;; ----- API -----

  :ring {
    :handler oc.interaction.app/app
    :reload-paths ["src"] ; work around issue https://github.com/weavejester/lein-ring/issues/68
  }

  :main oc.interaction.app
)
