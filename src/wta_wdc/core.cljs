(ns wta-wdc.core
  (:require [goog.functions]
            [cljs-http.client :as http]
            [cljs.core.async :as async]
            [cljs-wdc.core :as wdc]
            [hickory.core :as hc]
            [hickory.select :as hs]
            [reagent.core :as r]
            [cemerick.url :as url])
  (:require-macros
   [cljs.core.async.macros :as async]))

; (set! *warn-on-infer* true)

(declare render)

(defn deep-merge-with [f & maps]
  (apply
   (fn m [& maps]
     (if (every? map? maps)
       (apply merge-with m maps)
       (reduce f maps)))
   maps))

(defn deep-merge [& maps]
  (apply deep-merge-with (fn [x y] (or y x)) maps))

(defn wrap-baseurl [baseurl [endpoint opts]]
  [(str baseurl endpoint) opts])

(defn wrap-cors [cors-proxy [url opts]]
  (if cors-proxy
    [(clojure.string/replace-first url "://" (str "://" cors-proxy "/")) opts]
    [url opts]))

(defn wrap-auth [[url opts]]
  [url (assoc opts :with-credentials? false)])

(defonce wdc-state (r/atom nil))
(def a-hike-name (r/cursor wdc-state [:connection-data :hike-name]))
(def a-keyword-search (r/cursor wdc-state [:connection-data :keyword-search]))
(def a-region (r/cursor wdc-state [:connection-data :region]))
(def a-features (r/cursor wdc-state [:connection-data :features]))
(def a-rating (r/cursor wdc-state [:connection-data :rating]))
(def a-mileage-range (r/cursor wdc-state [:connection-data :mileage-range]))
(def a-elevation-gain (r/cursor wdc-state [:connection-data :elevation-gain]))
(def a-high-point (r/cursor wdc-state [:connection-data :high-point]))

(defonce app-state
  (r/atom
   {:show-ui? true
    :called-by-tableau? false}))

(def baseurl "http://www.wta.org")
(def cors-proxy "dtreskunov-cors-anywhere.herokuapp.com")
(def hike-search-endpoint "/go-outside/hikes/hike_search")

(defn <request [endpoint & [req]]
  (->> [endpoint req]
       (wrap-baseurl baseurl)
       (wrap-cors cors-proxy)
       (wrap-auth)
       (apply http/get)))

(defn request [endpoint req cb]
  (async/go (cb (async/<! (<request endpoint req)))))

(defn get-content [{:keys [content]}]
  (->> content
       (map #(if (string? %)
               (clojure.string/trim %)
               (get-content %)))
       (filter #(not (clojure.string/blank? %)))
       (clojure.string/join " ")))

