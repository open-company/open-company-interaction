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
  
  ([interaction]
  (str (url (:org-uuid interaction) (:board-uuid interaction) (:resource-uuid interaction))
    "/comments/" (:uuid interaction))))

(defn- update-link [interaction] (hateoas/partial-update-link (url interaction) {:content-type comment-media-type
                                                                       :accept comment-media-type}))

(defn- delete-link [interaction] (hateoas/delete-link (url interaction)))

(defn- interaction-links [interaction access-level]
  (let [links (if (= access-level :author) [(update-link interaction) (delete-link interaction)] [])]
    (assoc interaction :links links)))

(defun- reaction-link 
  ([reaction-url true] (hateoas/link-map "react" hateoas/DELETE reaction-url {}))
  ([reaction-url false] (hateoas/link-map "react" hateoas/PUT reaction-url {:accept reaction-media-type})))

(defn- access
  "Return `:author` if the specified interaction was authored by the specified user and `:none` if not."
  [interaction user]
  (if (= (-> interaction :author :user-id) (:user-id user)) :author :none))

(defn- comment-reaction-link
  "Create a reactions url using the resource url for the comment"
  [reaction interaction]
  (let [split-url (string/split (:href (first (:links interaction))) #"/" )
        base-url (take 6 split-url)
        interaction-uuid (last split-url)]
    (str (string/join "/" base-url) "/" interaction-uuid "/reactions/" reaction "/on")))

(defn- comment-reaction-and-link
  "Given the reaction and comment, return a map representation of the reaction for use in the API."
  [reaction interaction reacted?]
  (let [reaction-uuid (first reaction)]
    {:reaction reaction-uuid
     :reacted reacted?
     :count (last reaction)
     :links [(reaction-link (comment-reaction-link reaction-uuid interaction) reacted?)]}))

(defn- comment-reactions
  [interaction user]
  (if (:body interaction)
    (let [default-reactions (apply hash-map (interleave ["Agree"] (repeat [])))
          grouped-reactions (merge default-reactions
                                   (group-by :reaction (:reactions interaction))) ; reactions grouped by unicode character
          counted-reactions-map (map-kv count grouped-reactions) ; how many for each character?
          counted-reactions (map #(vec [% (get counted-reactions-map %)]) (keys counted-reactions-map))
          reaction-authors (map #(:user-id (:author %)) (:reactions interaction))
          reacted? (not (empty? (filter #(= % user) (vec reaction-authors))))]
      (assoc interaction :reactions
             (map #(comment-reaction-and-link % interaction reacted?) counted-reactions)))
      interaction))

(defn interaction-representation
  "Given an interaction, create a representation."
  [interaction access-level]
  (-> interaction
    (interaction-links access-level)
    (select-keys (conj representation-props :links))))

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
                    :items (map #(comment-reactions % user-id) interactions-with-links)}}
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