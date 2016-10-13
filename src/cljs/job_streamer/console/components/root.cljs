(ns job-streamer.console.components.root
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [fence.core :refer [+++]])
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
        [job-streamer.console.components.apps :only [apps-view]]
        [job-streamer.console.search :only [search-jobs]]
        [job-streamer.console.component-helper :only [make-click-outside-fn]]))

(def app-name "default")

(defn export-jobs []
  (api/request (str "/" app-name "/jobs?with=notation,shcedule,settings")
               {:handler (fn [response]
                           (let [blob (goog.fs/getBlobWithProperties (array (pr-str (:results response))) "application/edn")
                                 anchor (. js/document createElement "a")
                                 url (or (. js/window -URL) (. js/window -webkitURL))]
                             (+++ (if (.. js/window -navigator -msSaveBlob)
                               ;for IE
                               (.msSaveBlob (. js/window -navigator)  blob "jobs.edn")
                               ;for Firefox and Chorome
                                 (do
                                   (.setAttribute anchor "href" (.createObjectURL url blob))
                                   (.setAttribute anchor "download" "jobs.edn")
                                   (.appendChild (. js/document -body) anchor)
                                   (.click anchor)
                                   (.removeChild (. js/document -body) anchor))))))}))

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

(defcomponent version-dialog [app owner {:keys [header-channel]}]
  (will-mount [_]
    (let [uri (goog.Uri. (.-href js/location))
              port (.getPort uri)]
          (api/request (str (.getScheme uri) "://" (.getDomain uri) (when port (str ":" port)) "/version")
                       {:handler (fn [response]
                                   (om/transact! app :version
                                                 #(assoc %
                                                    :console-version response)))}))
        (api/request "/version"
                     {:handler (fn [response]
                                 (om/transact! app :version
                                               #(assoc %
                                                       :control-bus-version response)))}))

  (render [_]
   (let [{{:keys [console-version control-bus-version]} :version}  app]
    (html
     [:div.ui.dimmer.modals.page.transition.visible.active
      [:div.ui.modal.scrolling.transition.visible.active
       [:i.close.icon {:on-click (fn [e] (put! header-channel [:close-dialog true]))}]
       [:div.header "version"]
       [:div.content
        [:p "console version: " console-version]
        [:p "control-bus verion: " control-bus-version]
        [:div.ui.center.aligned.column.grid
          [:button.ui.black.button
           {:type "button"
            :on-click (fn [e] (put! header-channel [:close-dialog true]))} "Close"]]]]]))))

(defcomponent right-menu-view [app owner {:keys [header-channel jobs-channel]}]
  (init-state [_]
    :configure-opened? false
    :click-outside-fn nil)

  (will-mount [_]
    (go-loop []
      (let [[cmd msg] (<! header-channel)]
        (case cmd
          :refresh-stats (api/request (str "/" app-name "/stats")
                     {:handler (fn [response]
                                 (om/transact! app :stats
                                               #(assoc %
                                                       :agents-count (:agents response)
                                                       :jobs-count (:jobs response))))
                      :error-handler
                      {:http-error (fn [res]
                                     (om/update! app :system-error "error"))}})
          :version-dialog  (om/set-state! owner :open-version-dialog true)
          :close-dialog (om/set-state! owner :open-version-dialog false))

             (recur)))

    (put! header-channel [:refresh-stats true])

    (when-let [on-click-outside (om/get-state owner :click-outside-fn)]
      (.removeEventListener js/document "mousedown" on-click-outside)))

  (render-state [_ {:keys [control-bus-not-found? configure-opened? open-version-dialog]}]
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
         [:form {:on-submit (fn [e]
                              (.preventDefault e)
                              (search-jobs app {:q (.-value (.getElementById js/document "job-query"))}) false)}
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
                                                              (put! jobs-channel [:refresh-jobs true]))]
                                            (cond
                                              (gstring/endsWith (.-name file) ".xml")
                                              (import-xml-job result callback-fn)

                                              (gstring/endsWith (.-name file) ".edn")
                                              (import-edn-jobs (read-string result) callback-fn)

                                              :else
                                              (throw (js/Error. "Unsupported file type")))))
                                   (.readAsText reader file)))}]]
          [:a.item {:on-click (fn [e]
                                (.preventDefault e)
                                (om/set-state! owner :configure-opened? false)
                                (set! (.-href js/location) "#/app/default"))}
           [:i.browser.icon] "Upload batch components"]
          [:a.item {:on-click (fn[e]
                                (put! header-channel [:version-dialog true]))}
          [:i.circle.help.icon] "version"]]]
        (when open-version-dialog
          (om/build version-dialog app
                    {:opts {:header-channel header-channel}
                     :react-key "version-dialog"}))])))

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
    {:header-channel (chan)
     :jobs-channel  (chan)
     :calendars-channel  (chan)})
  (will-mount [_]
    (routing/init app owner))
  (render-state [_ {:keys [header-channel jobs-channel calendars-channel]}]
    (html
     [:div.full.height
      (if-let [system-error (:system-error app)]
        (om/build system-error-view app {:react-key "error"})
        (list
         [:div.ui.fixed.inverted.teal.menu
          [:div.header.item [:a {:href "#/" } [:img.ui.image {:alt "JobStreamer" :src "img/logo.png"}]]]
          (om/build right-menu-view app {:opts {:header-channel header-channel
                                                :jobs-channel jobs-channel
                                                :react-key "menu"}})]
         [:div.main.grid.content.full.height
          (case (first (:mode app))
            :jobs (om/build jobs-view app {:init-state {:mode (second (:mode app))}
                                           :opts {:header-channel header-channel
                                                  :jobs-channel jobs-channel}
                                           :react-key "jobs"})
            :agents (om/build agents-view app)
            :calendars (om/build calendars-view app {:init-state {:mode (second (:mode app))}
                                           :opts {:calendars-channel calendars-channel
                                                  :react-key "calendar"}})
            :apps (om/build apps-view app))]))])))
