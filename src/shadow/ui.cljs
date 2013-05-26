(ns shadow.ui
  (:require [shadow.object :as so]
            [shadow.keyboard :as kb]
            [shadow.dom :as dom]
            [goog.dom.forms :as gf]
            [clojure.string :as str]
            [cljs.reader :as reader]
            ))

(defn as-path [k]
  (if (keyword? k) [k] k))

;; behavior
(def update-on-change
  [:input/change (fn [obj attr value]
                   (so/update! obj assoc-in attr value))])

(defprotocol IInputType
  (-decode [this string] "decode from string to whatever")
  (-encode [this val] "encode from whatever to string to use in dom"))

(defn future-node
  "lazily create a dom node when attr first appears on object"
  [oref attr dom-key events-key placeholder]
  (let [node-atom (atom nil)
        attr (if (vector? attr) attr [attr])
        watch-id (gensym)
        placeholder-node (dom/build placeholder)]

    (add-watch oref watch-id (fn [_ _ _ new]
                               (when-let [value (get-in new attr)]
                                 (let [node (so/make-dom oref dom-key events-key value)]
                                   (dom/replace-node placeholder-node node))
                                 (remove-watch oref watch-id)
                                 )))

    placeholder-node
    ))

(def int-type
  (reify IInputType
    (-decode [this value]
      (when (re-find #"^\d+$" value)
        (js/parseInt value)))
    (-encode [this val]
      (str val))))

(def text-type
  (reify IInputType
    (-decode [this string]
      (.trim string))
    (-encode [this val]
      (str val))))

(def keyword-type
  (reify IInputType
    (-decode [this string]
      (keyword (.substring string 1)))
    (-encode [this val]
      (str val))))

;;; INPUT

(so/define-event :input/change "when an input is changed and its value was validated"
  [[:attr "the attr arg"]
   [:value "the new value"]
   [:input "the dom input?"]])

(so/define-event :input/blur "when the user leaves an input field, should follow :input/validated unlike :input/change will also fire when input wasnt changed"
  [[:attr "the attr arg"]
   [:value "the new value"]
   [:input "the dom input?"]])

(so/define-event :input/enter "when enter was pressed and the value validated"
  [[:attr "attr"]
   [:value "value"]
   [:input "input"]])

(so/define-event :input/validated "whenever the input was validated to be correct"
  [[:attr "attr eg. [:some :attr]"]
   [:value "the value"]
   [:dom-input "the dom node the value came from"]])

(so/define-event :input/error "whenever the input was validated to be falsy"
  [[:attr "attr eg. [:some :attr]"]
   [:msg "the error msg"]
   [:dom-input "the dom node the string value came from"]])

(so/define-event :input/force-validation "trigger a validation and thus resulting in input-validated/input-error"
  [])

(so/define-event :input/set-values "update the dom with new values"
  [[:new-values "a map containing the values to set, each input should check for its own attribute and only update if included in the map, otherwise ignore the event"]])

(so/define-event :input/set-options "update the dom with new options, probably only interesting for select boxes"
  [[:new-options "a map containing the options to set, each input should check for its own attribute and only update if included in the map, otherwise ignore the event"]])


(defn index-of [items check-fn]
  (loop [items items
         idx 0]
    (if (empty? items)
      nil
      (let [f (first items)]
        (if (check-fn f)
          idx
          (recur (rest items) (inc idx)))))))

(defn dom-name [attr]
  (str/join "." (map name attr)))

;; http://zilence.net/sj.gif
(defn group-select-options
  "transforms a list of maps into [[group-key [[value-key label] [value-key label]]] ...]"
  [items group-key value-key label-fn]
  (vec (->> items
            (group-by group-key)
            (sort-by first)
            (map (fn [[group items]]
                   [group (vec (map (fn [item]
                                         [(get item value-key)
                                          (label-fn item)])
                                       items))])
                 ))))

(defn never-has-errors [obj field value string-value]
  nil)

(defn do-validation
  "validate attr with value decoded from string-value via input
   notifies obj with [:input/error attr error-msg input] when an error is found
   notifies obj with [:input/validated attr value input] when no error is found"
  [{:keys [a parent] :as input} value string-value]
  (let [get-error-fn (so/get-type-attr parent :input/get-error never-has-errors)]
    (if-let [msg (get-error-fn parent a string-value value)]
      (do
        (so/notify! parent :input/error a msg input)
        false)
      (do
        (so/notify! parent :input/validated a value input)
        true))
      ))

(defn quick-validation [{:keys [input-type] :as this}]
  (let [sval (dom/get-value this)
        value (-decode input-type sval)]
    (do-validation this value sval)
    ))


(def dom-input-behavior
  [:input/force-validation quick-validation
   :input/set-values (fn [{:keys [a input-type] :as this} new-values]
                       (when-let [nv (get-in new-values a)]
                         (so/log "set-values" this a nv)
                         (so/update! this assoc :v nv)
                         (dom/set-value this (-encode input-type nv))
                         ))])


(defn dom-select-options-grouped [input-type options]
  ;; FIXME: can you nest more than 1 lvl? never done it.
  (for [[group-label group-options] options]
    (if (string? group-options)
      ;; single option
      [:option {:value (-encode type group-label)} group-options]
      ;; optgroup
      [:optgroup {:label group-label}
       (for [[value label] group-options]
         [:option {:value (-encode type value)}
          (str label)])])))

(defn dom-select-options-flat [input-type options]
  (for [[value label] options
        :let [label (or label value)]]
    [:option {:value (-encode input-type value)} (str label)]))

(defn dom-select-options [{:keys [input-type group] :as this} options]
  (if group
    (dom-select-options-grouped input-type options)
    (dom-select-options-flat input-type options)
    ))

(so/define ::dom-select
  :dom (fn [{:keys [attrs options] :as this}]
         [:select attrs (dom-select-options this options)])

  :behaviors [dom-input-behavior]

  :on [:input/set-options (fn [{:keys [a] :as this} new-options]
                            (when-let [nv (get-in new-options a)]
                              (so/update! this assoc :options nv) ;; dont really need to do this?
                              (let [curval (dom/get-value this)]
                                (dom/reset this)
                                (doseq [opt (dom-select-options this nv)]
                                  (dom/append this opt))
                                (dom/set-value this curval))))]

  :dom/events [:change  (fn [{:keys [a parent input-type] :as this} ev]
                          (let [sval (dom/get-value this)
                                value (-decode input-type sval)]
                            (when (do-validation this value sval)
                              (so/notify! parent :input/change a value this)))
                          )])

(defn dom-select
  ([obj attr type options]
     (dom-select obj attr type options {}))
  ([obj attr type options select-attrs]
     (when-not (satisfies? IInputType type)
       (throw (ex-info "dom select type must support protocol InputType" {:type type})))

     (when-not (vector? options)
         (throw (ex-info "select options should be a vector" {:options options})))

     ;; options should be [[value "label"] [value "label"]

     (let [a (as-path attr)
           v (get-in obj attr)]

       (so/create ::dom-select
                  {:parent obj
                   :attrs select-attrs
                   :options options
                   :a a
                   :v v
                   :input-type type}))))

(defn dom-select-grouped
  ([obj attr type options]
     (dom-select obj attr type options {:group true}))
  ([obj attr type options select-attrs]
     (dom-select obj attr type options (assoc select-attrs :group true))))

(defn process-dom-input [{:keys [parent a input-type] :as this} ev-type]
  (let [sval (dom/get-value this)
        value (-decode input-type sval)]
    (when (do-validation this value sval)
      (so/notify! parent ev-type a value this))))

(so/define ::dom-input
  :dom (fn [{:keys [attrs] :as this}]
         [:input (merge {:type "text"} attrs)])

  :behaviors [dom-input-behavior]

  :dom/events [:blur (fn [{:keys [input-type capture] :as this} ev]
                       (if (contains? capture :blur)
                         (process-dom-input this :input/blur)
                         ;; special case processsing for empty fields since
                         ;; change doesnt fire if you enter an empty field
                         ;; and leave it empty (but I still want validation to fire)
                         (let [sval (dom/get-value this)]
                           (when (= sval "")
                             (let [value (-decode input-type sval)]
                               (do-validation this value sval))))))

               :change (fn [this]
                         (process-dom-input this :input/change))])

(defn dom-input [obj attr type attrs]
  (when-not (satisfies? IInputType type)
    (throw (ex-info "dom input type must support protocol InputType" {:type type})))

  (let [attr (as-path attr)
        capture (:capture attrs #{:change})
        init-val (get-in obj attr)
        init-sval (:value attrs)
        init-sval (if (nil? init-val)
                    ""
                    (-encode type init-val))

        input-attrs (-> attrs
                        (dissoc :capture)
                        (assoc :value init-sval
                               :name (dom-name attr)))]

    (so/create ::dom-input {:parent obj
                            :attrs input-attrs
                            :capture capture
                            :input-type type
                            :a attr})))

(defn dom-checkbox [obj attr attrs]
  (let [attr (as-path attr)
        init-checked (get-in obj attr)
        input (dom/build [:input (assoc attrs :type "checkbox")])]

    (dom/check input init-checked)

    (when (:bind attrs)
      (so/bind-change obj attr
                       (fn [old new]
                         (dom/check input new)
                         )))

    (dom/on input :change (fn [e]
                            (so/notify! obj :input/change attr (dom/checked? input) input)))

    input
    ))

(so/define ::dom-textarea
  :dom (fn [{:keys [attrs v] :as this}]
         [:textarea attrs v])

  :behaviors [dom-input-behavior]

  :dom-events [:change (fn [{:keys [a parent input-type] :as this} e]
                         (let [sval (dom/get-value this)
                               value (-decode input-type sval)]
                           (when (do-validation this value sval)
                             (so/notify! parent :input/change a value this))))])

(defn dom-textarea
  [obj attr type attrs]
  (when-not (satisfies? IInputType type)
    (throw (ex-info "dom input type must support protocol InputType" {:type type :attr attr :attrs attrs})))

  (let [a (as-path attr)
        v (get-in obj a "")]
    (so/create ::dom-textarea {:parent obj
                               :a a
                               :v v
                               :input-type type
                               :attrs attrs
                               })))

(def timeouts (atom {}))

(defn timeout [delay callback]
  (.setTimeout js/window callback delay))

(defn keyed-timeout
  ([key callback] (keyed-timeout key callback 3000))
  ([key callback time-ms]
     (let [cur (get @timeouts key)]
       (when cur
         (.clearTimeout js/window cur))
       (let [timeout-fn (fn []
                          (swap! timeouts dissoc key)
                          (callback))
             timeout-id (.setTimeout js/window timeout-fn time-ms)]
         (swap! timeouts assoc key timeout-id)
         ))))

(def local-storage (.-localStorage js/window))

;; (def my-atom (ui/store-locally (atom defaults) "key"))
;; (def my-atom (atom defaults)
;; (ui/store-locally my-atom "key")

(defn store-locally [atm name]
  (let [stored-value (aget local-storage name)]

    (when stored-value
      (reset! atm (reader/read-string stored-value)))

    (add-watch atm :store-locally
               (fn [_ _ _ new]
                 (aset local-storage name (pr-str new))
                 ))

    atm
    ))



(so/define-event :nav-selected
  "when a nav item is clicked"
  [[:nav-value "value of the tab"]])

(so/define ::navbar
  :dom (fn [{:keys [navbar default] :as this}]
         [:ul.navbar
          (for [[nav-id nav-label] navbar
                :let [nav-value (-> nav-id str (.substring 1))
                      li-attr {:data-nav nav-value}
                      li-attr (if (= nav-id default)
                                (assoc li-attr :class "active")
                                li-attr)]]
            [:li li-attr
             [:a nav-label]]
            )])

  :dom/events [[:click "li"] (fn [this e el]
                               (doseq [t (dom/children this)]
                                 (dom/remove-class t "active"))
                               (dom/add-class el "active")

                               (let [nav-value (keyword (dom/data el :nav))]
                                 (so/notify! (:parent this) :nav-selected nav-value)
                                 ))])

(defn navbar [parent nav-data default]
  (let [pairs (vec (partition 2 nav-data))]
    (so/create ::navbar {:parent parent
                          :navbar pairs
                          :default default})))







