{:epupp/script-name "pez/linkedin_post_tracker.cljs"
 :epupp/auto-run-match "https://www.linkedin.com/*"
 :epupp/description "Track posts you engage with on LinkedIn and find them later"
 :epupp/run-at "document-idle"
 :epupp/inject ["scittle://replicant.js"]}

(ns pez.linkedin-post-tracker
  (:require [clojure.edn]
            [clojure.string :as string]
            [replicant.dom :as r]))

(defonce !state
  (atom {:tracker/posts   {}
         :tracker/index   []
         :ui/panel-open?  false
         :ui/search-text  ""
         :ui/filter-engagement nil
         :ui/sort-by      :sort/last-engaged
         :ui/sort-order   :desc
         :nav/seen-urns   #{}}))

(defonce !resources (atom {}))

;; Panel container must be created once and kept as a DOM reference
(when-not (:resource/panel-container @!resources)
  (let [container (doto (js/document.createElement "div")
                    (set! -id "epupp-tracker-panel-root")
                    (->> (.appendChild js/document.body)))]
    (swap! !resources assoc :resource/panel-container container)))

(def selectors ; Selector Registry
  {:sel/feed-container    [".scaffold-finite-scroll__content" "main"]
   :sel/post-container    ["[data-urn]"]
   :sel/author-name       [".update-components-actor__single-line-truncate [aria-hidden='true']"]
   :sel/author-headline   [".update-components-actor__description [aria-hidden='true']"]
   :sel/author-avatar     [".update-components-actor__avatar-image"]
   :sel/author-link       [".update-components-actor__meta-link"]
   :sel/post-text         [".update-components-text" ".feed-shared-update-v2__description"]
   :sel/timestamp         [".update-components-actor__sub-description [aria-hidden='true']"]
   :sel/like-button       ["button[aria-label*='React']"]
   :sel/comment-button    ["button[aria-label='Comment']"]
   :sel/see-more          ["button.see-more" ".feed-shared-inline-show-more-text__see-more-less-toggle"]
   :sel/repost-button     ["button.social-reshare-button"]
   :sel/overflow-menu     [".feed-shared-control-menu__trigger"]
   :sel/social-action-bar [".feed-shared-social-action-bar"]
   :sel/article-card      ["article.update-components-article"]
   :sel/article-title     [".update-components-article__title"]
   :sel/article-subtitle  ["[class*='update-components-article__subtitle']"]
   :sel/article-link      ["a[aria-label]"]
   :sel/image-container   [".update-components-image"]
   :sel/video             ["video"]
   :sel/document          [".update-components-document__container"]
   :sel/carousel          [".feed-shared-carousel"]
   :sel/poll              [".feed-shared-poll"]
   :sel/nav-bar           [".global-nav__nav" "#global-nav nav"]
   :sel/nav-items-list    [".global-nav__primary-items"]
   :sel/nav-items         [".global-nav__primary-item"]
   :sel/user-avatar       ["img.global-nav__me-photo"]})

(defn q
  "Query for first matching element using selector fallback chain."
  [context sel-key]
  (let [chain (get selectors sel-key)]
    (when chain
      (loop [[sel & more] chain
             idx 0]
        (when sel
          (let [result (try (.querySelector context sel)
                            (catch :default _e nil))]
            (if result
              (do
                (when (pos? idx)
                  (js/console.warn "[epupp:tracker] Fell to secondary selector for" (name sel-key) ":" sel))
                result)
              (recur more (inc idx)))))))))

(defn qa
  "Query for all matching elements using selector fallback chain.
   Returns a seq of elements or nil."
  [context sel-key]
  (let [chain (get selectors sel-key)]
    (when chain
      (loop [[sel & more] chain
             idx 0]
        (when sel
          (let [result (try (seq (.querySelectorAll context sel))
                            (catch :default _e nil))]
            (if (seq result)
              (do
                (when (pos? idx)
                  (js/console.warn "[epupp:tracker] Fell to secondary selector for" (name sel-key) ":" sel))
                result)
              (recur more (inc idx)))))))))

(defn q-doc [sel-key] (q js/document sel-key))
(defn qa-doc [sel-key] (qa js/document sel-key))

;; Utility Predicates

(defn activity-urn? [urn]
  (and (string? urn)
       (string/starts-with? urn "urn:li:activity:")))

(defn selector-health-check! []
  (let [results (into {}
                      (map (fn [[k _]] [k (some? (q-doc k))]))
                      selectors)
        {found true missing false} (group-by val results)]
    (js/console.log "[epupp:tracker] Selector health check:")
    (js/console.log "  Found:" (count found) (pr-str (mapv key found)))
    (when (seq missing)
      (js/console.warn "  Missing:" (count missing) (pr-str (mapv key missing))))
    results))

;; Scraping Boundary (impure — only place touching DOM for post data)

(defn scrape-post-element [post-el]
  {:raw/urn (.getAttribute post-el "data-urn")
   :raw/author-name (some-> (q post-el :sel/author-name) .-textContent string/trim)
   :raw/author-headline (some-> (q post-el :sel/author-headline) .-textContent string/trim)
   :raw/author-avatar-url (some-> (q post-el :sel/author-avatar) (.getAttribute "src"))
   :raw/author-profile-url (some-> (q post-el :sel/author-link) (.getAttribute "href"))
   :raw/text (some-> (q post-el :sel/post-text) .-textContent)
   :raw/timestamp-text (some-> (q post-el :sel/timestamp) .-textContent)
   :raw/has-article? (some? (q post-el :sel/article-card))
   :raw/article-title (some-> (q post-el :sel/article-card) (q :sel/article-title) .-textContent string/trim)
   :raw/article-subtitle (some-> (q post-el :sel/article-card) (q :sel/article-subtitle) .-textContent string/trim)
   :raw/article-url (some-> (q post-el :sel/article-card) (q :sel/article-link) (.getAttribute "href"))
   :raw/has-video? (some? (q post-el :sel/video))
   :raw/video-poster-url (some-> (q post-el :sel/video) (.getAttribute "poster"))
   :raw/has-document? (some? (q post-el :sel/document))
   :raw/has-carousel? (some? (q post-el :sel/carousel))
   :raw/has-poll? (some? (q post-el :sel/poll))
   :raw/has-image? (some? (q post-el :sel/image-container))
   :raw/image-url (some-> (q post-el :sel/image-container) (.querySelector "img") (.getAttribute "src"))
   :raw/article-image-url (some-> (q post-el :sel/article-card) (.querySelector "img") (.getAttribute "src"))
   :raw/has-reshare? (some? (.querySelector post-el ".update-components-mini-update-v2"))})

;; Pure Transforms (testable without DOM)

(defn text-preview [raw-text]
  (when raw-text
    (let [trimmed (string/trim raw-text)]
      (if (> (count trimmed) 200)
        (str (subs trimmed 0 200) "\u2026")
        trimmed))))

(defn detect-media-type [{:keys [raw/has-article? raw/has-video? raw/has-document?
                                  raw/has-carousel? raw/has-poll? raw/has-image?]}]
  (cond
    has-article?  :media/article
    has-video?    :media/video
    has-document? :media/document
    has-carousel? :media/carousel
    has-poll?     :media/poll
    has-image?    :media/image
    :else         :media/text))

(defn raw->post-snapshot [raw-data now]
  (let [media-type (detect-media-type raw-data)]
    (cond-> {:post/urn (:raw/urn raw-data)
             :post/first-seen now
             :post/last-engaged now
             :post/author-name (:raw/author-name raw-data)
             :post/author-headline (:raw/author-headline raw-data)
             :post/author-avatar-url (:raw/author-avatar-url raw-data)
             :post/author-profile-url (:raw/author-profile-url raw-data)
             :post/text-preview (text-preview (:raw/text raw-data))
             :post/media-type media-type
             :post/media-image-url (or (:raw/video-poster-url raw-data)
                                       (:raw/image-url raw-data))
             :post/reshare? (:raw/has-reshare? raw-data)
             :post/engagements #{}
             :post/pinned? false}
      (= media-type :media/article)
      (assoc :post/article-title (:raw/article-title raw-data)
             :post/article-subtitle (:raw/article-subtitle raw-data)
             :post/article-url (:raw/article-url raw-data)
             :post/article-image-url (:raw/article-image-url raw-data)))))

(defn promoted-post? [{:keys [raw/urn raw/timestamp-text]}]
  (or (not (activity-urn? urn))
      (some? (when timestamp-text (re-find #"(?i)promot" timestamp-text)))))

(defn find-post-urn [el]
  (when-let [post-el (.closest el "[data-urn]")]
    (let [raw (scrape-post-element post-el)]
      (when (and (activity-urn? (:raw/urn raw))
                 (not (promoted-post? raw)))
        (:raw/urn raw)))))

(defonce native-storage-fns
  (let [iframe (js/document.createElement "iframe")
        _ (set! (.. iframe -style -display) "none")
        _ (.appendChild js/document.body iframe)
        clean-window (.-contentWindow iframe)
        proto (.-prototype (.-Storage clean-window))
        set-item (.-setItem proto)
        get-item (.-getItem proto)
        remove-item (.-removeItem proto)]
    {:set-item set-item
     :get-item get-item
     :remove-item remove-item}))

(def storage-key "epupp:linkedin-tracker/posts")
(def post-cap 500)
(def prune-batch 50)

(defn storage-set! [k v]
  (.call (:set-item native-storage-fns) js/localStorage k v))

(defn storage-get [k]
  (.call (:get-item native-storage-fns) js/localStorage k))

(defn storage-remove! [k]
  (.call (:remove-item native-storage-fns) js/localStorage k))

(defn track-post
  "Add or update a post in state. Merges engagement and preserves pin."
  [state urn snapshot engagement-type now]
  (let [existing (get-in state [:tracker/posts urn])
        merged (if existing
                 (-> existing
                     (update :post/engagements (fnil conj #{}) engagement-type)
                     (assoc :post/last-engaged now))
                 (-> snapshot
                     (assoc :post/engagements #{engagement-type})
                     (assoc :post/last-engaged now)))]
    (-> state
        (assoc-in [:tracker/posts urn] merged)
        (update :tracker/index
                (fn [idx]
                  (if (some #{urn} idx)
                    idx
                    (conj (or idx []) urn)))))))

(defn toggle-pin [state urn]
  (update-in state [:tracker/posts urn :post/pinned?] not))

(defn prune-posts
  "Remove oldest unpinned posts when over capacity."
  [state]
  (let [posts (:tracker/posts state)
        index (:tracker/index state)]
    (if (<= (count posts) post-cap)
      state
      (let [unpinned-oldest (->> index
                                 (filter #(not (get-in posts [% :post/pinned?])))
                                 (sort-by #(get-in posts [% :post/first-seen]))
                                 (take prune-batch))
            remove-set (set unpinned-oldest)]
        (-> state
            (update :tracker/posts #(apply dissoc % unpinned-oldest))
            (update :tracker/index #(vec (remove remove-set %))))))))



(defn make-debounced [delay-ms f]
  (let [!timeout (atom nil)]
    (fn [& args]
      (when-let [t @!timeout]
        (js/clearTimeout t))
      (reset! !timeout
              (js/setTimeout #(apply f args) delay-ms)))))

(defn- attach-listener!
  [target event resource-key handler-fn opts]
  (let [{:keys [capture?] :or {capture? false}} opts]
    (when-let [old (resource-key @!resources)]
      (.removeEventListener target event old capture?))
    (swap! !resources assoc resource-key handler-fn)
    (.addEventListener target event handler-fn capture?)))

(defn- detach-listener!
  [target event resource-key opts]
  (let [{:keys [capture?] :or {capture? false}} opts]
    (when-let [handler (resource-key @!resources)]
      (.removeEventListener target event handler capture?)
      (swap! !resources assoc resource-key nil))))

(defn save-state! []
  (let [{:keys [tracker/posts tracker/index]} @!state
        data {:posts posts :index index}]
    (storage-set! storage-key (pr-str data))
    (js/console.log "[epupp:tracker] Saved" (count posts) "posts")))

(defn load-state! []
  (let [from-new (storage-get storage-key)
        from-old (when-not from-new (storage-get "epupp:linkedin-tracker"))
        raw (or from-new from-old)]
    (when raw
      (try
        (let [{:keys [posts index]} (clojure.edn/read-string raw)]
          (swap! !state merge
                 {:tracker/posts (or posts {})
                  :tracker/index (or index [])})
          (when from-old
            (save-state!)
            (storage-remove! "epupp:linkedin-tracker")
            (js/console.log "[epupp:tracker] Migrated from old storage key"))
          (js/console.log "[epupp:tracker] Loaded" (count posts) "posts"))
        (catch :default e
          (js/console.error "[epupp:tracker] Failed to load state:" e))))))

(def schedule-save! (make-debounced 3000 save-state!))

(defn extract-click-context [target]
  (let [closest-btn (when (not= (.. target -tagName toLowerCase) "button")
                      (.closest target "button"))]
    {:btn-aria (or (some-> (or closest-btn target) (.getAttribute "aria-label")) "")
     :text (or (.-textContent target) "")}))

(def click-patterns
  [{:source :btn-aria :pattern #"(?i)react"    :engagement :engaged/liked}
   {:source :btn-aria :pattern #"(?i)comment"  :engagement :engaged/commented}
   {:source :btn-aria :pattern #"(?i)repost"   :engagement :engaged/reposted}
   {:source :text     :pattern #"(?i)more"     :engagement :engaged/expanded}])

(defn interpret-click [click-context]
  (some (fn [{:keys [source pattern engagement]}]
          (when (re-find pattern (get click-context source ""))
            engagement))
        click-patterns))

(defn handle-engagement! [e]
  (try
    (let [target (.-target e)]
      (when-let [engagement (interpret-click (extract-click-context target))]
        (when-let [urn (find-post-urn target)]
          (let [post-el (.closest target "[data-urn]")
                now (.toISOString (js/Date.))
                raw (scrape-post-element post-el)
                snapshot (raw->post-snapshot raw now)]
            (swap! !state track-post urn snapshot engagement now)
            (schedule-save!)
            (js/console.log "[epupp:tracker] Engagement:" (name engagement) urn)))))
    (catch :default err
      (js/console.error "[epupp:tracker] Engagement handler error:" err))))

(defn attach-engagement-listener! []
  (attach-listener! js/document.body "click" :resource/engagement-handler handle-engagement! {:capture? true}))

(defn detach-engagement-listener! []
  (detach-listener! js/document.body "click" :resource/engagement-handler {:capture? true}))

(defn nav-button-view [{:keys [post-count open?]}]
  [:li {:id "epupp-tracker-nav-btn"
        :class "global-nav__primary-item"}
   [:button {:type "button"
             :style {:background "none" :border "none" :cursor "pointer"
                     :display "flex" :flex-direction "column" :align-items "center"
                     :padding "0" :width "48px" :height "52px" :justify-content "center"
                     :color (if open? "#0a66c2" "rgba(0,0,0,0.6)")}
             :on {:click (fn [e] (.stopPropagation e)
                           (swap! !state update :ui/panel-open? not))}}
    [:span {:style {:position "relative" :display "flex" :align-items "center"
                    :justify-content "center"}
            :title (str post-count " posts tracked")}
     [:svg {:viewBox "0 0 24 24" :width "24" :height "24" :fill "currentColor"
            :xmlns "http://www.w3.org/2000/svg"}
      [:path {:d "M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z"}]]]
    [:span {:style {:font-size "12px" :color "inherit" :line-height "1"
                    :display "inline-flex" :align-items "center" :gap "2px"}}
     "Tracker"
     [:span {:style (if open?
                      {:border-left "4px solid transparent"
                       :border-right "4px solid transparent"
                       :border-bottom "5px solid currentColor"}
                      {:border-left "4px solid transparent"
                       :border-right "4px solid transparent"
                       :border-top "5px solid currentColor"})}]]]])

(defn- find-me-nav-item [nav-list]
  (some (fn [item]
          (when (or (q item :sel/user-avatar)
                    (re-find #"(?i)^\s*Me\s*$" (.-textContent item)))
            item))
        (qa nav-list :sel/nav-items)))

(defn ensure-nav-button! []
  (when-let [nav-list (q-doc :sel/nav-items-list)]
    (let [mount-el (or (js/document.getElementById "epupp-tracker-nav-mount")
                       (let [el (js/document.createElement "div")
                             me-item (find-me-nav-item nav-list)]
                         (set! (.-id el) "epupp-tracker-nav-mount")
                         (set! (.. el -style -display) "contents")
                         (if me-item
                           (.insertBefore nav-list el me-item)
                           (.appendChild nav-list el))
                         (swap! !resources assoc :resource/nav-mount el)
                         el))]
      (r/render mount-el
                (nav-button-view {:post-count (count (:tracker/posts @!state))
                                  :open? (:ui/panel-open? @!state)})))))

(defn inject-pin-button! [post-el urn]
  (when-not (.querySelector post-el "[data-epupp-pin]")
    (let [overflow-btn (q post-el :sel/overflow-menu)
          target-container (when overflow-btn (.-parentElement overflow-btn))
          btn (js/document.createElement "button")
          pinned? (get-in @!state [:tracker/posts urn :post/pinned?])]
      (when target-container
        (.setAttribute btn "data-epupp-pin" urn)
        (set! (.. btn -style -cssText)
              "background: none; border: none; cursor: pointer; padding: 4px; font-size: 16px; line-height: 1; color: #666; display: inline-flex; align-items: center; justify-content: center; border-radius: 50%; width: 28px; height: 28px;")
        (set! (.-textContent btn) (if pinned? "\u2605" "\u2606"))
        (when pinned? (set! (.. btn -style -color) "#f59e0b"))
        (.addEventListener btn "mouseenter"
                           (fn [_] (set! (.. btn -style -background) "rgba(0,0,0,0.08)")))
        (.addEventListener btn "mouseleave"
                           (fn [_] (set! (.. btn -style -background) "none")))
        (.addEventListener btn "click"
                           (fn [e]
                             (.stopPropagation e)
                             (.preventDefault e)
                             (let [now (.toISOString (js/Date.))
                                   raw (scrape-post-element post-el)
                                   snapshot (raw->post-snapshot raw now)]
                               (swap! !state (fn [s]
                                               (let [s (if (get-in s [:tracker/posts urn])
                                                         s
                                                         (track-post s urn snapshot :engaged/pinned now))]
                                                 (toggle-pin s urn))))
                               (let [now-pinned? (get-in @!state [:tracker/posts urn :post/pinned?])]
                                 (set! (.-textContent btn) (if now-pinned? "\u2605" "\u2606"))
                                 (set! (.. btn -style -color) (if now-pinned? "#f59e0b" "#666")))
                               (schedule-save!))))
        (.insertBefore target-container btn overflow-btn)))))

(defn scan-post! [post-el]
  (let [raw (scrape-post-element post-el)]
    (when (and (activity-urn? (:raw/urn raw))
               (not (promoted-post? raw))
               (not ((:nav/seen-urns @!state) (:raw/urn raw))))
      (swap! !state update :nav/seen-urns conj (:raw/urn raw))
      (inject-pin-button! post-el (:raw/urn raw)))))

(defn scan-visible-posts! []
  (doseq [post-el (qa-doc :sel/post-container)]
    (scan-post! post-el)))

(defn process-mutations! []
  (try
    (scan-visible-posts!)
    (ensure-nav-button!)
    (catch :default err
      (js/console.error "[epupp:tracker] Mutation processing error:" err))))

(defn schedule-mutation-processing! []
  (when-let [raf (:resource/mutation-raf @!resources)]
    (js/cancelAnimationFrame raf))
  (when-let [timeout (:resource/mutation-timeout @!resources)]
    (js/clearTimeout timeout))
  (swap! !resources assoc :resource/mutation-raf
         (js/requestAnimationFrame
          (fn []
            (swap! !resources assoc :resource/mutation-timeout
                   (js/setTimeout process-mutations! 150))))))

(defn disconnect-feed-observer! []
  (when-let [observer (:resource/feed-observer @!resources)]
    (.disconnect observer))
  (when-let [raf (:resource/mutation-raf @!resources)]
    (js/cancelAnimationFrame raf))
  (when-let [timeout (:resource/mutation-timeout @!resources)]
    (js/clearTimeout timeout))
  (swap! !resources assoc
         :resource/feed-observer nil
         :resource/mutation-raf nil
         :resource/mutation-timeout nil))

(defn create-feed-observer! []
  (disconnect-feed-observer!)
  (let [observer (js/MutationObserver.
                  (fn [_mutations _observer]
                    (schedule-mutation-processing!)))]
    (.observe observer js/document.body
              #js {:childList true :subtree true})
    (swap! !resources assoc :resource/feed-observer observer)
    (js/console.log "[epupp:tracker] Feed observer started")))

(def engagement-labels
  {:engaged/liked "Liked"
   :engaged/commented "Commented"
   :engaged/reposted "Reposted"
   :engaged/expanded "Expanded"
   :engaged/pinned "Pinned"})

(def media-labels
  {:media/text "Text"
   :media/image "Image"
   :media/video "Video"
   :media/article "Article"
   :media/document "Document"
   :media/carousel "Carousel"
   :media/poll "Poll"})

(defn format-relative-time [iso-str now-ms]
  (let [then (.getTime (js/Date. iso-str))
        diff-ms (- now-ms then)
        minutes (Math/floor (/ diff-ms 60000))
        hours (Math/floor (/ minutes 60))
        days (Math/floor (/ hours 24))]
    (cond
      (< minutes 1) "just now"
      (< minutes 60) (str minutes "m ago")
      (< hours 24) (str hours "h ago")
      (= days 1) "yesterday"
      :else (str days "d ago"))))

(defn matches-search? [post search-text]
  (let [lower (string/lower-case search-text)]
    (or (string/includes? (string/lower-case (or (:post/author-name post) "")) lower)
        (string/includes? (string/lower-case (or (:post/text-preview post) "")) lower)
        (string/includes? (string/lower-case (or (:post/author-headline post) "")) lower))))

(defn filter-posts [posts {:keys [ui/search-text ui/filter-engagement]}]
  (cond->> (vals posts)
    (and search-text (seq search-text))
    (filter #(matches-search? % search-text))
    filter-engagement
    (filter #(contains? (:post/engagements %) filter-engagement))))

(defn sort-posts [posts sort-by-key sort-order]
  (let [key-fn (case sort-by-key
                 :sort/last-engaged :post/last-engaged
                 :sort/first-seen :post/first-seen
                 :sort/author :post/author-name
                 :post/last-engaged)
        sorted (sort-by key-fn posts)]
    (if (= sort-order :desc)
      (reverse sorted)
      sorted)))

(defn initials [author-name]
  (when author-name
    (->> (string/split author-name #" ")
         (take 2)
         (map #(subs % 0 1))
         (string/join ""))))

(defn extract-domain [url]
  (when (and url (string? url))
    (try
      (.-hostname (js/URL. url))
      (catch :default _ nil))))

(defn media-thumbnail [{:keys [post/media-type post/media-image-url]}]
  (case media-type
    :media/image
    (when media-image-url
      [:img {:src media-image-url
             :style {:width "100%" :max-height "160px" :border-radius "6px"
                     :object-fit "cover" :margin-bottom "6px"}}])
    :media/video
    (when media-image-url
      [:div {:style {:position "relative" :width "100%" :max-height "160px"
                     :overflow "hidden" :border-radius "6px" :margin-bottom "6px"}}
       [:img {:src media-image-url
              :style {:width "100%" :max-height "160px" :object-fit "cover"}}]
       [:div {:style {:position "absolute" :inset "0" :display "flex"
                      :align-items "center" :justify-content "center"
                      :background "rgba(0,0,0,0.3)"}}
        [:span {:style {:color "white" :font-size "28px"}} "\u25B6"]]])
    :media/document
    [:div {:style {:width "100%" :height "48px" :border-radius "6px"
                   :background "#f0f0f0" :display "flex" :align-items "center"
                   :justify-content "center" :margin-bottom "6px"}}
     [:span {:style {:font-size "18px" :color "#666"}} "\uD83D\uDCC4 Document"]]
    :media/carousel
    [:div {:style {:width "100%" :height "48px" :border-radius "6px"
                   :background "#f0f0f0" :display "flex" :align-items "center"
                   :justify-content "center" :margin-bottom "6px"}}
     [:span {:style {:font-size "18px" :color "#666"}} "\uD83C\uDFA0 Carousel"]]
    nil))

(defn article-mini-card [{:keys [post/article-title post/article-url
                                  post/article-image-url post/media-image-url]}]
  (let [domain (extract-domain article-url)
        img-url (or article-image-url media-image-url)]
    [:div {:style {:display "flex" :gap "8px" :padding "8px"
                   :background "#f8f9fa" :border-radius "6px"
                   :border "1px solid #e8e8e8" :margin-bottom "6px"}}
     (when img-url
       [:img {:src img-url
              :style {:width "48px" :height "48px" :border-radius "4px"
                      :object-fit "cover" :flex-shrink "0"}}])
     [:div {:style {:flex "1" :min-width "0"}}
      (when article-title
        [:div {:style {:font-size "12px" :font-weight "600" :color "#333"
                       :white-space "nowrap" :overflow "hidden"
                       :text-overflow "ellipsis"}}
         article-title])
      (when domain
        [:div {:style {:font-size "10px" :color "#999" :margin-top "2px"}}
         domain])]]))

(defn post-card [{:as post :keys [post/urn post/author-name post/author-avatar-url
                                  post/author-headline post/text-preview post/media-type
                                  post/engagements post/pinned? post/last-engaged]}]
  [:div {:replicant/key urn
         :style {:padding "12px" :border-bottom "1px solid #e0e0e0"
                 :background (if pinned? "#fffde7" "white")
                 :cursor "pointer"}
         :on {:click (fn [_e]
                       (js/window.open (str "https://www.linkedin.com/feed/update/" urn "/") "_blank"))}}
   ;; Author row
   [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-bottom "6px"}}
    (if author-avatar-url
      [:img {:src author-avatar-url
             :style {:width "32px" :height "32px" :border-radius "50%"}}]
      [:div {:style {:width "32px" :height "32px" :border-radius "50%"
                     :background "#0a66c2" :color "white" :display "flex"
                     :align-items "center" :justify-content "center"
                     :font-size "12px" :font-weight "bold"}}
       (initials author-name)])
    [:div {:style {:flex "1" :min-width "0"}}
     [:div {:style {:font-weight "600" :font-size "13px" :white-space "nowrap"
                    :overflow "hidden" :text-overflow "ellipsis"}}
      (or author-name "Unknown")]
     [:div {:style {:font-size "11px" :color "#666" :white-space "nowrap"
                    :overflow "hidden" :text-overflow "ellipsis"}}
      (or author-headline "")]]
    (when pinned?
      [:span {:style {:color "#f59e0b" :font-size "16px"}} "\u2605"])
    [:span {:style {:font-size "11px" :color "#999" :white-space "nowrap"}}
     (format-relative-time last-engaged (js/Date.now))]]
   ;; Text preview
   (when text-preview
     [:div {:style {:font-size "12px" :color "#333" :margin-bottom "6px"
                    :display "-webkit-box" :-webkit-line-clamp "2"
                    :-webkit-box-orient "vertical" :overflow "hidden"}}
      text-preview])
   ;; Media display (feed-like)
   (media-thumbnail post)
   ;; Article mini-card
   (when (= media-type :media/article)
     (article-mini-card post))
   ;; Badges
   [:div {:style {:display "flex" :gap "4px" :flex-wrap "wrap"}}
    (when media-type
      [:span {:style {:background "#e3f2fd" :color "#1565c0" :padding "2px 6px"
                      :border-radius "4px" :font-size "10px"}}
       (get media-labels media-type "?")])
    (for [eng (sort (map name engagements))]
      [:span {:replicant/key eng
              :style {:background "#f3e5f5" :color "#7b1fa2" :padding "2px 6px"
                      :border-radius "4px" :font-size "10px"}}
       eng])]])

(defn panel-view [state]
  (let [{:keys [tracker/posts ui/search-text ui/filter-engagement
                ui/sort-by ui/sort-order]} state
        filtered (filter-posts posts state)
        sorted (sort-posts filtered sort-by sort-order)
        post-count (count posts)]
    [:div {:id "epupp-tracker-panel"
           :style {:position "fixed" :top "52px" :right "0" :bottom "0"
                   :width "380px" :background "white" :z-index "9999"
                   :box-shadow "-2px 0 12px rgba(0,0,0,0.15)"
                   :display "flex" :flex-direction "column"
                   :font-family "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"}}
     ;; Header
     [:div {:style {:padding "12px 16px" :border-bottom "1px solid #e0e0e0"
                    :display "flex" :justify-content "space-between" :align-items "center"}}
      [:span {:style {:font-weight "700" :font-size "16px"}}
       (str "Tracked Posts (" post-count ")")]
      [:button {:style {:background "none" :border "none" :cursor "pointer"
                        :font-size "20px" :color "#666" :padding "0 4px"}
                :on {:click (fn [_] (swap! !state assoc :ui/panel-open? false))}}
       "\u00d7"]]
     ;; Search
     [:div {:style {:padding "8px 16px"}}
      [:input {:type "text"
               :placeholder "Search posts..."
               :value (or search-text "")
               :style {:width "100%" :padding "6px 10px" :border "1px solid #ccc"
                       :border-radius "4px" :font-size "13px" :box-sizing "border-box"}
               :on {:input (fn [e] (swap! !state assoc :ui/search-text (.. e -target -value)))}}]]
     ;; Filter chips
     [:div {:style {:padding "4px 16px" :display "flex" :gap "4px" :flex-wrap "wrap"}}
      (for [[k label] engagement-labels]
        [:button {:replicant/key (name k)
                  :style {:padding "3px 8px" :border-radius "12px" :font-size "11px"
                          :cursor "pointer" :border "1px solid #ccc"
                          :background (if (= filter-engagement k) "#0a66c2" "white")
                          :color (if (= filter-engagement k) "white" "#333")}
                  :on {:click (fn [_]
                                (swap! !state assoc :ui/filter-engagement
                                       (when (not= filter-engagement k) k)))}}
         label])]
     ;; Sort controls
     [:div {:style {:padding "4px 16px" :display "flex" :justify-content "space-between"
                    :align-items "center" :font-size "11px" :color "#666"}}
      [:span (str (count sorted) " shown")]
      [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
       [:select {:value (name sort-by)
                 :style {:font-size "11px" :padding "2px 18px 2px 4px" :border "1px solid #ccc"
                         :border-radius "4px" :appearance "auto" :background "white"}
                 :on {:change (fn [e]
                                (swap! !state assoc :ui/sort-by
                                       (keyword "sort" (.. e -target -value))))}}
        [:option {:value "last-engaged"} "Last engaged"]
        [:option {:value "first-seen"} "First seen"]
        [:option {:value "author"} "Author"]]
       [:button {:style {:background "none" :border "none" :cursor "pointer"
                         :font-size "14px"}
                 :on {:click (fn [_]
                               (swap! !state update :ui/sort-order
                                      #(if (= % :desc) :asc :desc)))}}
        (if (= sort-order :desc) "\u25bc" "\u25b2")]]]
     ;; Post list
     [:div {:style {:flex "1" :overflow-y "auto" :overscroll-behavior "contain"}}
      (if (seq sorted)
        (for [post sorted]
          (post-card post))
        [:div {:style {:padding "32px" :text-align "center" :color "#999"}}
         "No tracked posts yet"])]]))

(defn render-panel! []
  (let [container (:resource/panel-container @!resources)]
    (if (:ui/panel-open? @!state)
      (r/render container (panel-view @!state))
      (r/render container nil))))

(defn attach-escape-handler! []
  (attach-listener! js/document "keydown" :resource/keydown-handler
    (fn [e]
      (when (and (= (.-key e) "Escape")
                 (:ui/panel-open? @!state))
        (swap! !state assoc :ui/panel-open? false)))
    {}))

(defn detach-escape-handler! []
  (detach-listener! js/document "keydown" :resource/keydown-handler {}))

(defn attach-click-outside-handler! []
  (attach-listener! js/document "click" :resource/click-outside-handler
    (fn [e]
      (when (:ui/panel-open? @!state)
        (let [panel (js/document.getElementById "epupp-tracker-panel")
              nav-btn (js/document.getElementById "epupp-tracker-nav-btn")]
          (when (and panel
                     (not (.contains panel (.-target e)))
                     (or (nil? nav-btn)
                         (not (.contains nav-btn (.-target e)))))
            (swap! !state assoc :ui/panel-open? false)))))
    {}))

(defn detach-click-outside-handler! []
  (detach-listener! js/document "click" :resource/click-outside-handler {}))

(defn attach-beforeunload-handler! []
  (attach-listener! js/window "beforeunload" :resource/beforeunload-handler
    (fn [_e] (save-state!))
    {}))

(defn detach-beforeunload-handler! []
  (detach-listener! js/window "beforeunload" :resource/beforeunload-handler {}))

(defn on-navigation! []
  (let [current (.-href js/window.location)]
    (when (not= current (:nav/last-url @!state))
      (swap! !state assoc
             :nav/seen-urns #{}
             :nav/last-url current
             :ui/panel-open? false)
      (js/setTimeout
       (fn []
         (scan-visible-posts!)
         (ensure-nav-button!))
       1500))))

(defn start-url-polling! []
  (when-let [old (:resource/url-poll-interval @!resources)]
    (js/clearInterval old))
  (swap! !state assoc :nav/last-url (.-href js/window.location))
  (swap! !resources assoc :resource/url-poll-interval
         (js/setInterval
          (fn []
            (let [current (.-href js/window.location)]
              (when (not= current (:nav/last-url @!state))
                (on-navigation!))))
          2000)))

(defn stop-url-polling! []
  (when-let [interval (:resource/url-poll-interval @!resources)]
    (js/clearInterval interval)
    (swap! !resources assoc :resource/url-poll-interval nil)))

(defn attach-popstate-handler! []
  (attach-listener! js/window "popstate" :resource/popstate-handler-fn
    (fn [_e] (on-navigation!))
    {}))

(defn detach-popstate-handler! []
  (detach-listener! js/window "popstate" :resource/popstate-handler-fn {}))

(defn teardown! []
  (disconnect-feed-observer!)
  (detach-engagement-listener!)
  (detach-escape-handler!)
  (detach-click-outside-handler!)
  (detach-beforeunload-handler!)
  (detach-popstate-handler!)
  (stop-url-polling!)
  (save-state!)
  (when-let [m (:resource/nav-mount @!resources)]
    (r/render m nil))
  (when-let [container (:resource/panel-container @!resources)]
    (r/render container nil))
  :torn-down)

(defn init! []
  (remove-watch !state ::panel-renderer)
  (add-watch !state ::panel-renderer
             (fn [_k _r o n]
               (when (not= o n)
                 (render-panel!)
                 (ensure-nav-button!))))
  (load-state!)
  (create-feed-observer!)
  (attach-engagement-listener!)
  (attach-escape-handler!)
  (attach-click-outside-handler!)
  (attach-beforeunload-handler!)
  (attach-popstate-handler!)
  (start-url-polling!)
  (ensure-nav-button!)
  (js/setTimeout scan-visible-posts! 1000)
  (selector-health-check!)
  (js/console.log "[epupp:tracker] Initialized")
  :initialized)

(init!)
