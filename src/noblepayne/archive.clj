(require '[[clojure.data.xml :as xml]
           [clojure.data.xml.event :as event]
           [clojure.data.xml.protocols :as proto]
           [clojure.data.xml.impl :as xml-impl]
           [cheshire.core :as json]
           [noblepayne.parse] ;; N.B. side-effects!
           [clojure.string :as str]
           [clojure.pprint :refer [pprint]]
           [clojure.walk :as w]
           [clojure.zip :as zip]
           [hickory.core :as h]
           [hickory.select :as hs]
           [hickory.zip :as hz]
           [cybermonday.core :as cm]
           [dev.onionpancakes.chassis.core :as c]])

;;; ZAPZAPZAP
  (defn apply-default-namespace [feed]
    (if-let [default-namespace (-> feed :attrs :xmlns)]
      (with-meta
        (w/prewalk
         (fn [node]
           (if (is-element? node)
             (let [tag (:tag node)
                   tag-name (name tag)
                   tag-namespace (namespace tag)
                   new-namespace (if tag-namespace
                                   tag-namespace
                                   (str (xml/uri-symbol default-namespace)))
                   new-tag (keyword new-namespace tag-name)]
               (assoc node :tag new-tag))
             node))
         feed)
        (meta feed))
      feed))

  (defn get-item-id [item]
    (let [[guid] (hs/select (hs/tag :guid) item)]
      ;; check for guid tag and use if found
      (if guid
        (-> guid :content first)
        ;; fallback to looking for dc:identifier
        (let [[dcid] (hs/select (hs/tag :dc:identifier) item)]
          ;; nil if no id found
          (when dcid
            (-> dcid :content first))))))

  (defn get-item-id2 [item]
    (let [[guid] (hs/select (hs/tag :guid) item)
          [dcid] (hs/select (hs/tag :dc:identifier) item)]
      (-> (or guid dcid) :content first)))

  (defn parsed-item->map [{:keys [:content]}]
    (into {}
          (for [{:keys [:tag :attrs :content]} content]
            [tag (if (empty? content) attrs content)])))

  (defn get-item-id3 [item]
    (let [item-map (parsed-item->map item)]
      (cond
        (contains? item-map :guid) (-> item-map :guid first)
        (contains? item-map :dc:identifier) (-> item-map :dc:identifier first))))

  (->> "https://feeds.pinboard.in/rss/u:noblepayne/t:development/"
       slurp
       (#(xml/parse-str % {:skip-whitespace true :coalescing false}))
       cleanup-feed
       apply-header
       (hs/select (hs/tag :item))
       first
       xmlparsed->xmlhiccup
       item->map
       ;;
       )
