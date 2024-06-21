(ns noblepayne.pinboard-poster
  {:clj-kondo/config '{:lint-as {datalevin.core/with-transaction clojure.core/let}
                       :linters {:unresolved-symbol {:exclude [(datalevin.core/with-conn)]}}}}
  (:gen-class)
  (:require [babashka.fs :as fs]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [clojure.zip :as zip]
            [cheshire.core :as json]
            [datalevin.core :as d]
            [hato.client :as http]
            [hickory.core :as h]
            [hickory.select :as hs]
            [hickory.zip :as hz]))

;; ~~~~~~~~~~ Global Config ~~~~~~~~~~
(def FEEDS ["https://feeds.pinboard.in/rss/u:noblepayne/t:lup"])
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
(defn pull-feed [feed-url]
  (-> feed-url
      http/get
      :body
      h/parse
      h/as-hickory))

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
                      :feed.item/id item-id}]))

(defn all-entities [conn]
  (d/q '[:find (d/pull ?e ["*"])
         :where [?e _ _]]
       (d/db conn)))

(defn find-item [conn feed-url item-id]
  (first (d/q '[:find (d/pull ?e ["*"])
                :in $ ?feed-id ?item-id
                :where
                [?e :feed/id ?feed-id]
                [?e :feed.item/id ?item-id]]
              (d/db conn)
              feed-url
              item-id)))

(defn retract-all [conn e]
  (d/transact! conn [[:db/retractEntity e]]))

(defn unseen-items [conn item-ids]
  (let [results (d/q '[:find ?id
                       :in $ [?id ...]
                       :where (not [_ :feed.item/id ?id])]
                     (d/db conn)
                     item-ids)]
    (into [] (map first) results)))

;; ~~~~~~~~~~ Slack ~~~~~~~~~~
(defn format-message [{:keys [author link title description]}]
  (str "_" author "'s LUP Pinboard:_\n"
       "<" link "|" title ">"
       (when description (str "\n" description))))

(defn post-to-slack [feed-item]
  (->  SLACK-URL
       (http/post
        {:content-type :json
         :throw-exceptions? false
         :form-params {:channel "#lup-links",
                       :username "kirkland_brand_zapier",
                       :text (format-message feed-item)
                       :unfurl_links true}})
       :satus))

;; ~~~~~~~~~~ Main ~~~~~~~~~~
(defn process-feed [conn feed-url]
  (let [feed (pull-feed feed-url)
        items (find-items feed)
        parsed-items (map (comp (juxt :id identity) extract-feed-item) items)
        item-map (apply array-map (flatten parsed-items))
        all-ids (keys item-map)
        ;; This could get stale but that's ok in this use case.
        ;; We have uniqueness tracking in the db if something new has been added
        ;; to the db. If something is retracted we won't be including it, but a
        ;; subsequent run will catch it.
        unseen-ids (unseen-items conn all-ids)]
    (doseq [id unseen-ids
            :let [item (get item-map id)]]
      (d/with-transaction [txn conn]
        (try
          (store-item txn feed-url id)
          (post-to-slack item)
          (println "Processed:" id)
          (catch clojure.lang.ExceptionInfo e (when (not= :transact/unique
                                                          (-> e ex-data :error))
                                                (throw e)))
          (catch Exception e (d/abort-transact txn) (throw e)))))))

(defn -main [& _]
  (d/with-conn [conn "db" SCHEMA KV-OPTS]
    (doseq [feed FEEDS]
      (process-feed conn feed))
    ;; re-init db to reduce size 
    ;; BUG? second opening of db always resizes to 100M
    ;; re-init respects KV-OPTS and tries to fit it into mapsize.
    (d/init-db (d/datoms (d/db conn) :eav) "db2" SCHEMA KV-OPTS)) 
  ;; Replace db with fresh version.
  (fs/delete-tree "db")
  (fs/move "db2" "db"))

(comment

  (-main)

  (def conn (d/get-conn "db" SCHEMA KV-OPTS))
  (process-feed conn (first FEEDS))
  (d/close conn))