(ns job-streamer.console.components.job-progress
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan close! pub timeout]]
            [tubax.core :refer [xml->clj]]
            (job-streamer.console.api :as api))
   (:use [cljs.reader :only [read-string]]
         [job-streamer.console.components.job-breadcrumb :only [breadcrumb-view]]))

;; Now, app-name is static.
(def app-name "default")
(def completed-color "#7fff7f")
(def failed-color "#ff7f7f")
(def started-color "#7f7fff")
(def stopped-color "#ffff7f")
(def else-color "#bf7fff")

(defn search-execution [owner job-name execution-id]
  (api/request (str "/" app-name "/job/" job-name "/execution/" execution-id)
               {:handler (fn [response]
                           (let [steps (:job-execution/step-executions response)]
                             (om/set-state! owner
                                           :step-executions steps)))}))

(defn search-job-status [owner job-name]
  (api/request (str "/" app-name "/job/" job-name)
               {:handler (fn [response]
                             (om/set-state! owner :job-batch-status (get-in response [:job/latest-execution :job-execution/batch-status :db/ident])))}))

(defn coloring-element[element step-execution]
    (.css element "fill"
          (case (get-in step-execution [:step-execution/batch-status :db/ident])
            (:batch-status/completed) completed-color
            (:batch-status/failed :batch-status/abandoned) failed-color
            (:batch-status/started :batch-status/starting) started-color
            (:batch-status/stopped :batch-status/stopping) stopped-color
            else-color)))

(defn coloring-svg [step-execution]
  (let [not-named-element (js/jQuery (str "g[data-element-id=\"" (:step-execution/step-name step-execution) "\"] g rect"))]
    (coloring-element not-named-element step-execution))
  (let [step-name (:step-execution/step-name step-execution)
        step-name-element (-> (str "tspan:contains(\"" step-name "\")")
                              js/jQuery
                              (.filter #(= (.text (js/jQuery "this")) step-name)))
        named-element (.. step-name-element (parents "g.djs-element") (find "g rect"))]
    (coloring-element named-element step-execution)))

(defn calcute-width [svg]
  (if svg
    (-> svg xml->clj :attributes :width)
    0))

(defn calcute-height [svg]
  (if svg
    (-> svg xml->clj :attributes :height)
  0))

(defcomponent job-progress-view [{:keys [job width height]} owner]
  (will-mount [_]
             (let [latest-execution-id (get-in job [:job/latest-execution :db/id])]
               (om/set-state! owner :latest-execution-id latest-execution-id)
               (search-execution owner (:job/name job) latest-execution-id)))
  (did-mount [_]
    (let [ch (chan)]
      (om/set-state! owner :refresh-timer ch)
      (go-loop []
        (when-let [_ (<! ch)]
          (<! (timeout 5000))
          (search-job-status owner (:job/name job))
          (search-execution owner (:job/name job) (om/get-state owner :latest-execution-id))
          (when-not (#{:batch-status/completed :batch-status/stopped :batch-status/abandoned :batch-status/failed} (om/get-state owner :job-batch-status))
            (put! ch :continue))
          (recur)))
      (put! ch :start)))
  (render-state [_ {:keys [step-executions]}]
                (doall (map coloring-svg step-executions))
                (html [:svg (cond->
                              {:dangerouslySetInnerHTML {:__html (:job/svg-notation job)}
                               :x "0px"
                               :y "0px"
                               :viewBox (str "0 0 " (-> job :job/svg-notation calcute-width) " " (-> job :job/svg-notation calcute-height))
                               :preserveAspectRadio "xMinYMin meet"}
                              width (assoc :width (str width "px"))
                              height (assoc :height (str height "px")))])))

(defcomponent coloring-meaning-view[_]
  (render [_]
          (html
            [:div
             [:div.ui.horizontal.list
              [:span.item
               [:div.ui.horizontal.label
                {:style
                 {:background-color completed-color}}]
               "completed"]
              [:span.item
               [:div.ui.horizontal.label
                {:style
                 {:background-color failed-color}}]
               "failed or abandoned"]
              [:span.item
               [:div.ui.horizontal.label
                {:style
                 {:background-color started-color}}]
               "started or starting"]
              [:span.item
               [:div.ui.horizontal.label
                {:style
                 {:background-color stopped-color}}]
               "stopped or stopping"]
              [:span.item
               [:div.ui.horizontal.label
                {:style
                 {:background-color else-color}}]
               "else"]]])))


(defcomponent big-job-progress-view [job-name owner]
  (init-state [_]
              {:refresh-job-ch (chan)
               ;change mode to back detail view
               :mode [:jobs :detail :progress]})
  (will-mount [_]
              (api/request (str "/" app-name "/job/" job-name)
                           {:handler (fn [response]
                                       (om/set-state! owner :job response))}))
  (render-state [_ {:keys [job refresh-job-ch dimmed? mode]}]
                (html
                  [:div
                   (om/build breadcrumb-view mode {:init-state {:job-name job-name}
                                                   :react-key "job-progress-breadcrumb"})
                   (om/build coloring-meaning-view {:react-key "coloring-meaning"})
                   [:div.dimmable.image.dimmed
                    {:on-mouse-enter (fn [e]
                                       (om/set-state! owner :dimmed? true))
                     :on-mouse-leave (fn [e]
                                       (om/set-state! owner :dimmed? false))}
                    [:div.ui.inverted.dimmer (when dimmed? {:class "visible"})
                     [:div.content
                      [:div.center
                       [:button.ui.primary.button
                        {:type "button"
                         :on-click (fn [e]
                                     (let [w (js/window.open (str "/" app-name "/job/" job-name "/edit") name "width=1200,height=800")]
                                       (.addEventListener w "unload" (fn [] (js/setTimeout (fn [] (put! refresh-job-ch true))) 10))))}
                        "Edit"]]]]
                    [:div
                     (when job
                       (om/build job-progress-view
                                 {:job job}
                                 {:react-key "big-job-progress-view"}))]]])))
