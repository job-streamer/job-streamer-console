(ns job-streamer.console.endpoint.console
  (:require [compojure.core :refer :all]
            [hiccup.page :refer [html5 include-css include-js]]
            [ring.util.response :refer [resource-response content-type header]]
            [environ.core :refer [env]]
            [clojure.tools.logging :as log]
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
   (include-js (str "/js/job-streamer"
                    (when-not (:dev env) ".min") ".js"))))

(defn login-view [config]
  (layout config
   [:div#login.ui.full.height.page]
   (include-js (str "/js/job-streamer"
                    (when-not (:dev env) ".min") ".js"))))

(defn flowchart [{:keys [control-bus-url]} job-name]
  (html5
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1"}]
     [:meta {:name "control-bus-url" :content control-bus-url}]
     [:meta {:name "job-name" :content job-name}]
     (include-css
       "//cdn.jsdelivr.net/semantic-ui/2.0.3/semantic.min.css"
       "/css/vendors/vis.min.css"
       "/css/job-streamer.css"
       "/css/diagram-js.css"
       "/vendor/bpmn-font/css/bpmn.css"
       "/vendor/bpmn-font/css/bpmn-embedded.css"
       "/css/jsr-352.css")]
   [:body
    [:div.ui.fixed.inverted.teal.menu
      [:div.header.item [:img.ui.image {:alt "JobStreamer" :src "/img/logo.png"}]]]
    [:div.main.grid.full.height
     [:div.ui.grid
      [:div.ui.row
       [:div.ui.column
        [:h2.ui.violet.header
         [:div.content
          (or job-name "New")]]
          [:div#message.ui.floating.message.hidden]]]]
     [:div#canvas]
     [:div#js-properties-panel {:style "top: 47px;"}]
     [:ul.buttons
      [:li [:button.ui.positive.button.submit.disabled {:id "save-job" :type "button"} [:i.save.icon] "Save"]]
      [:li [:button.ui.black.deny.button {:id "cancel" :type "button" :onClick "window.close();"} "Cancel"]]]]
    (include-js "/js/jsr-352.min.js"
                (str "/js/flowchart" (when-not (:dev env) ".min") ".js"))]))

(defn console-endpoint [config]
  (routes
   (GET ["/:app-name/jobs/new" :app-name #".*"]
        [app-name]
        (flowchart config nil))
   (GET ["/:app-name/job/:job-name/edit" :app-name #".*" :job-name #".*"]
        [app-name job-name]
        (flowchart config job-name))

   (GET "/" [] (index config))
   (GET "/login" [] (login-view config))
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

