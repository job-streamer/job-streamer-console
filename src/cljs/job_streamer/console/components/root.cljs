(ns job-streamer.console.components.root
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [fence.core :refer [+++]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [job-streamer.console.routing :as routing]
            [job-streamer.console.api :as api]
            [goog.string :as gstring]
            [goog.fs])
  (:use [cljs.reader :only [read-string]]
        [job-streamer.console.components.jobs :only [jobs-view]]
        [job-streamer.console.components.agents :only [agents-view]]
        [job-streamer.console.components.calendars :only [calendars-view]]
        [job-streamer.console.components.apps :only [apps-view]]
        [job-streamer.console.search :only [search-jobs parse-sort-order]]
        [job-streamer.console.component-helper :only [make-click-outside-fn]]))

(def app-name "default")

(defn export-jobs []
  (api/download (str "/" app-name "/jobs/download?with=notation,schedule,settings")))

(defn export-calendars []
  (api/download "/calendars/download"))

(defn import-edn-jobs [jobs callback message-channel]
  (let [ch (chan)]
    (go-loop [cnt 1]
      (let [jobs (<! ch)
            rest-jobs (not-empty (rest jobs))]
        (when-let [job (first jobs)]
          (api/request (str "/" app-name "/jobs") :POST
                       (select-keys job [:job/name
                                         :job/bpmn-xml-notation
                                         :job/svg-notation
                                         :job/schedule
                                         :job/exclusive?
                                         :job/time-monitor
                                         :job/status-notifications])
                       {:handler (fn [_]
                                   (if rest-jobs
                                     (put! ch rest-jobs)
                                     (callback cnt)))
                        :error-handler (fn [response]
                                         (put! message-channel {:type "error"
                                                                :body (:message response)}))
                        :forbidden-handler (fn [_]
                                             (put! message-channel {:type "error"
                                                                    :body "Cannot import. Unauthorized."}))})
          (recur (inc cnt)))))
    (put! ch jobs)))

(defn import-edn-calendars [calendars callback message-channel]
  (let [ch (chan)]
    (go-loop [cnt 1]
      (let [calendars (<! ch)
            rest-calendars (not-empty (rest calendars))]
        (when-let [calendar (first calendars)]
          (api/request "/calendars" :POST
                       calendar
                       {:handler (fn [_]
                                   (if rest-calendars
                                     (put! ch rest-calendars)
                                     (callback cnt)))
                        :error-handler (fn [response]
                                         (put! message-channel {:type "error"
                                                                :body (:message response)}))
                        :forbidden-handler (fn [_]
                                             (put! message-channel {:type "error"
                                                                    :body "Cannot import. Unauthorized."}))})
          (recur (inc cnt)))))
    (put! ch calendars)))

(defn upload-dialog [el-name upload-fn callback-fn]
  [:input
   {:type "file"
    :name el-name
    :id el-name
    :style {:display "none"}
    :value ""
    :on-change
    (fn [e]
      ;IE cannot refresh value so on-change will not be called after second time.
      (when-not (= "" (-> js/document (.getElementById  el-name) (. -value)))
      (let [file (aget (.. e -target -files) 0)
            reader (js/FileReader.)]
        (set! (.-onload reader)
              #(let [result (.. % -target -result)]
                 (upload-fn file result callback-fn)))
        (.readAsText reader file))))}])

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

