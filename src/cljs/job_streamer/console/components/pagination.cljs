(ns job-streamer.console.components.pagination
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            (job-streamer.console.format :as fmt)))

(defcomponent pagination-view [{:keys [page per max-items hits] :or {page 1 per 20 max-items 7}} owner]  
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
                                     (when (> page 1) (link-fn (dec page))))}
           [:i.left.arrow.icon]]
          (for [p-no (range start (inc end))]
            [:a.item (merge
                      {:on-click (fn [_]
                                   (link-fn p-no))}
                      (when (= page p-no) {:class "active"})) p-no])
          [:a.icon.item {:on-click (fn [_]
                                     (when (< page total-pages) (link-fn (inc page))))}
           [:i.right.arrow.icon]]])))))

