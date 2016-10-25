(ns job-streamer.console.style
  (:use [garden.core :only [css]]
        [garden.units :only [px em]]))

(def styles
  [[:.full.height {:height "100%"}]
   [:.main.content {:min-height "100%"
                    :max-width (px 960)
                    :margin {:left "auto" :right "auto"}
                    :padding {:top (px 80)
                              :left (em 2)
                              :right (em 2)}
                    :background {:color "#fff"}
                    :border {:left "1px solid #ddd"
                             :right "1px solid #ddd"}}
    [:.ui.selection.dropdown {:min-width (em 1)
                              :padding (em 0.5)}]]
   [:table.job-list
    [:td.job-name {:max-width (px 280)}
     [:div {:overflow "hidden"
            :text-overflow "ellipsis"}]]]
   [:div.breadcrumb
    [:div.section {:max-width (px 320)
                   :white-space "nowrap"
                   :text-overflow "ellipsis"
                   :overflow "hidden"}]]
   [:div.name {:overflow "hidden"}]
   [:#timeline-inner {:font-size (px 8)}]
   [:#job-blocks-inner {:height (px 400)}]
   [:#tab-content {:padding (px 10)}]
   [:.ui.menu
    [:#agent-stats.item :#job-stats.item
     {:padding-top (em 0)}
     [:a {:cursor "pointer"}]
     [:.ui.horiontal.statistics :.statistic {:margin {:top (em 0.2)
                                                      :bottom (em 0.2)}}]]]
   [:.ui.cards
    [:.job-detail.card {:width "100%"}
     [:#job-blocks-inner {:height (px 250)}]]]
   [:#job-blocks {:min-height (px 500)}]
   [:td.log-link
    [:a {:cursor "pointer"}]]
   [:.step-view
    [:.item
     [:.content
      [:.log.list {:background {:color "#6f6f6f"}
                   :padding (em 1)
                   :font-family "monospace"
                   :border {:radius (px 3)}
                   :overflow {:y "auto" :x "auto"}
                   :margin-top (px 10)
                   :max-width (px 800)
                   :max-height (px 400)}
       [:.item
        [:.content
         [:.description {:color "#dcdccc"}
          [:span {:margin {:right (em 1)}}]
          [:span.date {:color "#dca3a3"}]
          [:pre {:overflow "visible"}]]]]]]]]

   [:.kalendae
    [:.k-days
     [:span.k-selected.k-active {:background {:color "#d01919"}}]]]
   [:.vis.timeline
    [:.item.range {:color "#313131"
                   :background {:color "#abe1fd"}
                   :border {:color "#abe1fd"}}]
    [:.item.range.completed {:color "#3c763d"
                             :background {:color "#adddcf"}
                             :border {:color "#adddcf"}}]
    [:.item.range.failed    {:color "#cd2929"
                             :background {:color "#fed1ab"}
                             :border {:color "#fed1ab"}}]]

   [:.k-days
    [:span {:box-sizing "content-box"}]]
   [:div.ui.input.block {:display "block"}]
   [:table.ui.table
   [:th.can-sort {:cursor "pointer"}]]]
)


(defn build []
  (css {:pretty-pring? false} styles))
