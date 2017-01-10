(ns job-streamer.console.flowchart)

(enable-console-print!)

(defn render []
  (let [CustomModeler            js/window.CustomModeler
        propertiesPanelModule    js/window.propertiesPanelModule
        propertiesProviderModule js/window.propertiesProviderModule
        camundaModdleDescriptor  js/window.camundaModdleDescriptor
        jsr352ModdleDescriptor   js/window.jsr352ModdleDescriptor]
    (println "Render flowchart.")))