(ns oc.interaction.representations.interaction
  "Resource representations for OpenCompany interactions."
  (:require [defun.core :refer (defun defun-)]
            [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.interaction.config :as config]))

(def comment-media-type "application/vnd.open-company.comment.v1+json")
(def comment-collection-media-type "application/vnd.collection+vnd.open-company.comment+json;version=1")

(def reaction-media-type "application/vnd.open-company.reaction.v1+json")

(def representation-props [:body :reaction :author :created-at :updated-at])

(defun url 

  ([org-uuid board-uuid topic-slug entry-uuid]
  (str "/orgs/" org-uuid "/boards/" board-uuid "/topics/" topic-slug "/entries/" entry-uuid))
  
  ([org-uuid board-uuid topic-slug entry-uuid reaction-unicode]
  (str (url org-uuid board-uuid topic-slug entry-uuid) "/reactions/" reaction-unicode "/on"))
  
  ([interaction :guard :interaction-uuid]
  (str (url (dissoc interaction :interaction-uuid))
       "/comments/" (:interaction-uuid interaction)
       "/reactions/" (:uuid interaction)))
  
  ([interaction]
  (str (url (:org-uuid interaction) (:board-uuid interaction) (:topic-slug interaction) (:entry-uuid interaction))
    "/comments/" (:uuid interaction))))

(defn- self-link [interaction] (hateoas/self-link (url interaction) {:accept comment-media-type}))

(defn- interaction-links [interaction access-level]
  (assoc interaction :links [(self-link interaction)]))

(defun- reaction-link 
  ([reaction-url true] (hateoas/link-map "react" hateoas/DELETE reaction-url {:accept reaction-media-type}))
  ([reaction-url false] (hateoas/link-map "react" hateoas/PUT reaction-url {:accept reaction-media-type})))

(defn render-interaction
  "Given an interaction, create a JSON representation for the REST API."
  [interaction access-level]
  (json/generate-string
    (-> interaction
      (interaction-links access-level)
      (select-keys (conj representation-props :links)))
    {:pretty config/pretty?}))

(defn render-interaction-list
  "
  Given the parts of an interaction URL, a sequence of interactions, and a user, render a list of the interactions
  for the REST API.
  "
  [org-uuid board-uuid topic-slug entry-uuid interactions user]
  (let [collection-url (str (url org-uuid board-uuid topic-slug entry-uuid) "/comments")
        links [(hateoas/self-link collection-url {:accept comment-collection-media-type})]]
    (json/generate-string
      {:collection {:version hateoas/json-collection-version
                    :href collection-url
                    :links links
                    :items (map #(select-keys (interaction-links % :none) (conj representation-props :links))
                              interactions)}}
        {:pretty config/pretty?})))

 (defn render-reaction
  "
  Given a unicode character, a list of reactions, and an indication if the user reacted or not, render a reaction
  for the REST API.
  "
  [org-uuid board-uuid topic-slug entry-uuid reaction-unicode reactions reacted?]
  (let [reaction-url (url org-uuid board-uuid topic-slug entry-uuid reaction-unicode)]
    (json/generate-string {reaction-unicode {
        :reacted reacted?
        :count (count reactions)
        :links [(reaction-link reaction-url reacted?)]}})))