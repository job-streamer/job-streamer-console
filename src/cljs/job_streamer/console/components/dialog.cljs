(ns job-streamer.console.components.dialog
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [clojure.string :as string]
            [sablono.core :as html :refer-macros [html]]))

(enable-console-print!)

(defcomponent dangerously-action-dialog [app owner {:keys [ok-handler cancel-handler answer delete-type]}]
  (init-state [_]
    {:typed nil
     :unmatch false})
  (render-state [_ {:keys [typed unmatch]}]
    (html
     [:div.ui.dimmer.modals.page.transition.visible.active
      [:div.ui.modal.scrolling.transition.visible.active
       [:div.header "Are you ABSOLUTELY sure?"]
       [:div.ui.warning.message
        "Unexpected bad things will happen if you don't read this!"]
       [:div.content
        [:p "This action" [:strong "CANNOT"] " be undone."]
        [:p "Please type in the name of the repository to confirm."]
        [:div.ui.form
         [:div.field (when unmatch {:class "error"})
          [:input {:type "text"
                   :value typed
                   :on-change (fn [e]
                                (om/update-state! owner #(assoc %
                                                                :typed (.. e -target -value)
                                                                :unmatch false)))}]]]]
       [:div.actions
        [:div.ui.two.column.grid
         [:div.left.aligned.column
          [:button.ui.black.deny.button
           {:on-click (fn [e]
                        (cancel-handler))}
           "Cancel"]]
         [:div.right.aligned.column
          [:button.ui.red.danger.button
           {:on-click (fn [e]
                        (if (= typed answer)
                          (ok-handler)
                          (om/set-state! owner :unmatch true)))}
           (string/join ["I understand the consequences, delete this " delete-type])]]]]]])))
