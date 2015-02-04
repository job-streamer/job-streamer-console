(ns job-streamer.console.jobs
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.ui.Component]
            (job-streamer.console.format :as fmt))
  (:use [cljs.reader :only [read-string]]
        (job-streamer.console.agents :only [agents-view])
        (job-streamer.console.timeline :only [timeline-view])
        (job-streamer.console.blocks :only [job-edit-view job-detail-view])
        (job-streamer.console.execution :only [execution-view]))
  (:import [goog.net EventType]
           [goog.events KeyCodes]))

(enable-console-print!)

(def control-bus-url "http://localhost:45102")
(def app-state (atom {:page :jobs
                      :query nil
                      :jobs []
                      :agents []}))

(defn execute-job [job-id]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]))
    (.send xhrio (str control-bus-url "/job/" job-id "/executions") "post")))

(defn search-jobs [app job-query]
  (let [xhrio (net/xhr-connection)]
      (events/listen xhrio EventType.SUCCESS
                     (fn [e]
                       (om/update! app :jobs
                                   (read-string (.getResponseText xhrio)))))
      (.send xhrio (str control-bus-url "/jobs?q=" (js/encodeURIComponent job-query)) "get")))

(defn search-execution [latest-execution job-id execution-id]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]
                     (let [steps (-> (.getResponseText xhrio)
                                    (read-string)
                                    :job-execution/step-executions)]
                       (om/transact! latest-execution
                                     #(assoc % :job-execution/step-executions steps)))))
    (.send xhrio (str control-bus-url "/job/" job-id "/execution/" execution-id))))

(defcomponent job-list-view [app owner]
  (will-mount [_]
    (search-jobs app ""))
  (render-state [_ {:keys [comm]}]
    (html (if (empty? (:jobs app))
             [:div.ui.grid
              [:div.ui.one.column.row
               [:div.column
                [:div.ui.icon.message
                 [:i.child.icon]
                 [:div.content
                  [:div.header "Let's create a job!"]
                  [:p [:button.ui.primary.button
                       {:type "button"
                        :on-click (fn [e]
                                    (put! comm [:edit nil]))}
                       [:i.plus.icon] "Create the first job"]]]]]]]
             [:div.ui.grid
              [:div.ui.two.column.row
               [:div.column
                [:button.ui.button
                 {:type "button"
                  :on-click (fn [e]
                              (put! comm [:edit nil]))}
                 [:i.plus.icon] "New"]]
               [:div.ui.right.aligned.column
                [:button.ui.circular.icon.button
                 {:type "button"
                  :on-click (fn [e]
                              (search-jobs app ""))}
                 [:i.refresh.icon]]]]
              [:div.row
               [:div.column
                [:table.ui.table
                 [:thead
                  [:tr
                   [:th {:rowSpan 2} "Job name"]
                   [:th {:colSpan 3} "Last execution"]
                   [:th "Next execution"]
                   [:th {:rowSpan 2} "Operations"]]
                  [:tr
                   [:th "Started at"]
                   [:th "Duration"]
                   [:th "Status"]
                   [:th "Start"]]]
                 [:tbody
                  (apply concat
                         (for [{job-id :job/id :as job} (:jobs app)]
                           [[:tr
                             [:td 
                              [:a {:on-click (fn [e]
                                               (put! comm [:show job-id]))} job-id]]
                             (if-let [latest-execution (:job/latest-execution job)]
                               (if (= (get-in latest-execution [:job-execution/batch-status :db/ident]) :batch-status/registered)
                                 [:td.center.aligned {:colSpan 3} "Wait for an execution..."]
                                 (let [start (:job-execution/start-time latest-execution)
                                       end (:job-execution/end-time  latest-execution)]
                                   (list
                                    [:td (when start
                                           (let [id (:db/id latest-execution)]
                                             [:a {:on-click (fn [e] (search-execution latest-execution job-id id))}
                                              (fmt/date-medium start)]))]
                                    [:td (fmt/duration-between start end)]
                                    (let [status (name (get-in latest-execution [:job-execution/batch-status :db/ident]))]
                                      [:td {:class (condp = status
                                                     "completed" "positive"
                                                     "failed" "negative"
                                                     "")} 
                                     status]))))
                               [:td.center.aligned {:colSpan 3} "No executions"])
                             [:td
                              (if-let [next-execution (:job/next-execution job)]
                                (fmt/date-medium (:job-execution/start-time next-execution))
                                "-")]
                             [:td (if (some #{:batch-status/registered :batch-status/starting :batch-status/started :batch-status/stopping}
                                            [(get-in job [:job/latest-execution :job-execution/batch-status :db/ident])])
                                    [:div.ui.circular.icon.orange.button
                                     [:i.setting.loading.icon]]
                                    [:button.ui.circular.icon.green.button
                                     {:on-click (fn [e]
                                                  (om/update! job :job/latest-execution {:job-execution/batch-status {:db/ident :batch-status/registered}})
                                                  (execute-job job-id))}
                                     [:i.play.icon]])]]
                            (when-let [step-executions (not-empty (get-in job [:job/latest-execution :job-execution/step-executions]))]
                              [:tr
                               [:td {:colSpan 8}
                                (om/build execution-view step-executions)]])]))]]]]]))))


