(ns job-streamer.console.components.pagination
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [cljs.core.async :refer [put!]]
            [sablono.core :as html :refer-macros [html]]
            (job-streamer.console.format :as fmt)))

(defcomponent pagination-view [{:keys [jobs-view-channel page per max-items hits] :or {page 1 per 20 max-items 7}} owner]
  (render-state [_ {:keys [link-fn]}]
                (html
                  (if (> hits per)
                    (let [total-pages (inc (quot (dec hits) per))
                          half  (quot max-items 2)
                          start (if (> page half)
                                  (- page half) 1)
                          end   (if (< page (- total-pages half))
                                  (+ page half)
                                  total-pages)]
                      [:div.ui.pagination.menu
                       [:a.icon.item {:on-click (fn [_]
                                                  (when (> page 1) (do (link-fn (dec page))
                                                                     (println page)
                                                                     (put! jobs-view-channel [:change-page (dec page)]))))}
                        [:i.left.arrow.icon]]
                       (for [p-no (range start (inc end))]
                         [:a.item (merge
                                    {:on-click (fn [_]
                                                 (link-fn p-no)
                                                 (println p-no)
                                                 (put! jobs-view-channel [:change-page p-no]))}
                                    (when (= page p-no) {:class "active"})) p-no])
                       [:a.icon.item {:on-click (fn [_]
                                                  (when (< page total-pages) (do (link-fn (inc page))
                                                                               (println page)
                                                                               (put! jobs-view-channel [:change-page (inc page)]))))}
                        [:i.right.arrow.icon]]])))))

