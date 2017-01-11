(ns job-streamer.console.flowchart
  (:require [job-streamer.console.api :as api]))

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

(defn- save-job-control-bus [job-name xml svg]
  (let [job {:job/name (or job-name (str (random-uuid))) ;; TODO: Name job uuid temporary.
             :job/bpmn-xml-notation xml
             :job/svg-notation svg}]
    (api/request (str "/" app-name (if job-name (str "/job/" job-name) "/jobs"))
                 (if job-name :PUT :POST)
                 job
                 {:handler (fn [response]
                             (.close js/window))
                  :error-handler #(.error js/console (str "something went wrong with save job:" %))})))

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
                                                   (save-job-control-bus job-name xml svg))))))]
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
