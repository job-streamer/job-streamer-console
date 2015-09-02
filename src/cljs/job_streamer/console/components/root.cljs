(ns job-streamer.console.components.root
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan]]
            [job-streamer.console.routing :as routing]
            [job-streamer.console.api :as api]
            [goog.string :as gstring]
            [goog.fs])
  (:use [cljs.reader :only [read-string]]
        [job-streamer.console.components.jobs :only [jobs-view]]
        [job-streamer.console.components.agents :only [agents-view]]
        [job-streamer.console.components.calendars :only [calendars-view]]
        [job-streamer.console.search :only [search-jobs]]
        [job-streamer.console.component-helper :only [make-click-outside-fn]]))

(def app-name "default")

(defn export-jobs []
  (api/request (str "/" app-name "/jobs?with=notation,shcedule,settings")
               {:handler (fn [response]
                           (let [blob (goog.fs/getBlobWithProperties (array (pr-str (:results response))) "application/edn")
                                 click-event (. js/document createEvent "HTMLEvents")
                                 anchor (. js/document createElement "a")]
                             (.initEvent click-event "click")
                             (doto anchor
                               (.setAttribute "href" (goog.fs/createObjectUrl blob))
                               (.setAttribute "download" "jobs.edn"))
                             (.dispatchEvent anchor click-event)))}))

(defn import-xml-job [jobxml callback]
  (api/request (str "/" app-name "/jobs") :POST jobxml
               {:format :xml
                :handler callback}))

(defn import-edn-jobs [jobs callback]
  (let [ch (chan)]
    (go-loop []
      (let [jobs (<! ch)
            rest-jobs (not-empty (rest jobs))]
        (if-not (empty? jobs)
          (api/request (str "/" app-name "/jobs") :POST (:job/edn-notation (first jobs))
                       {:handler (fn [_]
                                   (when rest-jobs
                                     (put! ch rest-jobs)))}))
        (if rest-jobs
          (recur)
          (callback))))
    (put! ch jobs)))

(defcomponent right-menu-view [app owner {:keys [stats-channel jobs-channel]}]
  (init-state [_]
    :configure-opened? false
    :click-outside-fn nil)

  (will-mount [_]
    (go-loop []
      (let [_ (<! stats-channel)]
        (api/request (str "/" app-name "/stats")
                     {:handler (fn [response]
                                 (om/transact! app :stats
                                               #(assoc %
                                                       :agents-count (:agents response)
                                                       :jobs-count (:jobs response))))
                      :error-handler
                      {:http-error (fn [res]
                                     (om/update! app :system-error "error"))}})
        (recur)))

    (put! stats-channel true)

    (when-let [on-click-outside (om/get-state owner :click-outside-fn)]
      (.removeEventListener js/document "mousedown" on-click-outside)))
  
  (render-state [_ {:keys [control-bus-not-found? configure-opened?]}]
    (let [{{:keys [agents-count jobs-count]} :stats}  app]
      (html 
       [:div.right.menu
        [:div#agent-stats.item
         (when (= (first (:mode app)) :agents) {:class "active"})
         [:a.ui.tiny.horizontal.statistics
          {:href "#/agents"}
          [:div.ui.inverted.statistic
           [:div.value agents-count]
           [:div.label (str "agent" (when (> agents-count 1) "s"))]]]]
        [:div#job-stats.item
         (when (= (first (:mode app)) :jobs) {:class "active"})
         [:a.ui.tiny.horizontal.statistics
          {:href "#/"}
          [:div.ui.inverted.statistic
           [:div.value jobs-count]
           [:div.label (str "job" (when (> jobs-count 1) "s"))]]]]
        [:div#job-search.item
         [:form {:on-submit (fn [e] (search-jobs app {:q (.-value (.getElementById js/document "job-query"))}) false)}
          [:div.ui.icon.transparent.inverted.input
           [:input#job-query {:type "text"}]
           [:i.search.icon]]]]
        [:div.ui.dropdown.item
         [:button.ui.basic.icon.inverted.button
          {:on-click (fn [_]
                       (om/set-state! owner :configure-opened? (not configure-opened?)))}
          [:i.configure.icon]]
         [:div.menu.transition {:class (if configure-opened? "visible" "hide")}
          [:a.item {:on-click (fn [e]
                                (.preventDefault e)
                                (om/set-state! owner :configure-opened? false)
                                (set! (.-href js/location) "#/calendars"))}
           [:i.calendar.icon] "Calendar"]
          [:a.item {:on-click (fn [e]
                                (.preventDefault e)
                                (om/set-state! owner :configure-opened? false)
                                (export-jobs))}
           [:i.download.icon] "Export jobs"]
          [:a.item {:on-click (fn [e]
                                (.. (om/get-node owner) (querySelector "[name='file']") click)
                                (om/set-state! owner :configure-opened? false))}
           [:i.upload.icon] "Import jobs"
           [:input {:type "file" :name "file" :style {:display "none"}
                    :on-change (fn [e]
                                 (let [file (aget (.. e -target -files) 0)
                                       reader (js/FileReader.)]
                                   (set! (.-onload reader)
                                         #(let [result (.. % -target -result)
                                                callback-fn (fn []
                                                              (println jobs-channel)
                                                              (put! jobs-channel [:refresh-jobs true]))]
                                            (cond
                                              (gstring/endsWith (.-name file) ".xml")
                                              (import-xml-job result callback-fn)

                                              (gstring/endsWith (.-name file) ".edn")
                                              (import-edn-jobs (read-string result) callback-fn)
                                              
                                              :else
                                              (throw (js/Error. "Unsupported file type")))))
                                   (.readAsText reader file)))}]]]]])))

  (did-mount [_]
    (when-not (om/get-state owner :click-outside-fn)
      (om/set-state! owner :click-outside-fn
                   (make-click-outside-fn
                    (.. (om/get-node owner) (querySelector "div.ui.dropdown.item"))
                    (fn [_]
                      (om/set-state! owner :configure-opened? false)))))
    (.addEventListener js/document "mousedown"
                       (om/get-state owner :click-outside-fn))))

(defcomponent system-error-view [app owner]
  (render [_]
    (html
     [:div.ui.dimmer.modals.transition.visible.active
      [:div.ui.basic.modal.transition.visible.active {:style {:margin-top "-142.5px;"}}
       [:div.header "A Control bus is NOT found."]
       [:div.content
        [:div.image [:i.announcement.icon]]
        [:div.description [:p "Run a control bus first and reload this page."]]]]])))

(defcomponent root-view [app owner]
  (init-state [_]
    {:stats-channel (chan)
     :jobs-channel  (chan)})
  (will-mount [_]
    (routing/init app owner))
  (render-state [_ {:keys [stats-channel jobs-channel]}]
    (html
     [:div.full.height
      (if-let [system-error (:system-error app)]
        (om/build system-error-view app)
        (list
         [:div.ui.fixed.inverted.teal.menu
          [:div.header.item [:img.ui.image {:alt "JobStreamer" :src "img/logo.png"}]]
          (om/build right-menu-view app {:opts {:stats-channel stats-channel
                                                :jobs-channel jobs-channel}})]
         [:div.main.grid.content.full.height
          (case (first (:mode app))
            :jobs (om/build jobs-view app {:init-state {:mode (second (:mode app))}
                                           :opts {:stats-channel stats-channel
                                                  :jobs-channel jobs-channel}})
            :agents (om/build agents-view app)
            :calendars (om/build calendars-view app))]))])))
