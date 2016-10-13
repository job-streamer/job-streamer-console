(ns job-streamer.console.components.apps
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.ui.Component]
            [job-streamer.console.api :as api]
            [job-streamer.console.format :as fmt]
            [cljsjs.dropzone]
            [cljsjs.jquery]
            [job-streamer.console.api :as api])
  (:use [cljs.reader :only [read-string]])
  (:import [goog.net.EventType]
           [goog.events EventType]))

(defcomponent app-edit-view [app-name owner]
  (will-mount [_])
  (did-mount [_]
             (set! (.-autoDiscover js/Dropzone) false)
             (js/Dropzone. ".dropzone" (js-obj "url" (api/url-for "/default/batch-components")
                                               "headers" (js-obj "Cache-Control" "" "X-Requested-With" "")
                                               "maxFiles" 1
                                               "accept" (fn [file done]
                                                          (if (clojure.string/ends-with? (.-name file) ".jar")
                                                            (done)
                                                            (done "Unsupported file type."))))))
  (render-state [_ {:keys [agent]}]
                (html
                  [:div
                   [:div.ui.icon.message
                    [:i.child.icon]
                    [:div.content
                     [:div.header "Let's create batch components!"]
                     [:ol.ui.list
                      [:li
                       [:h4.ui.header "Clone repository."]
                       [:pre
                        [:code "% git clone https://github.com/job-streamer/job-streamer-examples.git"]]]
                      [:li
                       [:h4.ui.header "Create a jar file with dependencies."]
                       [:pre
                        [:code "% cd job-streamer-examples\n"
                         "% mvn clean compile assembly:single"]]]
                      [:li
                       [:h4.ui.header "Upload the jar file to the following form."]
                       [:pre "target/job-streamer-examples-X.Y.Z-SNAPSHOT.jar"]]]]]
                   [:div {:class "dropzone"
                          :style {:border-style "dashed"
                                  :border-width "2px"
                                  :cursor "pointer"}}]])))

(defcomponent apps-view [app owner]
  (render [_]
          (html
            [:div.ui.grid
             [:div.ui.row
              [:div.ui.column
               [:h2.ui.violet.header
                [:i.browser.icon]
                [:div.content
                 "Application"
                 [:div.sub.header (:application/name app)]]]]]
             [:div.ui.row
              [:div.ui.column
               (let [mode (second (:mode app))]
                 (case mode
                   :edit (om/build app-edit-view (:application/name app) {:react-key "app-edit"})
                   ;; default
                   ))]]])))
