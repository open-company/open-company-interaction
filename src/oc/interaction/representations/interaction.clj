(ns oc.interaction.representations.interaction
  "Resource representations for OpenCompany interactions."
  (:require [clojure.string :as string]
            [defun.core :refer (defun defun-)]
            [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.interaction.config :as config]))

(def comment-media-type "application/vnd.open-company.comment.v1+json")
(def comment-collection-media-type "application/vnd.collection+vnd.open-company.comment+json;version=1")

(def reaction-media-type "application/vnd.open-company.reaction.v1+json")

(def representation-props [:uuid :body :reaction :author :reactions :created-at :updated-at])

(defn- map-kv
  "Utility function to do an operation on the value of every key in a map."
  [f coll]
  (reduce-kv (fn [m k v] (assoc m k (f v))) (empty coll) coll))

(defun url 

  ([org-uuid board-uuid resource-uuid]
  (str "/orgs/" org-uuid "/boards/" board-uuid "/resources/" resource-uuid))
  
  ([org-uuid board-uuid resource-uuid reaction-unicode]
  (str (url org-uuid board-uuid resource-uuid) "/reactions/" reaction-unicode "/on"))
  
  ([interaction :guard :interaction-uuid]
  (str (url (dissoc interaction :interaction-uuid))
       "/comments/" (:interaction-uuid interaction)
       "/reactions/" (:uuid interaction)))
  
  ([interaction :guard :reaction]
  (url (:org-uuid interaction) (:board-uuid interaction) (:resource-uuid interaction) (:reaction interaction)))

  ([interaction :guard :comment-react]
    (str (url (dissoc interaction :comment-react)) "/react"))

  ([interaction]
  (str (url (:org-uuid interaction) (:board-uuid interaction) (:resource-uuid interaction))
    "/comments/" (:uuid interaction))))

(defn- update-link [interaction] (hateoas/partial-update-link (url interaction) {:content-type comment-media-type
                                                                       :accept comment-media-type}))

(defn- delete-link [interaction] (hateoas/delete-link (url interaction)))

(defun- reaction-link 
  ([reaction-url] (hateoas/link-map "react" hateoas/POST
                    reaction-url
                    {:content-type "text/plain"
                     :accept reaction-media-type}))
  ([reaction-url true] (hateoas/link-map "react" hateoas/DELETE reaction-url {}))
  ([reaction-url false] (hateoas/link-map "react" hateoas/PUT reaction-url {:accept reaction-media-type})))

(defn- interaction-links
  [interaction access-level]
  (let [links (if (= access-level :author)
                  [(update-link interaction) (delete-link interaction)]
                  ;; if the user didn't author this interaction then we're building this to pass along
                  ;; to some other board watching user, so we know they have reaction access, but don't
                  ;; know if they've used this reaction or not, so we provide both links
                  [(reaction-link (url interaction) true) (reaction-link (url interaction) false)])]
    (assoc interaction :links
     (if (:body interaction)
      (conj links (reaction-link (url (assoc interaction :comment-react true))))
      links))))

(defn- access
  "Return `:author` if the specified interaction was authored by the specified user and `:none` if not."
  [interaction user]
  (if (= (-> interaction :author :user-id) (:user-id user)) :author :none))

(defn- comment-reaction-link
  "Create a reactions URL using the resource URL for the comment"
  [reaction interaction comment-url]
  (let [base-url (take 6 (string/split comment-url #"/"))
        comment-uuid (:uuid interaction)]
    (str (string/join "/" base-url) "/" comment-uuid "/reactions/" reaction "/on")))

(defn- comment-reaction-and-link
  "Given the reaction and comment, return a map representation of the reaction for use in the API."
  [reaction interaction reacted? collection-url]
  (let [reaction-uuid (first reaction)]
    {:reaction reaction-uuid
     :reacted reacted?
     :count (last reaction)
     :links [(reaction-link (comment-reaction-link reaction-uuid interaction collection-url) reacted?)]}))

(defn- comment-reactions
  [interaction user collection-url]
  (if (:body interaction)
    (let [default-reactions (apply hash-map (interleave config/default-comment-reactions (repeat [])))
          grouped-reactions (merge default-reactions
                                   (group-by :reaction (:reactions interaction))) ; reactions grouped by unicode character
          counted-reactions-map (map-kv count grouped-reactions) ; how many for each character?
          counted-reactions (map #(vec [% (get counted-reactions-map %)]) (keys counted-reactions-map))
          reaction-authors (map #(:user-id (:author %)) (:reactions interaction))
          reacted? (boolean (seq (filter #(= % user) (vec reaction-authors))))]
      (assoc interaction :reactions
             (vec (map #(comment-reaction-and-link % interaction reacted? collection-url) counted-reactions))))
      interaction))

(defn interaction-representation
  "Given an interaction, create a representation."
  [interaction access-level]
  (-> interaction
    (interaction-links access-level)
    (select-keys (conj representation-props :links))))

(defn render-ws-comment
  "Given a comment create the data to send over the websocket."
  [comment user]
  (let [collection-url (str (url (:org-uuid comment)
                                 (:board-uuid comment)
                                 (:resource-uuid comment) "/comments"))
        comment-with-link (interaction-links comment
                                             (access comment user))]
    (comment-reactions comment-with-link (str (:user-id user)) collection-url)))

(defn render-interaction
  "Given an interaction, create a JSON representation for the REST API."
  [interaction access-level]
  (json/generate-string
    (interaction-representation interaction access-level)
    {:pretty config/pretty?}))

(defn render-interaction-list
  "
  Given the parts of an interaction URL, a sequence of interactions, and a user, render a list of the interactions
  for the REST API.
  "
  [org-uuid board-uuid resource-uuid interactions user]
  (let [collection-url (str (url org-uuid board-uuid resource-uuid) "/comments")
        links [(hateoas/self-link collection-url {:accept comment-collection-media-type})]
        interactions-with-links (map #(interaction-representation % (access % user)) interactions)
        user-id (str (:user-id user))]
    (json/generate-string
      {:collection {:version hateoas/json-collection-version
                    :href collection-url
                    :links links
                    :items (map #(comment-reactions % user-id collection-url) interactions-with-links)}}
        {:pretty config/pretty?})))

(defn render-reaction
  "
  Given a unicode character, a list of reactions, and an indication if the user reacted or not, render a reaction
  for the REST API.
  "
  [org-uuid board-uuid resource-uuid reaction-unicode reactions reacted?]
  (let [reaction-url (url org-uuid board-uuid resource-uuid reaction-unicode)]
    (json/generate-string {reaction-unicode {
        :reacted reacted?
        :count (count reactions)
        :links [(reaction-link reaction-url reacted?)]}})))