(defcomponent jobs-view [app owner]
  (init-state [_]
    {:mode :list})
  (will-mount [_]
    (let [comm (chan)]
      (om/set-state! owner :comm comm)
      (go-loop []
        (let [[type value] (<! comm)]
          (case type
            :edit (om/update-state!
                   owner
                   (fn [state]
                     (-> state
                         (assoc :mode :edit)
                         (assoc :job-id value))))
            :show (om/update-state!
                   owner
                   (fn [state]
                     (-> state
                         (assoc :mode :show)
                         (assoc :job-id value))))
            :list (om/update-state!
                   owner
                   (fn [state]
                     (-> state
                         (assoc :mode :list)
                         (assoc :job-id nil)))))
          (recur)))))
  (render-state [_ {:keys [mode comm job-id]}]
    (html [:div
           [:h2.ui.purple.header
            [:i.setting.icon]
            [:div.content
             "Job"
            [:div.sub.header "Edit and execute a job."]]]
           (condp = mode
             :edit
             (om/build job-edit-view job-id
                       {:init-state {:comm comm}})

             :show
             (om/build job-detail-view job-id
                       {:init-state {:comm comm}})

             ;; default
             [:div
              [:div.ui.top.attached.tabular.menu
               [:a (merge {:class "item"
                           :on-click #(om/set-state! owner :mode :list)}
                          (when (= mode :list) {:class "item active"}))
                "list"]
               [:a (merge {:class "item"
                           :on-click #(om/set-state! owner :mode :timeline)}
                          (when (= mode :timeline) {:class "item active"}))
                "timeline"]]
              [:div.ui.bottom.attached.active.tab.segment
               [:div#tab-content
                (om/build (case mode
                            :timeline timeline-view
                            :list     job-list-view)
                          app {:init-state {:comm comm}})]]])])))

(defcomponent main-view [app owner]
  (will-mount [_]
    (let [xhrio (net/xhr-connection)]
      (events/listen xhrio EventType.SUCCESS
                     (fn [e]
                       (om/update! app :agents
                                   (read-string (.getResponseText xhrio)))))
      (.send xhrio (str control-bus-url "/agents") "get")))
  (render [_]
    (html [:div
           
           (case (:page app)
             :agents (om/build agents-view app)
             :jobs (om/build jobs-view app))])))

(defcomponent right-menu-view [app owner]
  (render [_]
    (html 
     [:div
      [:div#agent-stats.item
       (when (= (:page app) :agents) {:class "active"})
       [:a.ui.tiny.horizontal.statistics
        {:on-click (fn [e]
                     (om/update! app :page :agents))}
        [:div.ui.inverted.statistic
         [:div.value (count (:agents app))]
         [:div.label (str "agent" (when (> (count (:agents app)) 1) "s"))]]]]
      [:div#job-stats.item
       (when (= (:page app) :jobs) {:class "active"})
       [:a.ui.tiny.horizontal.statistics
        {:on-click (fn [e]
                     (om/update! app :page :jobs))}
        [:div.ui.inverted.statistic
         [:div.value (count (:jobs app))]
         [:div.label (str "job" (when (> (count (:jobs app)) 1) "s"))]]]]
      [:div#job-search.item
       [:form {:on-submit (fn [e] (search-jobs app (.-value (.getElementById js/document "job-query"))) false)}
        [:div.ui.icon.transparent.inverted.input
         [:input#job-query {:type "text"}]
         [:i.search.icon]]]]])))

(om/root main-view app-state
         {:target (.getElementById js/document "main")})

(om/root right-menu-view app-state
         {:target (.getElementById js/document "menu")})