(defcomponent right-menu-view [app owner {:keys [header-channel jobs-channel message-channel calendars-channel]}]
  (init-state [_]
    :configure-opened? false
    :export-opened? false
    :import-opened? false
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

  (render-state [_ {:keys [control-bus-not-found? configure-opened? open-version-dialog
                           export-opened? import-opened?]}]
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
                              (om/transact! app #(assoc %
                                                   :query (.-value (.getElementById js/document "job-query"))))
                              (search-jobs app {:q (.-value (.getElementById js/document "job-query")) :sort-by (-> app :job-sort-order parse-sort-order)} message-channel) false)}
          [:div.ui.icon.transparent.inverted.input
           [:input#job-query {:type "text"
                              :placeholder "Search..."}]]
          [:i.search.icon {:on-click (fn [e]
                                        (.preventDefault e)
                                        (search-jobs app {:q (.-value (.getElementById js/document "job-query")) :sort-by (-> app :job-sort-order parse-sort-order)} message-channel) false)}]]]
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
          [:div.ui.dropdown.item
           {:on-mouse-over (fn [_]
                             (om/set-state! owner :export-opened? true))
            :on-mouse-out  (fn [_]
                             (om/set-state! owner :export-opened? false))}
           [:i.download.icon]
           "Export"
           [:div.menu.left.transition
            {:class (if export-opened? "visible" "hidden")}
            [:a.item {:on-click (fn [e]
                                  (.preventDefault e)
                                  (om/set-state! owner :configure-opened? false)
                                  (export-jobs)
                                  )}
             "Export jobs"]
            [:a.item {:on-click (fn [e]
                                  (.preventDefault e)
                                  (om/set-state! owner :configure-opened? false)
                                  (export-calendars))}
             "Export calendars"]]]

          [:div.ui.dropdown.item
           {:on-mouse-over (fn [_]
                             (om/set-state! owner :import-opened? true))
            :on-mouse-out  (fn [_]
                             (om/set-state! owner :import-opened? false))}
           [:i.upload.icon] "Import"
           [:div.menu.left.transition
            {:class (if import-opened? "visible" "hidden")}
            [:a.item {:on-click (fn [e]
                                  (.. (om/get-node owner)
                                      (querySelector "[name='file-jobs']")
                                      click)
                                  (om/set-state! owner :configure-opened? false))}
             (upload-dialog
              "file-jobs"
              (fn [file result callback-fn]
                (cond
                   (gstring/endsWith (.-name file) ".edn")
                   (import-edn-jobs (read-string result) callback-fn message-channel)

                   :else
                   (put! message-channel {:type "error"
                                          :body "Cannot import. Unsupported file type."})))
              (fn [cnt]
                (put! jobs-channel [:refresh-jobs true])
                (put! message-channel {:type "success"
                                       :body (str "Imported " cnt " jobs!")})))
             "Import jobs"]
            [:a.item {:on-click (fn [e]
                                  (.. (om/get-node owner)
                                      (querySelector "[name='file-calendars']")
                                      click)
                                  (om/set-state! owner :configure-opened? false))}
             (upload-dialog
              "file-calendars"
              (fn [file result callback-fn]
                (import-edn-calendars (read-string result) callback-fn message-channel))
              (fn [cnt]
                (put! message-channel {:type "success"
                                       :body (str "Imported " cnt " calendars!")})
                (put! calendars-channel [:fetch-calendar true])))
             "Import calendars"]]]

          [:a.item {:on-click (fn [e]
                                (.preventDefault e)
                                (om/set-state! owner :configure-opened? false)
                                (set! (.-href js/location) "#/app/default"))}
            [:i.browser.icon] "Upload batch components"]
          [:a.item {:on-click (fn[e]
                                (put! header-channel [:version-dialog true]))}
            [:i.circle.help.icon] "version"]
            [:a.item {:on-click #(api/request (api/url-for "/auth") :DELETE
                                              {:handler (fn [response]
                                                          (set! (.-href js/location) "/login"))})}
            [:i.sign.out.icon] "Logout"]]]
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
                      (om/set-state! owner :configure-opened? false)
                      (om/set-state! owner :import-opened? false)
                      (om/set-state! owner :export-opened? false)))))
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
    {:header-channel    (chan)
     :jobs-channel      (chan)
     :calendars-channel (chan)
     :message-channel   (chan)
     :message nil})
  (will-mount [_]
    (routing/init app owner)
    (go-loop []
      (when-let [msg (<! (om/get-state owner :message-channel))]
        (om/set-state! owner :message msg)
        (go (<! (timeout 5000))
          (om/set-state! owner :message nil))
        (recur))))
  (render-state [_ {:keys [header-channel jobs-channel
                           calendars-channel message-channel
                           message]}]
    (html
     [:div.full.height
      (if-let [system-error (:system-error app)]
        (om/build system-error-view app {:react-key "error"})
        (list
         [:div.ui.fixed.inverted.teal.menu {:key "div-1"}
          [:div.header.item [:a {:href "#/" } [:img.ui.image {:alt "JobStreamer" :src "img/logo.png"}]]]
          (om/build right-menu-view app {:opts {:header-channel  header-channel
                                                :jobs-channel    jobs-channel
                                                :calendars-channel calendars-channel
                                                :message-channel message-channel
                                                :react-key "menu"}})]
         [:div.main.grid.content.full.height {:key "div-2"}
          [:div#message.ui.floating.message
           {:style {:float "right"}
            :class (if message
                     (str (:type message) " visible")
                     "hidden")}
           (:body message)]
          (case (first (:mode app))
            :jobs (om/build jobs-view app {:init-state {:mode (second (:mode app))}
                                           :opts {:header-channel header-channel
                                                  :jobs-channel jobs-channel
                                                  :message-channel message-channel}
                                           :react-key "jobs"})
            :agents (om/build agents-view app)
            :calendars (om/build calendars-view app {:init-state {:mode (second (:mode app))}
                                           :opts {:calendars-channel calendars-channel
                                                  :react-key "calendar"}})
            :apps (om/build apps-view app))]))])))

(defcomponent login-view [app owner]
  (init-state [_]
    {:errors nil
     :username ""
     :password ""})
  (render-state [_ {:keys [username password errors]}]
    (html
      [:div.full.height
        [:div.ui.fixed.inverted.teal.menu
          [:div.header.item [:img.ui.image {:alt "JobStreamer" :src "/img/logo.png"}]]]
        [:div.main.grid.content.full.height
          [:div.ui.middle.aligned.center.aligned.login.grid
           [:div.column
            [:h2.ui.header
             [:div.content
              [:img.ui.image {:src "/img/logo.png"}]]]
            [:form.ui.large.login.form
             (merge {:on-submit (fn [e]
                                  (.preventDefault e)
                                  (api/request (api/url-for "/auth") :POST
                                                    {:user/id username :user/password password}
                                                    {:handler #(set! (.-href js/location) "/")
                                                     :error-handler #(om/set-state! owner :errors (or (:messages %)
                                                                                                      ["A Control bus is NOT found."]))
                                                     :unauthorized-handler #(om/set-state! owner :errors (:messages %))}))}
                    (when errors {:class "error"}))
             [:div.ui.stacked.segment
              [:div.ui.error.message
               (map #(vec [:p %]) errors)]
              [:div.field
               [:div.ui.left.icon.input
                [:i.user.icon]
                [:input {:type "text" :name "username" :id "username" :placeholder "User name" :value username
                         :on-change (fn [e]
                                      (let [username (.. js/document (getElementById "username") -value)]
                                        (om/set-state! owner :username username)))}]]]
              [:div.field
               [:div.ui.left.icon.input
                [:i.lock.icon]
                [:input {:type "password" :name "password" :id "password" :placeholder "Password" :value password
                         :on-change (fn [e]
                                      (let [password (.. js/document (getElementById "password") -value)]
                                        (om/set-state! owner :password password)))}]]]
              [:button.ui.fluid.large.teal.submit.button{:type "submit"} "Login"]]]]]]])))
