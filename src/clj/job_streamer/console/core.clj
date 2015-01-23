(ns job-streamer.console.core
  (:use [hiccup.core :only [html]]
        [hiccup.page :only [html5 include-css include-js]]
        [ring.util.response :only [resource-response content-type header]])
  (:require [hiccup.middleware :refer [wrap-base-url]]
            [compojure.core :refer [defroutes GET]]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [job-streamer.console.style :as style]))

(def control-bus-url "http://localhost:45102")

(defn layout [& body]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1"}]
    (include-css "https://cdnjs.cloudflare.com/ajax/libs/semantic-ui/1.6.4/semantic.min.css"
                 "/css/vis.min.css"
                 "/css/job-streamer.css")
    (include-js  "/js/vis.min.js"
                 "/react/react.js")]
   [:body
    [:div.ui.page
     [:div.ui.fixed.inverted.teal.menu
      [:div.title.item [:b "Job Streamer"]]
      [:div#menu.right.menu]]
     [:div.main.grid.content.full.height
      body]]]))

(defn index []
  (layout [:div.row
           [:div.column
            [:div.ui.items
             [:div.ui.item
              [:div#main.content]]]]]
          (include-js "/js/jobs.js")))

(defn job-edit-view []
  (layout [:div.row
           [:div.column
            [:div.ui.items
             [:div.ui.item
              [:div#job-blocks]]]]]
          [:xml#job-toolbox
           [:block {:type "job"}]
           [:block {:type "step"}]
           [:block {:type "batchlet"}]
           [:block {:type "chunk"}]
           [:block {:type "reader"}]
           [:block {:type "processor"}]
           [:block {:type "writer"}]
           ]
          (include-js "/js/blockly_compressed.js"
                      "/js/blocks.js")))

(defroutes app-routes
  (GET "/" [] (index))
  (GET "/job/new" [] (job-edit-view))
  (GET "/react/react.js" [] (-> (resource-response "react/react.js")
                                (content-type "text/javascript")))
  (GET "/react/react.min.js" [] (resource-response "react/react.min.js"))
  (GET "/css/job-streamer.css" [] (-> {:body (style/build)}
                                      (content-type "")))
  (route/resources "/"))

(def app
  (-> (handler/site app-routes)
      (wrap-base-url)))
