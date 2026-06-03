(ns noblepayne.pinboard-poster
  {:clj-kondo/config '{:lint-as {datalevin.core/with-transaction clojure.core/let}
                       :linters {:unresolved-symbol {:exclude [(datalevin.core/with-conn)]}}}}
  (:gen-class)
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.zip :as zip]
            [datalevin.core :as d]
            [hato.client :as http]
            [hickory.core :as h]
            [hickory.select :as hs]
            [hickory.zip :as hz]))

;; ~~~~~~~~~~ Global Config ~~~~~~~~~~
(def FEED-CONFIG
  [{:feed "https://feeds.pinboard.in/rss/u:noblepayne/t:lup"
    :channel "#lup-links"
    :label "LUP Pinboard"}
   {:feed "https://feeds.pinboard.in/rss/u:tophfisher/t:clanker"
    :channel "#clanker-links"
    :label "Clanker Pinboard"}
   {:feed "https://feeds.pinboard.in/rss/u:noblepayne/t:clanker"
    :channel "#clanker-links"
    :label "Clanker Pinboard"}])
(def SLACK-URL (System/getenv "SLACK_URL"))
(def SCHEMA {;; feed url
             :feed/id {:db/valueType :db.type/string}
             ;; feed item id
             :feed.item/id {:db/valueType :db.type/string}
             ;; item ids are unique per feed
             :feed.item/unique-id {:db/valueType :db.type/tuple
                                   :db/tupleAttrs [:feed/id :feed.item/id]
                                   :db/unique :db.unique/identity}})

;; This only controls the initial db size, it will be resized as needed.
;; Database shouldn't need to exceed 1MB, add some margin in case we temp.
;; exceed between maintenance events.
;; TODO: prune database to most recent 100 feed items or similar?
(def KV-OPTS {:kv-opts {:mapsize 2}})

;; ~~~~~~~~~~ Feed Processing ~~~~~~~~~~
(defn parse-feed [xml-string]
  (-> xml-string h/parse h/as-hickory))

(defn pull-feed [feed-url]
  (-> feed-url http/get :body parse-feed))

(defn find-items [feed]
  (hs/select (hs/tag :item) feed))

(defn- extract-tag [feed-item tag]
  (->> feed-item (hs/select (hs/tag tag)) first :content first))

(defn- extract-link
  "N.B. link tag content is empty, link string is next element
   TODO: why is this parsing this way? try replacing with clojure.data.xml?"
  [feed-item]
  (->> feed-item
       hz/hickory-zip
       (hs/select-next-loc (hs/tag :link))
       hs/after-subtree
       zip/node))

(defn extract-feed-item [feed-item]
  (let [id (extract-tag feed-item :dc:identifier)
        title (extract-tag feed-item :title)
        link (extract-link feed-item)
        description (extract-tag feed-item :description)
        author (extract-tag feed-item :dc:creator)]
    {:id id
     :title title
     :link link
     :description (when description (str/trim description))
     :author author}))

;; ~~~~~~~~~~ Database Storage ~~~~~~~~~~
(defn store-item [conn feed-id item-id]
  (d/transact! conn [{:feed/id feed-id
                      :feed.item/id item-id
                      :feed.item/unique-id [feed-id item-id]}]))

(defn all-entities [conn]
  (d/q '[:find (d/pull ?e ["*"])
         :where [?e _ _]]
       (d/db conn)))

(defn find-item [conn feed-url item-id]
  (ffirst (d/q '[:find (d/pull ?e ["*"])
                 :in $ ?feed-id ?item-id
                 :where
                 [?e :feed/id ?feed-id]
                 [?e :feed.item/id ?item-id]]
               (d/db conn)
               feed-url
               item-id)))

(defn retract-all [conn e]
  (d/transact! conn [[:db/retractEntity e]]))

(defn unseen-items [conn feed-id item-ids]
  (let [results (d/q '[:find ?id
                       :in $ ?feed-id [?id ...]
                       :where (not [?e :feed/id ?feed-id]
                                   [?e :feed.item/id ?id])]
                     (d/db conn)
                     feed-id
                     item-ids)]
    (into [] (map first) results)))

;; ~~~~~~~~~~ Slack ~~~~~~~~~~
(defn format-message [{:keys [author link title description]} label]
  (str "_" author "'s " label ":_\n"
       "<" link "|" title ">"
       (when description (str "\n" description))))

(defn post-to-slack [feed-item channel label]
  (-> SLACK-URL
      (http/post
       {:content-type :json
        :throw-exceptions? false
        :form-params {:channel channel
                      :username "kirkland_brand_zapier"
                      :text (format-message feed-item label)
                      :unfurl_links true}})
      :status))

;; ~~~~~~~~~~ Main ~~~~~~~~~~
(defn process-feed-config [conn {:keys [feed channel label]}]
  (let [items (-> feed pull-feed find-items)
        parsed-items (map (comp (juxt :id identity) extract-feed-item) items)
        item-map (apply array-map (flatten parsed-items))
        all-ids (keys item-map)
        unseen-ids (unseen-items conn feed all-ids)]
    (doseq [id unseen-ids
            :let [item (get item-map id)]]
      (let [status (post-to-slack item channel label)]
        (when (and status (<= 200 status 299))
          (d/with-transaction [txn conn]
            (try
              (store-item txn feed id)
              (println "Processed:" id)
              (catch clojure.lang.ExceptionInfo e
                (when (not= :transact/unique (-> e ex-data :error))
                  (throw e))))))))))

(defn -main [& _]
  (d/with-conn [conn "db" SCHEMA KV-OPTS]
    (doseq [fc FEED-CONFIG]
      (process-feed-config conn fc))
    ;; re-init db to reduce size 
    ;; BUG? second opening of db always resizes to 100M
    ;; re-init respects KV-OPTS and tries to fit it into mapsize.
    (d/init-db (d/datoms (d/db conn) :eav) "db2" SCHEMA KV-OPTS))
  ;; Replace db with fresh version.
  ;; TODO: replace with d/copy?
  (fs/delete-tree "db")
  (fs/move "db2" "db"))

(comment

  (-main)

  (def conn (d/get-conn "db" SCHEMA KV-OPTS))
  (process-feed-config conn (first FEED-CONFIG))
  (d/close conn))