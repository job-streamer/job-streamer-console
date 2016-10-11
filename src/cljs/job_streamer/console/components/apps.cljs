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
             (set! (.-myAwesomeDropzone (.-options js/Dropzone))
                   (js-obj ))
             (js/Dropzone. ".drag-and-drop-area" (js-obj "url"
                                                         (fn [files]
                                                           (println (str "Upload File : " (.-name (first files))))
                                                           "#"))))
  (render-state [_ {:keys [agent]}]
                (html
                  [:div
                   [:h2 app-name]
                   [:div.drag-and-drop-area {:style {:color "#ccc"
                                                     :height "200px"
                                                     :margin "30px 0 20px"
                                                     :border-style "dashed"
                                                     :border-width "2px"
                                                     :line-height "200px"
                                                     :text-align "center"
                                                     :cursor "pointer"}}]])))

(defcomponent apps-view [app owner]
  (render [_]
          (html
            [:div.ui.grid
             [:div.ui.row
              [:div.ui.column
               [:h2.ui.violet.header
                [:i.laptop.icon]
                [:div.content
                 "Apps"
                 [:div.sub.header "Applications."]]]]]
             [:div.ui.row
              [:div.ui.column
               (let [mode (second (:mode app))]
                 (case mode
                   :edit (om/build app-edit-view (:application/name app) {:react-key "app-edit"})
                   ;; default
                   ))]]])))
