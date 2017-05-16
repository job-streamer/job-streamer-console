(ns job-streamer.console.components.calendars
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all timeout]]
            [clojure.browser.net :as net]
            [clojure.string :as string]
            [goog.events :as events]
            [goog.ui.Component]
            [goog.Uri.QueryData :as query-data]
            [goog.string :as gstring]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [job-streamer.console.api :as api]
            [job-streamer.console.format :as fmt])

  (:use [cljs.reader :only [read-string]]
        (job-streamer.console.components.dialog :only[dangerously-action-dialog])
        (job-streamer.console.routing :only[fetch-calendars])
        [job-streamer.console.search :only [parse-sort-order toggle-sort-order]])
  (:import [goog.net.EventType]
           [goog.events EventType]
           [goog Uri]))

(defn save-calendar [calendar owner calendars-channel message-channel]
  (letfn [(on-success [_] (do
                            (put! message-channel {:type "info" :body "Saved calendar successfully."})
                            (put! calendars-channel [:save-calendar calendar]
                                  #(set! (.-href js/location)  "#/calendars"))))
          (on-failure [res error-code]
                      (put! message-channel {:type "error" :body (:message res)}))]
    (if (:new? calendar)
      (api/request "/calendars" :POST calendar
                   {:handler on-success
                    :error-handler on-failure})
      (api/request (str "/calendar/" (:calendar/name calendar)) :PUT calendar
                   {:handler on-success
                    :error-handler on-failure}))))

(defn delete-calendar [calendar owner calendars-channel]
  (api/request (str "/calendar/" (:calendar/name calendar)) :DELETE
               {:handler (fn [response]
                           (put! calendars-channel [:delete-calendar calendar])
                           (set! (.-href js/location) "#/calendars"))
                :error-handler (fn[res error-code]
                                 (om/set-state! owner :delete-error res))}))

(defn hh:mm? [hh:mm-string]
  (if hh:mm-string
    (re-find #"^([01]?[0-9]|2[0-3]):([0-5][0-9])$" hh:mm-string)
    false))

(def breadcrumb-elements
  {:calendars {:name "calendars" :href "#/calendars"}
   :calendars.new {:name "New" :href "#/calendars/new"}
   :calendars.detail {:name "calendar: %s" :href "#/calendar/%s"}
   :calendars.edit {:name "Edit: %s" :href "#/calendar/%s/edit"}})

(defcomponent breadcrumb-view [mode owner]
  (render-state [_ {:keys [calendar-name]}]
                (html
                  [:div.ui.breadcrumb
                   (drop-last
                     (interleave
                       (loop [i 1, items []]
                         (if (<= i (count mode))
                           (recur (inc i)
                                  (conj items (if-let [item (get breadcrumb-elements
                                                                 (->> mode
                                                                      (take i)
                                                                      (map name)
                                                                      (string/join ".")
                                                                      keyword))]
                                                [:a.section {:href (gstring/format (:href item) calendar-name)}
                                                 (gstring/format (:name item) calendar-name)])))
                           (let [res (keep identity items)]
                             (conj (vec (drop-last res))
                                   (into [:div.section.active] (rest (last res)))))))
                       (repeat [:i.right.chevron.icon.divider])))])))

(defcomponent calendar-detail-view [cal-name owner]
  (render-state [_ {:keys [calendar mode]}]
                (html
                  [:div
                   (om/build breadcrumb-view mode {:init-state {:calendar-name cal-name}
                                                   :react-key "calendar-detail-breadcrumb"})
                   [:div#calendar-detail-view-content.ui.grid
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

(defcomponent calendar-edit-view [calendars owner {:keys [cal-name calendars-channel]}]
  (init-state [_]
    {:calendar (if-let [calendar (some->> calendars
                                          (filter #(= (:calendar/name %) cal-name))
                                          first
                                          ;;Temporary solution: this value has sometimes wrong type, om.core/MapCursor.
                                          om/value
                                          )]
                 calendar
                 {:calendar/name nil
                  :calendar/weekly-holiday [true false false false false false true]
                  :calendar/holidays []
                  :calendar/day-start "00:00"
                  :new? true})

     :kalendae nil
     :save-status false
     :save-error nil})
  (render-state [_ {:keys [kalendae calendar error-map save-error save-status mode message-channel]}]
    (html
     [:form.ui.form
      (om/build breadcrumb-view mode {:init-state {:calendar-name cal-name}
                                      :react-key "calendar-edit-breadcrumb"})
      [:h4.ui.dividing.header (if (:new? calendar) "New calendar" "Edit calendar")]
      [:div.ui.grid
       [:div.row
        [:div.column
         (if (:new? calendar)
           [:div.field (when (:calendar/name error-map) {:class "error"})
            [:label "Calendar name"]
            [:input {:id "cal-name"
                     :type "text"
                     :value (:calendar/name calendar)
                     :on-change (fn [e] (let [editting-cal-name (.. js/document (getElementById "cal-name") -value)]
                                          (om/set-state! owner [:calendar :calendar/name]
                                                         editting-cal-name)
                                          (api/request (str "/calendar/" editting-cal-name) :GET
                                                       {:handler (fn [response]
                                                                   (om/set-state! owner [:error-map :calendar/name]
                                                                                  ["Name is already taken"]))
                                                        :error-handler(fn [e] (om/update-state! owner [:error-map]
                                                         #(dissoc % :calendar/name)))})))}]

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
         [:div.field (if (:calendar/day-start error-map) {:class "error"} {})
          [:label "Day start"]
          [:input {:id "day-start"
                   :type "text"
                   :value (:calendar/day-start calendar)
                   :on-change (fn [e]
                                (let [day-start (.. js/document (getElementById "day-start") -value)]
                                  (om/set-state! owner [:calendar :calendar/day-start] day-start)
                                  (if (hh:mm? day-start)
                                    (om/update-state! owner [:error-map] #(dissoc % :calendar/day-start))
                                    (om/set-state! owner [:error-map :calendar/day-start]
                                                   ["Invalid hh:mm format"]))))}]
          (when-let [msgs (:calendar/day-start error-map)]
            [:div.ui.popup.transition.visible.top.left
             (first msgs)])]]]
       [:div.row
        [:div.column
         [:div.field
          [:label "Holidays"]
          [:div#holiday-selector]]]]
       [:div.row
        [:div.right.aligned.column
         [:div.field
          [:button.ui.black.deny.button
           {:type "button"
            :on-click (fn [e]
                        (set! (.-href js/location) "#/calendars"))}
           "Cancel"]
          [:button.ui.positive.button.submit
           (merge {:type "button"
                   :on-click (fn [_]
                               (om/set-state! owner [:calendar :calendar/holidays]
                                              (js->clj (.getSelectedAsDates (om/get-state owner :kalendae))))
                               (let [calendar (om/get-state owner :calendar)
                                     [result map] (b/validate calendar :calendar/name v/required)]
                                 (if result
                                   (om/set-state! owner :error-map (:bouncer.core/errors map))
                                   (save-calendar calendar owner calendars-channel message-channel))))}
                  (when (not-empty error-map) {:class "disabled"}))
           [:i.save.icon] "Save"]]]]]]))

  (did-mount [_]
    (om/set-state! owner :kalendae
                   (js/Kalendae. (clj->js {:attachTo (.getElementById js/document "holiday-selector")
                                           :months 4
                                           :selected (om/get-state owner [:calendar :calendar/holidays])
                                           :mode "multiple"
                                           :blackout (fn [d] (nth (->> (om/get-state owner [:calendar :calendar/weekly-holiday])
                                                                       (map #(if % 1 0)))
                                                                  (.. js/Kalendae (moment d) day))) })))))

(defcomponent calendar-list-view [app owner {:keys [calendars-channel]}]
  (init-state [_]
    {:delete-error nil})
  (render-state [_ {:keys [delete-error]}]
    (html
     [:div.ui.grid
      (let [messages (:messages delete-error)]
        [:div.row {:style {:display (if messages "block" "none")}}
         [:div.column
          [:div.ui.message {:class "error"}
           [:div.header "Failed to delete calendar"]
           [:div.body
            (for[message messages] message)]]]])

      [:div.ui.two.column.row
       [:div.column
        [:button.ui.basic.green.button
         {:type "button"
          :on-click #(set! (.-href js/location) "#/calendars/new")}
         [:i.plus.icon] "New"]]]
      [:div.row
       (when-let [calendars (not-empty (:calendars app))]
         [:table.ui.celled.striped.table
          [:thead
           [:tr
            [:th.can-sort
             {:on-click (fn [e]
                          (let [uri (.. (Uri. "/calendars")
                                        (setQueryData (query-data/createFromMap
                                                        (clj->js {:sort-by (-> app
                                                                               :calendar-sort-order
                                                                               (toggle-sort-order :name)
                                                                               parse-sort-order)}))))]
                            (api/request (.toString uri) :GET
                                         {:handler (fn [response]
                                                     (om/transact! app
                                                                   #(assoc %
                                                                      :calendars response)))}))
                            (om/transact! app :calendar-sort-order #(toggle-sort-order % :name)))}
             "Name"
             [:i.sort.icon
                          {:class (when-let [sort-order (get-in app [:calendar-sort-order :name])]
                                    (if (= sort-order :asc)
                                      "ascending"
                                      "descending"))}]]
            [:th "Holidays"]
            [:th "Day start"]
            [:th "Operations"]]]
          [:tbody
           (for [cal calendars]
             [:tr
              [:td
               [:a {:href (str "#/calendar/" (js/encodeURIComponent (:calendar/name cal)))}
                (:calendar/name cal)]]
              [:td (let [holidays (take 3 (:calendar/holidays cal))]
                     (str (->> holidays
                               (map #(fmt/date-only %))
                               (clojure.string/join ","))
                          (when (> (count (:calendar/holidays cal)) 3)
                            ",...")))]
              [:td (:calendar/day-start cal)]
              [:td
               [:button.ui.red.button
                {:type "button"
                 :on-click (fn [e]
                             (.preventDefault e)
                             (put! calendars-channel [:open-dangerously-dialog
                                                      {:ok-handler (fn []
                                                                     (delete-calendar cal owner calendars-channel))
                                                       :answer (:calendar/name cal)}]))}"Delete"]]])]])]])))


(defcomponent calendars-view [app owner {:keys [calendars-channel]}]
  (init-state [_]
    {:dangerously-action-data nil
     :message-channel (chan)})
  (will-mount [_]
    (go-loop []
      (let [[cmd msg] (<! calendars-channel)]
        (try
          (case cmd
            :delete-calendar (om/transact! (:calendars app)
                                           (fn [cals]
                                             (remove #(= % msg) cals)))
            :save-calendar (om/transact! (:calendars app)
                                           (fn [cals]
                                             (->> cals
                                             (remove #(= (:calendar/name %) (:calendar/name msg)))
                                             (cons msg))))
            :fetch-calendar (fetch-calendars
                              (fn [response]
                                (om/transact! app (fn [cursor]
                                                          (assoc cursor
                                                            :calendars response)))))
            :open-dangerously-dialog (om/set-state! owner :dangerously-action-data msg))
          (catch js/Error e))
        (when (not= cmd :close-chan-listener)
          (recur))))
    (go-loop []
      (when-let [msg (<! (om/get-state owner :message-channel))]
        (om/set-state! owner :message msg)
        (go (<! (timeout 5000))
          (om/set-state! owner :message nil))
        (recur))))
  (render-state [_ {:keys [dangerously-action-data message message-channel]}]
    (let [mode (second (:mode app))]
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
          (when message [:div#message.ui.floating.message {:class (str "visible " (:type message))} (:body message)])
          (case mode
            :new (om/build calendar-edit-view (:calendars app) {:init-state {:message-channel message-channel}
                                                                :state {:mode (:mode app)}
                                                                :opts {:calendars-channel calendars-channel
                                                                       :message-channel message-channel}
                                                                :react-key "calendar-new"})
            :detail (om/build calendar-detail-view (:cal-name app) {:state {:mode (:mode app)}
                                                                    :opts {:calendars-channel calendars-channel}
                                                                    :react-key "calendar-detail"})
            :edit (om/build calendar-edit-view (:calendars app)
                            {:init-state {:message-channel message-channel}
                             :opts {:cal-name (:cal-name app)
                                    :calendars-channel calendars-channel}
                             :state {:mode (:mode app)}
                             :react-key "calendar-edit"})
            ;; default
            (cond
              (nil? (:calendars app)) [:img {:src "/img/loader.gif"}]
              :default (om/build calendar-list-view app {:state {:mode (:mode app)}
                                                                      :opts {:calendars-channel calendars-channel}
                                                                      :react-key "calendar-list"})))
          (when dangerously-action-data
            (om/build dangerously-action-dialog nil
                      {:opts (assoc dangerously-action-data
                                    :ok-handler (fn []
                                                  (om/set-state! owner :dangerously-action-data nil)
                                                  ((:ok-handler dangerously-action-data)))
                                    :cancel-handler (fn [] (om/set-state! owner :dangerously-action-data nil))
                                    :delete-type "calendar")
                       :react-key "calendar-dialog"}))]]])))
  (will-unmount [_]
    (put! calendars-channel [:close-chan-listener true])))