(defn number [s]
  (first (re-find #"\d+(\.\d+)?" s)))

(defn get-attr-vals [name {:keys [attrs content] :as hickory}]
  (letfn [(-get-attr-vals [{:keys [attrs content]}]
            (lazy-seq
             (let [val (get attrs name)
                   vals (map -get-attr-vals content)]
               (cons val vals))))]
    (->> hickory
         -get-attr-vals
         flatten
         (remove nil?))))

(defn all-by-class [cls hickory]
  (hs/select (hs/class cls) hickory))

(defn by-class [cls hickory]
  (first (all-by-class cls hickory)))

(defn search-page->hike-summaries [search-page]
  (all-by-class :search-result-item search-page))

(defn hike-summary->row [item]
  (let [[region sub-region] (->> item (by-class :region) get-content (#(clojure.string/split % #" -- ")))
        hike-length (->> item (by-class :hike-length) get-content)]
    {:url (->> item (by-class :listitem-title) :attrs :href)
     :title (->> item (by-class :listitem-title) get-content)
     :region region
     :subRegion sub-region
     :length (->> hike-length number js/parseFloat)
     :lengthType (->> hike-length (re-find #",\s*(.*)") second)
     :elevationGain (->> item (by-class :hike-gain) get-content number js/parseFloat)
     :highestPoint (->> item (by-class :hike-highpoint) get-content number js/parseFloat)
     :currentRating (->> item (by-class :current-rating) get-content number js/parseFloat)
     :ratingCount (->> item (by-class :rating-count) get-content number int)
     :features (->> item (by-class :trip-features) (get-attr-vals :title) (clojure.string/join "|"))
     :alert (->> item (by-class :alert) get-content)
     :excerpt (->> item (by-class :show-excerpt) get-content)
     :thumbnailUrl (->> item (by-class :listing-image) (get-attr-vals :src) first)}))

(defn omit-nil-vals [m]
  (into {} (filter (fn [[k v]] (some? v)) m)))

(defn get-initial-query-params []
  (omit-nil-vals
   {:title @a-hike-name
    :region (:id @a-region)
    :subregion "all"
    :features:list (->> @a-features vals (map :id))
    :rating (:id @a-rating)
    :mileage:int (:id @a-mileage-range)
    :elevationgain:int (:id @a-elevation-gain)
    :highpoint @a-high-point
    :searchabletext @a-keyword-search
    :sort "name"
    :filter "Search"}))

(defn get-next-query-params [search-page]
  (->> search-page
       (hs/select (hs/child (hs/id :hike_results) (hs/class :pagination) (hs/class :next)))
       first :attrs :href url/url :query))

(defn get-total-results-count [search-page]
  (->> search-page
       (hs/select (hs/class :search-count))
       first get-content number int))

(defn <request-hikes []
  (let [out (async/chan)]
    (async/go-loop [query-params (get-initial-query-params)]
      (let [{:keys [status body]} (async/<! (<request hike-search-endpoint {:query-params query-params}))]
        (if (= 200 status)
          (let [search-page (hc/as-hickory (hc/parse body))
                next-query-params (get-next-query-params search-page)
                rows (->> search-page
                          search-page->hike-summaries
                          (map hike-summary->row))]
            (async/>! out rows)
            (if next-query-params
              (recur next-query-params)
              (async/close! out)))
          (throw (str "HTTP status " status)))))
    out))

(deftype WTAWDC []
  wdc/IWebDataConnector
  (get-auth-type [this] "none")
  (get-name [this] "Washington Trails Association")
  (get-table-infos [this]
    [{:id "hikes"
      :alias   "Hikes"
      :columns [{:id "url" :dataType "string"}
                {:id "title" :dataType "string"}
                {:id "region" :dataType "string"}
                {:id "subRegion" :dataType "string"}
                {:id "length" :dataType "float"}
                {:id "lengthType" :dataType "string"}
                {:id "elevationGain" :dataType "float"}
                {:id "highestPoint" :dataType "float"}
                {:id "currentRating" :dataType "float"}
                {:id "ratingCount" :dataType "int"}
                {:id "features" :dataType "string"}
                {:id "alert" :dataType "string"}
                {:id "excerpt" :dataType "string"}
                {:id "thumbnailUrl" :dataType "string"}]}])
  (get-standard-connections [this] [])
  (<get-rows [this {:keys [id] :as table-info} increment-value filter-values]
    (case id
      "hikes" (<request-hikes)))
  (shutdown [this] @wdc-state)
  (init [this phase state]
    (swap! wdc-state deep-merge state)
    (swap! app-state merge {:show-ui? (#{"auth" "interactive"} phase)
                            :auth-only? (= "auth" phase)
                            :called-by-tableau? true}))
  (check-auth [this state done] (done)))

(def wdc (WTAWDC.))
(wdc/register! wdc)

(defn get-regions [search-page]
  (->> search-page
       (hs/select (hs/child (hs/id :super-region) (hs/tag :option)))
       (map (fn [option]
              {:id (-> option :attrs :value)
               :displayName (-> option :content first)}))))

(defn get-features [search-page]
  (->> search-page
       (hs/select (hs/descendant (hs/id "filter-features") (hs/attr :name #(= "features:list"))))
       (map (fn [input]
              {:id (-> input :attrs :value)
               :displayName (-> input :attrs :value)}))))

(def ratings
  [{:id "0" :displayName "No minimum"}
   {:id "1" :displayName "At least 1 star"}
   {:id "2" :displayName "At least 2 stars"}
   {:id "3" :displayName "At least 3 stars"}
   {:id "4" :displayName "At least 4 stars"}
   {:id "5" :displayName "5 stars only, I don't mess around!"}])

(def mileage-ranges
  [{:id "0" :displayName "No limit"}
   {:id "1" :displayName "Under 3 miles"}
   {:id "2" :displayName "3 to 8 miles"}
   {:id "3" :displayName "8 to 12 miles"}
   {:id "4" :displayName "Over 12 miles, I want to go far!"}])

(def elevation-gains
  [{:id "0" :displayName "No limit"}
   {:id "1" :displayName "Negligible: less than 500 ft"}
   {:id "2" :displayName "Minimum: 500-1500 ft"}
   {:id "3" :displayName "Moderate: 1500-3000 ft"}
   {:id "4" :displayName "Strenuous: Greater than 3000 ft, I want to climb a mountain!"}])

(defn bind [state & {:keys [js-> ->js] :or {js-> identity ->js identity} :as attrs}]
  (letfn [(get-value [event]
            (-> event
                (aget "target")
                (aget "value")
                js->))
          (on-change [event]
            (reset! state (get-value event)))]
    (assoc attrs
           :value (->js @state)
           :on-change on-change)))

(defn indexed [f coll]
  (into {} (map (fn [x] [(f x) x]) coll)))

(defn select-component
  "A <select> (combo) component.
  
  The first argument is an atom holding the selected item or index of selected items (map id -> item).
  The second argument is an atom holding items available to be selected.

  Optional keyword arguments:
  :display-by function that takes an item and returns a string to be displayed
  :index-by function that takes an item and returns a string id
  :size how many lines to show
  :multiple? whether to allow multiple selection
  :keywordize? whether the keys of the index of selected items need to be keywordized"
  [a-selection a-items {:keys [display-by index-by size multiple? keywordize?]
                        :or {display-by :displayName index-by :id size 5 multiple? true keywordize? true}}]
  (let [get-id (fn [v] (if keywordize? (keyword v) v))
        a-items-index (r/track #(indexed (comp get-id index-by) @a-items))
        on-select-change (fn [e]
                           (let [selection
                                 (if multiple?
                                   (let [js-options (-> e (aget "target") (aget "options"))
                                         options (for [i (range (aget js-options "length"))] (aget js-options i))
                                         items (->> options
                                                    (filter (fn [option] (aget option "selected")))
                                                    (map (fn [option] (aget option "value")))
                                                    (map get-id)
                                                    (select-keys @a-items-index))]
                                     items)
                                   (let [id (-> e (aget "target") (aget "value") get-id)
                                         item (get @a-items-index id)]
                                     item))]
                             (reset! a-selection selection)))
        default-value (if multiple?
                        (or (keys @a-selection) [])
                        (or (index-by @a-selection) ""))]
    (fn []
      (when-not multiple?
        (when-not @a-selection
          (reset! a-selection (first @a-items))))
      [:select.form-control {:default-value default-value :on-change on-select-change :size size :multiple multiple?}
       (for [item @a-items]
         ^{:key item} [:option {:value (index-by item)} (display-by item)])])))


(defn ui-component []
  (let [a-search-page (r/atom nil)
        a-available-regions (r/track #(get-regions @a-search-page))
        a-available-features (r/track #(get-features @a-search-page))
        a-total-results-count (r/track #(get-total-results-count @a-search-page))
        a-initial-query-params (r/track get-initial-query-params)
        -request-search-page (fn [query-params]
                               (request hike-search-endpoint
                                        {:query-params query-params}
                                        (fn [{:keys [body]}] (reset! a-search-page (hc/as-hickory (hc/parse body))))))
        request-search-page (goog.functions/debounce -request-search-page 1000)]
    (fn []
      (request-search-page @a-initial-query-params)
      [:div.panel.panel-primary
       [:div.panel-body
        [:form.form-horizontal
         [:div.form-group
          [:label.col-sm-3.control-label "Hike name"]
          [:div.col-sm-9
           [:input.form-control (bind a-hike-name :type "text" :placeholder "Try \"snowshoe\"...")]]]
         [:div.form-group
          [:label.col-sm-3.control-label "Keyword search"]
          [:div.col-sm-9
           [:input.form-control (bind a-keyword-search :type "text" :placeholder "Full text search of the trail description")]
           [:div.small "Using this option may result in terrible performance"]]]
         [:div.form-group
          [:label.col-sm-3.control-label "Region"]
          [:div.col-sm-9
           [select-component a-region a-available-regions {:multiple? false :size 12}]]]
         [:div.form-group
          [:label.col-sm-3.control-label "Features"]
          [:div.col-sm-9
           [select-component a-features a-available-features {:multiple? true :size 12}]
           [:div.small "Hold down Cmd/Ctrl key to select multiples"]]]
         [:div.form-group
          [:label.col-sm-3.control-label "Rating"]
          [:div.col-sm-9
           [select-component a-rating (r/atom ratings) {:multiple? false :size 1}]]]
         [:div.form-group
          [:label.col-sm-3.control-label "Mileage range"]
          [:div.col-sm-9
           [select-component a-mileage-range (r/atom mileage-ranges) {:multiple? false :size 1}]]]
         [:div.form-group
          [:label.col-sm-3.control-label "Elevation gain"]
          [:div.col-sm-9
           [select-component a-elevation-gain (r/atom elevation-gains) {:multiple? false :size 1}]]]
         [:div.form-group
          [:label.col-sm-3.control-label "High point"]
          [:div.col-sm-9
           [:input.form-control (bind a-high-point :type "text" :placeholder "Max elevation, ft")]]]
         [:div.form-group
          [:div.col-sm-offset-3.col-sm-9
           [:div.small "You must abide by WTA's "
            [:a {:href "http://www.wta.org/our-work/about/terms-of-service" :target "_blank"} "Terms of Service"]
            " in order to use this data."]
           [:button.btn.btn-primary {:type "button" :on-click #(wdc/go! wdc)} (str "Fetch " @a-total-results-count " hikes")]]]]]])))

(defn learn-more-component []
  [:div
   [:p "Open this page inside Tableau to connect to your data. "
    [:a
     {:href "https://onlinehelp.tableau.com/current/pro/desktop/en-us/examples_web_data_connector.html"
      :target "_blank" :role "button"}
     "Learn more..."]] 
   [:p
    [:a.btn.btn-primary.btn-lg {:href "tableau://do-something-cool"} "Launch Tableau"]]])

(defn root-component []
  (when (:show-ui? @app-state)
    [:div.container
     [:div.jumbotron
      [:h2 "Washington Trails Association"]
      (if (:called-by-tableau? @app-state)
        [ui-component]
        [learn-more-component])]]))

(r/render-component
 [root-component]
 (. js/document (getElementById "app")))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  (swap! app-state update-in [:__figwheel_counter] inc)
)
