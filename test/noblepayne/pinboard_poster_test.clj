(ns noblepayne.pinboard-poster-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [hato.client :as http]
            [noblepayne.pinboard-poster :as sut]))

(def sample-rss
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <rdf:RDF xmlns=\"http://purl.org/rss/1.0/\"
            xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"
            xmlns:dc=\"http://purl.org/dc/elements/1.1/\"
            xmlns:taxo=\"http://purl.org/rss/1.0/modules/taxonomy/\"
            xmlns:content=\"http://purl.org/rss/1.0/modules/content/\">
     <channel rdf:about=\"http://pinboard.in\">
       <title>Pinboard (noblepayne)</title>
       <link>https://pinboard.in/</link>
       <items><rdf:Seq><rdf:li rdf:resource=\"https://example.com/a\"/></rdf:Seq></items>
     </channel>
     <item rdf:about=\"https://example.com/a\">
       <title>Article A</title>
       <link>https://example.com/a</link>
       <description>A great article about things</description>
       <dc:creator>noblepayne</dc:creator>
       <dc:identifier>tag:pinboard.in,2026:/test-a</dc:identifier>
     </item>
     <item rdf:about=\"https://example.com/b\">
       <title>Article B</title>
       <link>https://example.com/b</link>
       <description>Another fine article</description>
       <dc:creator>tophfisher</dc:creator>
       <dc:identifier>tag:pinboard.in,2026:/test-b</dc:identifier>
     </item>
   </rdf:RDF>")

(deftest test-parse-feed
  (testing "parses RSS XML into hickory items"
    (let [feed (sut/parse-feed sample-rss)
          items (sut/find-items feed)]
      (is (= 2 (count items))))))

(deftest test-extract-feed-items
  (testing "extracts all fields from a feed item"
    (let [feed (sut/parse-feed sample-rss)
          items (sut/find-items feed)
          parsed (map sut/extract-feed-item items)]
      (testing "first item"
        (let [a (first parsed)]
          (is (= "tag:pinboard.in,2026:/test-a" (:id a)))
          (is (= "Article A" (:title a)))
          (is (= "https://example.com/a" (:link a)))
          (is (= "A great article about things" (:description a)))
          (is (= "noblepayne" (:author a)))))
      (testing "second item"
        (let [b (second parsed)]
          (is (= "tag:pinboard.in,2026:/test-b" (:id b)))
          (is (= "Article B" (:title b)))
          (is (= "https://example.com/b" (:link b)))
          (is (= "tophfisher" (:author b)))))
      (testing "all items have required fields"
        (doseq [item parsed]
          (is (some? (:id item)))
          (is (some? (:title item)))
          (is (some? (:link item)))
          (is (some? (:author item))))))))

(deftest test-format-message
  (testing "formats message with all fields"
    (let [item {:author "noblepayne"
                :link "https://example.com/a"
                :title "Article A"
                :description "A great article"}
          msg (sut/format-message item "LUP Pinboard")]
      (is (str/includes? msg "noblepayne's LUP Pinboard"))
      (is (str/includes? msg "https://example.com/a"))
      (is (str/includes? msg "Article A"))
      (is (str/includes? msg "A great article"))))
  (testing "omits description line when nil"
    (let [item {:author "tophfisher"
                :link "https://example.com/b"
                :title "Article B"
                :description nil}
          msg (sut/format-message item "Clanker Pinboard")]
      (is (str/includes? msg "tophfisher's Clanker Pinboard"))
      (is (str/includes? msg "https://example.com/b"))
      (is (not (str/includes? msg "null")))))
  (testing "uses label parameter for the tagline"
    (let [item {:author "wes" :link "https://x.com" :title "X" :description nil}
          lup-msg (sut/format-message item "LUP Pinboard")
          clanker-msg (sut/format-message item "Clanker Pinboard")]
      (is (str/includes? lup-msg "wes's LUP Pinboard"))
      (is (str/includes? clanker-msg "wes's Clanker Pinboard")))))

(def test-schema
  {:feed/id {:db/valueType :db.type/string}
   :feed.item/id {:db/valueType :db.type/string}
   :feed.item/unique-id {:db/valueType :db.type/tuple
                         :db/tupleAttrs [:feed/id :feed.item/id]
                         :db/unique :db.unique/identity}})

(def kv-opts {:kv-opts {:mapsize 2}})

(deftest test-db-store-and-unseen
  (let [tmpdir (str "/tmp/test-pinboard-db-" (java.util.UUID/randomUUID))
        conn (d/get-conn tmpdir test-schema kv-opts)]
    (try
      (testing "store-item adds a seen item"
        (sut/store-item conn "https://feed.example.com/z" "id-store-test")
        (let [found (sut/find-item conn "https://feed.example.com/z" "id-store-test")]
          (is (some? found))
          (is (= "https://feed.example.com/z" (:feed/id found)))
          (is (= "id-store-test" (:feed.item/id found)))))

      (testing "unseen-items returns only new ids"
        (sut/store-item conn "https://feed.example.com/z" "id-seen")
        (sut/store-item conn "https://feed.example.com/z" "id-alsoseen")
        (let [unseen (sut/unseen-items conn "https://feed.example.com/z" ["id-seen" "id-alsoseen" "id-unseen"])]
          (is (= ["id-unseen"] unseen))))

      (testing "unseen-items returns all when db empty"
        (let [conn2 (d/get-conn (str tmpdir "-empty") test-schema kv-opts)]
          (try
            (let [unseen (sut/unseen-items conn2 "https://feed.example.com/z" ["id-a" "id-b"])]
              (is (= #{"id-a" "id-b"} (set unseen))))
            (finally (d/close conn2)))))

      (testing "dedup: second run produces no unseen"
        (let [ids ["dup-1" "dup-2"]
              _ (doseq [id ids] (sut/store-item conn "https://feed.example.com/d" id))
              unseen (sut/unseen-items conn "https://feed.example.com/d" ids)]
          (is (empty? unseen))))

      (finally
        (d/close conn)))))

(deftest test-process-feed-config
  (testing "processes feed, stores items, posts to slack"
    (let [tmpdir (str "/tmp/test-pinboard-process-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir test-schema kv-opts)
          posted (atom [])
          config {:feed "https://feeds.pinboard.in/rss/u:noblepayne/t:lup"
                  :channel "#lup-links"
                  :label "LUP Pinboard"}]
      (try
        (with-redefs [http/get (fn [& _] {:status 200 :body sample-rss})
                      http/post (fn [url & _] (swap! posted conj url) {:status 200})]
          (sut/process-feed-config conn config))
        (testing "stores items in db"
          (let [a (sut/find-item conn (:feed config) "tag:pinboard.in,2026:/test-a")
                b (sut/find-item conn (:feed config) "tag:pinboard.in,2026:/test-b")]
            (is (some? a))
            (is (some? b))))
        (testing "posts each item to slack"
          (is (= 2 (count @posted))))
        (testing "second run posts nothing (dedup)"
          (reset! posted [])
          (with-redefs [http/get (fn [& _] {:status 200 :body sample-rss})
                        http/post (fn [url & _] (swap! posted conj url) {:status 200})]
            (sut/process-feed-config conn config))
          (is (empty? @posted)))
        (testing "Slack 500 error: items remain unseen (not stored in db)"
          (reset! posted [])
          (let [conn2 (d/get-conn (str tmpdir "-500") test-schema kv-opts)]
            (with-redefs [http/get (fn [& _] {:status 200 :body sample-rss})
                          http/post (fn [_ _] {:status 500})]
              (sut/process-feed-config conn2 config))
            (let [a (sut/find-item conn2 (:feed config) "tag:pinboard.in,2026:/test-a")]
              (is (nil? a)))
            (d/close conn2)))
        (finally
          (d/close conn))))))
