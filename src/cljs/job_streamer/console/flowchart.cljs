(ns job-streamer.console.flowchart
  (:require [job-streamer.console.api :as api]
            [bouncer.core :as b]
            [bouncer.validators :as v])
  (:import [goog Uri]))

(def control-bus-url (.. js/document
                         (querySelector "meta[name=control-bus-url]")
                         (getAttribute "content")))

(def app-name "default")

(def initial-diagram
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<bpmn:definitions xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                         "xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                         "xmlns:jsr352=\"http://jsr352/\" "
                         "xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" "
                         "xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\" "
                         "targetNamespace=\"http://bpmn.io/schema/bpmn\" "
                         "id=\"Definitions_1\">"
         "<jsr352:Job id=\"Job_1\" isExecutable=\"false\">"
           "<bpmn:startEvent id=\"StartEvent_1\"/>"
         "</jsr352:Job>"
         "<bpmndi:BPMNDiagram id=\"BPMNDiagram_1\">"
           "<bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"Job_1\">"
             "<bpmndi:BPMNShape id=\"_BPMNShape_StartEvent_2\" bpmnElement=\"StartEvent_1\">"
                 "<dc:Bounds height=\"36.0\" width=\"36.0\" x=\"173.0\" y=\"102.0\"/>"
             "</bpmndi:BPMNShape>"
           "</bpmndi:BPMNPlane>"
         "</bpmndi:BPMNDiagram>"
       "</bpmn:definitions>"))

(defn save-job-control-bus [job job-name]
  (if-let [messages (first (b/validate job :job/name [v/required [v/matches #"^[\w\-]+$"]]))]
    ;; TODO: Handler error.
    (.error js/console (str messages))
    ;; (om/set-state! owner :message {:class "error"
    ;;                                :header "Invalid job format"
    ;;                                :body [:ul
    ;;                                       (for [msg (->> messages
    ;;                                                      (postwalk #(if (map? %) (vals %) %))
    ;;                                                      flatten)]
    ;;                                         [:li msg])]})
    (api/request (str "/" app-name (if job-name (str "/job/" job-name) "/jobs"))
                 (if job-name :PUT :POST)
                 job
                 {:handler (fn [_] (.close js/window))
                  :forbidden-handler (fn [response]
                                       (.error js/console "Forbidden.")
                                       ;; TODO: Handler error.
                                       ;; (om/set-state! owner :message {:class "error"
                                       ;;                                :header "Save failed"
                                       ;;                                :body [:p "You are unauthorized save job."]})
                                       )
                  :error-handler (fn [response]
                                   (.error js/console "Something wrong.")
                                   ;; TODO: Handler error.
                                   ;; (om/set-state! owner :message {:class "error"
                                   ;;                                :header "Save failed"
                                   ;;                                :body [:p "Somethig is wrong."]})
                                   )})))

(defn save-job [job-name xml svg]
  (let [uri (goog.Uri. (.-href js/location))
        port (.getPort uri)]
    (api/request (str (.getScheme uri) "://" (.getDomain uri) (when port (str ":" port)) "/job/from-xml")
                 :POST
                 xml
                 {:handler (fn [response]
                             (if job-name
                               (save-job-control-bus (assoc response
                                                       :job/bpmn-xml-notation xml
                                                       :job/svg-notation svg) job-name)
                               (api/request (str "/" app-name "/job/" (:job/name response))
                                            {:handler (fn[_]
                                                        (.error js/console (str "Job name:" (:job/name response) " is already used."))
                                                        ;; TODO: Display error message.
                                                        ;; (om/set-state! owner :message {:class "error"
                                                        ;;                                :header "Name Already Used"
                                                        ;;                                :body [:p "This job name is already used"]})
                                                        )
                                             :error-handler (fn[_] (save-job-control-bus (assoc response
                                                                                           :job/bpmn-xml-notation xml
                                                                                           :job/svg-notation svg) job-name))})))
                  :error-handler (fn [response]
                                   ;; TODO: Display error message.
                                   ;; (om/set-state! owner :message {:class "error"
                                   ;;                                :header "Invalid job format"
                                   ;;                                :body [:p (:message response)]})
                                   )
                  :format :xml})))

(defn render []
  (let [modeler js/window.bpmnjs ;; Get via global scope.
        job-name (.. js/document
                     (querySelector "meta[name=job-name]")
                     (getAttribute "content"))
        import-xml-cb (fn [err]
                        (if err
                          (.error js/console (str "something went wrong:" err))
                          (.. modeler
                              (get "canvas")
                              (zoom "fit-viewport"))))
        save-job-handler (fn [e]
                           (.preventDefault e)
                           (.stopPropagation e)
                           (.saveSVG modeler
                                     (fn [err svg]
                                       (.saveXML modeler
                                                 #js {:format true}
                                                 (fn [err xml]
                                                   (save-job job-name xml svg))))))]
    ;; Get bpmn-xml from control-bus and import to modeler.
    (if job-name
      (api/request (str "/" app-name "/job/" job-name)
                   {:handler (fn [{:keys [job/bpmn-xml-notation]}]
                               (.importXML modeler bpmn-xml-notation import-xml-cb))
                    :error-handler #(.error js/console (str "something went wrong with load job:" %))})
      (.importXML modeler initial-diagram import-xml-cb))

    ;; Add click behabiour to the save link.
    (.addEventListener js/window
                       "DOMContentLoaded"
                       (fn []
                         (let [link (.getElementById js/document "save-job")]
                           (aset link "className" "active")
                           (.addEventListener link "click" save-job-handler))))))
