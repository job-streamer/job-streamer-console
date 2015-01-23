(ns job-streamer.console.utils)

(defmacro defblock [block-name & {:keys [colour previous-statement? next-statement?
                                         inline?
                                         fields output] :or {fields []}}]
  (let [this (gensym "this")
        n (name block-name)]
    `(aset (.-Blocks js/Blockly) ~n
           (cljs.core/clj->js {:init (fn []
                                       (cljs.core/this-as
                                        ~this
                                        ~(when colour `(.setColour ~this ~colour))
                                        ~(when previous-statement? `(.setPreviousStatement ~this true))
                                        ~(when next-statement? `(.setNextStatement ~this true))
                                        ~(when inline? `(.setInputsInline ~this true))
                                        ~(when output `(.setOutput ~this true))
                                        ~@(for [field fields]
                                           (case (:type field)
                                             :text
                                             `(-> ~this
                                                  (.appendDummyInput)
                                                  (.appendField (:label ~field))
                                                  (.appendField (js/Blockly.FieldTextInput. "") (:name ~field)))

                                             :checkbox
                                             `(-> ~this
                                                  (.appendDummyInput)
                                                  (.appendField (:label ~field))
                                                  (.appendField (js/Blockly.FieldCheckbox. "TRUE") (:name ~field)))
                                             :value-input
                                             `(-> ~this
                                                  (.appendValueInput (:name ~field))
                                                  (.appendField (:label ~field)))
                                             :statement
                                             `(.appendStatementInput ~this (:name ~field))))))}))))
