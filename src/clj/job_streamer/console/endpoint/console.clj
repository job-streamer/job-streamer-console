(ns job-streamer.console.endpoint.console
  (:require [compojure.core :refer :all]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5 include-css include-js]]
            [ring.util.response :refer [resource-response content-type header redirect]]
            [environ.core :refer [env]]
            [clj-http.client :as client]
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

(defn login-view [_ request]
  (layout request
    [:div.ui.fixed.inverted.teal.menu
      [:div.header.item [:img.ui.image {:alt "JobStreamer" :src "/img/logo.png"}]]]
    [:div.main.grid.content.full.height
      [:div.ui.middle.aligned.center.aligned.login.grid
       [:div.column
        [:h2.ui.header
         [:div.content
          [:img.ui.image {:src "/img/logo.png"}]]]
        [:form.ui.large.login.form
         (merge {:method "post" :action "/login"}
                (when (get-in request [:params :error])
                  {:class "error"}))
         [:div.ui.stacked.segment
          [:div.ui.error.message
           (map #(vec [:p %]) (get-in request [:params :error]))]
          [:div.field
           [:div.ui.left.icon.input
            [:i.user.icon]
            [:input {:type "text" :name "username" :placeholder "User name"}]]]
          [:div.field
           [:div.ui.left.icon.input
            [:i.lock.icon]
            [:input {:type "password" :name "password" :placeholder "Password"}]]]
          [:button.ui.fluid.large.teal.submit.button {:type "submit"} "Login"]]]]]]))

(defn login [{:keys [control-bus-url-from-backend]} username password]
  (try
    (let [{:keys [status body cookies]}
          (client/post (str control-bus-url-from-backend "/auth")
                       {:content-type :edn
                        :body (pr-str {:user/id username
                                       :user/password password})
                        :throw-exceptions false})]
      (if (== status 201)
        (let [token (:token (read-string body))]
          (log/infof "Authentificated with token: %s." token)
          (as-> cookies c
                (select-keys c ["ring-session"])
                (update c "ring-session" #(select-keys % [:value :domain :path :secure :http-only :max-age :expires]))
                (assoc {} :cookies c)))
        (read-string body)))
    (catch java.net.ConnectException ex
      (log/error "A Control bus is NOT found.")
      {:messages ["A Control bus is NOT found."]})))

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
    [:div.main.grid.content.full.height
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
    (include-js "/js/jsr-352.js"
                (str "/js/flowchart" (when-not (:dev env) ".min") ".js"))]))

(defn console-endpoint [config]
  (routes
   (GET "/login" request (login-view config request))
   (POST "/login" {{:keys [username password appname]} :params :as request}
     (let [{:keys [cookies messages]} (login config username password)]
       (if cookies
         (-> (redirect (get-in request [:query-params "next"] "/"))
             (assoc :cookies cookies))
         (login-view config (assoc-in request [:params :error] messages)))))

   (GET ["/:app-name/jobs/new" :app-name #".*"]
        [app-name]
        (flowchart config nil))
   (GET ["/:app-name/job/:job-name/edit" :app-name #".*" :job-name #".*"]
        [app-name job-name]
        (flowchart config job-name))

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

