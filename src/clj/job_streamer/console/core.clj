(ns job-streamer.console.core
  (:use [hiccup.core :only [html]]
        [hiccup.page :only [html5 include-css include-js]]
        [ring.util.response :only [resource-response content-type header]]
        [environ.core :only [env]])
  (:require [hiccup.middleware :refer [wrap-base-url]]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.handler :as handler]
            [compojure.route :as route]
            (job-streamer.console [style :as style]
                                  [jobxml :as jobxml])))

(def control-bus-url (or (env control-bus-url) "http://localhost:45102"))

(defn layout [& body]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "control-bus-url" :content control-bus-url}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1"}]
    (include-css "https://cdnjs.cloudflare.com/ajax/libs/semantic-ui/1.12.3/semantic.min.css"
                 "/css/vendors/vis.min.css"
                 "/css/vendors/kalendae.css"
                 "/css/job-streamer.css")
    (include-js  "/js/vendors/vis.min.js"
                 "/js/vendors/kalendae.standalone.min.js")
    (when (:dev env) (include-js "/react/react.js"))]
   [:body body]))

(defn index []
  (layout
   [:div#app.ui.page]
   [:xml#job-toolbox
    [:category {:name "Core"}
     [:block {:type "job"}]
     [:block {:type "property"}]
     [:block {:type "step"}]]
    [:category {:name "Step components"}
     [:block {:type "batchlet"}]
     [:block {:type "chunk"}]
     [:block {:type "reader"}]
     [:block {:type "processor"}]
     [:block {:type "writer"}]]]
   (include-js (str "/js/extern/job-streamer"
                    (when-not (:dev env) ".min") ".js"))))

(defroutes app-routes
  (GET "/" [] (index))
  (POST "/job/from-xml" [:as request]
    (when-let [body (:body request)]
      (let [xml (slurp body)]
        {:headers {"Content-Type" "application/edn"}
         :body (pr-str (jobxml/xml->job xml))})))
  (GET "/react/react.js" [] (-> (resource-response "cljsjs/development/react.inc.js")
                                (content-type "text/javascript")))
  (GET "/react/react.min.js" [] (resource-response "cljsjs/production/react.min.inc.js"))
  (GET "/css/job-streamer.css" [] (-> {:body (style/build)}
                                      (content-type "text/css")))
  (route/resources "/"))

(def app
  (-> (handler/site app-routes)
      (wrap-base-url)))
