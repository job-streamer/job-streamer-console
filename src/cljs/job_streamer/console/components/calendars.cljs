(ns job-streamer.console.components.calendars
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.ui.Component]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [job-streamer.console.api :as api]
            [job-streamer.console.format :as fmt])
  
  (:use [cljs.reader :only [read-string]])
  (:import [goog.net.EventType]
           [goog.events EventType]))

(defn save-calendar [calendar owner]
  (letfn [(on-success [_] (om/set-state! owner :save-status true))
          (on-failure [res error-code] (om/set-state! owner :save-error error-code))]
    (if (:new? calendar)
      (api/request "/calendars" :POST calendar
                   {:handler on-success
                    :error-handler on-failure})
      (api/request (str "/calendar/" (:calendar/name calendar)) :PUT calendar
                   {:handler on-success
                    :error-handler on-failure}))))

(defn delete-calendar [calendar cb]
  (api/request (str "/calendar/" (:calendar/name calendar)) :DELETE
               {:handler (fn [_]
                           (cb))}))

(defcomponent calendar-detail-view [cal-name owner]
  (render-state [_ {:keys [calendar]}]
    (html
     [:div
      [:div.ui.grid
       [:div.row
        [:div.column
         [:h3.ui.header (:calendar/name calendar)]]]
       
       [:div.row
        [:div.column
         [:div#holiday-selector]]]

       [:div.row
        [:div.column
         [:button.ui.basic.button
          {:type "button"
           :on-click (fn [e]
                       (set! (.-href js/location) (str "#/calendar/" (js/encodeURIComponent cal-name) "/edit")))}
          [:i.edit.icon] "Edit"]]]]]))
  (did-mount [_]
    (api/request (str "/calendar/" cal-name)
                 {:handler (fn [response]
                             (om/update-state! owner
                                               (fn [state]
                                                 (assoc state
                                                        :kalendae (js/Kalendae.
                                                                   (clj->js {:attachTo (.getElementById js/document "holiday-selector")
                                                                             :months 4
                                                                             :mode "multiple"
                                                                             :selected (:calendar/holidays response)
                                                                             :blackout (fn [d]
                                                                                         (nth (->> (:calendar/weekly-holiday response)
                                                                                                   (map #(if % 1 0)))
                                                                                              (.. js/Kalendae (moment d) day)))}))
                                                        :calendar response))))})))

(defcomponent calendar-edit-view [calendars owner {:keys [cal-name]}]
  (init-state [_]
    {:calendar (if-let [calendar (some->> calendars
                                          (filter #(= (:calendar/name %) cal-name))
                                          first)]
                 calendar
                 {:calendar/name nil
                  :calendar/weekly-holiday [true false false false false false true]
                  :calendar/holidays []
                  :new? true})
     
     :kalendae nil
     :save-status false
     :save-error nil})
  (render-state [_ {:keys [kalendae calendar error-map save-error save-status]}]
    (html
     [:form.ui.form
      [:h4.ui.dividing.header (if (:new? calendar) "New calendar" "Edit calendar")]
      [:div.ui.grid
       [:div.row
        [:div.column
         (if (:new? calendar)
           [:div.field (when (:calendar/name error-map) {:class "error"})
            [:label "Calendar name"]
            [:input {:type "text" :id "cal-name" :value (:calendar/name calendar)
                     :on-change (fn [e] (om/set-state! owner [:calendar :calendar/name] 
                                                       (.. js/document (getElementById "cal-name") -value)))
                     :on-focus (fn [e] (om/set-state! owner [:error-map :calendar/name] nil))}]
            (when-let [msgs (:calendar/name error-map)]
              [:div.ui.popup.transition.visible.top.left
               (first msgs)])]
           [:div.field
            [:h4 (:calendar/name calendar)]])]]
       [:div.row
        [:div.column
         [:div.field
          [:label "Weekly holidays"]
          [:div.ui.buttons
           (map-indexed
            (fn [idx weekday]
              [:button.ui.toggle.button
               {:type "button"
                :class (when (nth (:calendar/weekly-holiday calendar) idx) "red")
                :on-click (fn [_]
                            (om/update-state! owner [:calendar :calendar/weekly-holiday idx] #(not %))
                            (set! (.-blackout calendar)
                                  (fn [d]
                                    (nth (->> (om/get-state owner [:calendar :calendar/weekly-holiday])
                                              (map #(if % 1 0)))
                                         (.. js/Kalendae (moment d) day))))
                            (.draw (om/get-state owner :kalendae)))}
               weekday])
            ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Stu"])]]]]
       [:div.row
        [:div.column
         [:div.field
          [:label "Holidays"]
          [:div#holiday-selector]]]]
       [:div.row
        [:div.right.aligned.column
         [:div.field
          [:button.ui.positive.button.submit
           {:type "button"
            :on-click (fn [_]
                        (om/set-state! owner [:calendar :calendar/holidays]
                                       (js->clj (.getSelectedAsDates (om/get-state owner :kalendae))))
                        (let [calendar (om/get-state owner :calendar)
                              [result map] (b/validate calendar :calendar/name v/required)]
                          (if result
                            (om/set-state! owner :error-map (:bouncer.core/errors map))
                            (save-calendar calendar owner))))}
           [:i.save.icon] "Save"]
          (if save-status
            [:i.checkmark.green.icon]
            (when save-error
              [:div.red save-error]))]]]]]))

  (did-mount [_]
    (om/set-state! owner :kalendae
                   (js/Kalendae. (clj->js {:attachTo (.getElementById js/document "holiday-selector")
                                           :months 4
                                           :selected (om/get-state owner [:calendar :calendar/holidays])
                                           :mode "multiple"
                                           :blackout (fn [d] (nth (->> (om/get-state owner [:calendar :calendar/weekly-holiday])
                                                                       (map #(if % 1 0)))
                                                                  (.. js/Kalendae (moment d) day))) })))))

(defcomponent calendar-list-view [calendars owner]
  (render [_]
    (html
     [:div.ui.grid
      [:div.ui.two.column.row
        [:div.column
         [:button.ui.basic.green.button
          {:type "button"
           :on-click #(set! (.-href js/location) "#/calendars/new")}
          [:i.plus.icon] "New"]]]
      [:div.row
       (when (not-empty calendars)
        [:table.ui.celled.striped.table
         [:thead
          [:tr
           [:th "Name"]
           [:th {:col-span 2} "Holidays"]]]
         [:tbody
          (for [cal calendars]
            [:tr
             [:td
              [:a {:href (str "#/calendar/" (:calendar/name cal))}
               (:calendar/name cal)]]
             [:td (let [holidays (take 3 (:calendar/holidays cal))]
                    (str (->> holidays
                              (map #(fmt/date-only %))
                              (clojure.string/join ","))
                         (when (> (count (:calendar/holidays cal)) 3)
                           ",...")))]
             [:td
              [:i.red.delete.icon
               {:on-click (fn [_]
                            (delete-calendar cal (fn []
                                                   (om/update! calendars
                                                               (fn [cals] (remove #(= % cal)))))))}]]])]])]])))

(defcomponent calendars-view [app owner]
  (render [_]
    (html
     [:div.ui.grid
      [:div.ui.row
       [:div.ui.column
        [:h2.ui.violet.header
         [:i.calendar.icon]
         [:div.content
          "Calendar"
          [:div.sub.header "Calendars"]]]]]
      [:div.ui.row
       [:div.ui.column
        (let [mode (second (:mode app))]
          (case mode
            :new (om/build calendar-edit-view (:calendars app) {:opts {:mode mode}})
            :detail (om/build calendar-detail-view (:cal-name app))
            :edit (om/build calendar-edit-view (:calendars app)
                            {:opts {:mode mode
                                    :cal-name (:cal-name app)}})
            ;; default
            (cond
              (nil? (:calendars app)) [:img {:src "/img/loader.gif"}]
              :default (om/build calendar-list-view (:calendars app)))))]]])))

