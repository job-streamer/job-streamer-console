(ns job-streamer.console.endpoint.console
  (:require [compojure.core :refer :all]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5 include-css include-js]]
            [ring.util.response :refer [resource-response content-type header]]
            [environ.core :refer [env]]
            (job-streamer.console [style :as style]
                                  [jobxml :as jobxml])))

(defn layout [config & body]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "control-bus-url" :content (:control-bus-url config)}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1"}]
    (include-css "//cdn.jsdelivr.net/semantic-ui/2.0.3/semantic.min.css"
                 "/css/vendors/vis.min.css"
                 "/css/vendors/kalendae.css"
                 "/css/vendors/dropzone.css"
                 "/css/job-streamer.css")
    (include-js  "/js/vendors/vis.min.js"
                 "/js/vendors/kalendae.standalone.min.js"
                 )
    (when (:dev env)
      (include-js "/react/react.js"))]
   [:body body]))

(defn index [config]
  (layout config
   [:div#app.ui.full.height.page]
   [:xml#job-toolbox
    [:block {:type "job"}]
    [:block {:type "property"}]
    [:block {:type "step"}]
    [:block {:type "flow"}]
    [:block {:type "split"}]
    ;; [:block {:type "decision"}] TODO will support
    [:block {:type "batchlet"}]
    [:block {:type "chunk"}]
    [:block {:type "reader"}]
    [:block {:type "processor"}]
    [:block {:type "writer"}]
    [:block {:type "next"}]
    [:block {:type "end"}]
    [:block {:type "fail"}]
    [:block {:type "stop"}]]

   (include-js (str "/js/job-streamer"
                    (when-not (:dev env) ".min") ".js"))))

(defn console-endpoint [config]
  (routes
   (GET "/" [] (index config))
   (POST "/job/from-xml" [:as request]
     (when-let [body (:body request)]
       (let [xml (slurp body)]
         (try
           {:headers {"Content-Type" "application/edn"}
            :body (pr-str (jobxml/xml->job xml))}
           (catch Exception e
             {:status 400
              :headers {"Content-Type" "application/edn"}
              :body (pr-str {:message (.getMessage e)})})))))
   (GET "/react/react.js" [] (-> (resource-response "cljsjs/development/react.inc.js")
                                 (content-type "text/javascript")))
   (GET "/react/react.min.js" [] (resource-response "cljsjs/production/react.min.inc.js"))
   (GET "/css/job-streamer.css" [] (-> {:body (style/build)}
                                       (content-type "text/css")))
   (GET "/version" [] (-> {:body  (clojure.string/replace (str "\"" (slurp "VERSION") "\"") "\n" "")}
                                       (content-type "text/plain")))))

