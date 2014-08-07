(ns pizarra.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :as async :refer [put! chan alts! <! >!]]
            [pizarra.timemachine :as undo]))

(enable-console-print!)

(def app-state
  (atom {:canvas {:drawing? false
                  :dimensions {:width 800 :height 400}
                  :actions []}
         :tools {:lineWidth 4
                 :strokeStyle "#000000" }}))

(defprotocol Drawable
  (-draw [this context]))

(defprotocol ITool
  (-start [this actions])
  (-move [this action x y])
  (-end [this actions]))

(defrecord LineAction [points props]
  Drawable
  (-draw [this context]
    (.save context)
    (doseq [[k val] props]
      (aset context (name k) val))
    (.beginPath context)
    (let [[x y] (first points)]
      (.moveTo context x y))
    (doseq [[x y] points]
      (.lineTo context x y))
    (.stroke context)
    (.restore context)))

(defn new-line-action []
  (->LineAction [] (merge {:lineCap "round"
                           :lineJoin "round"} (:tools @app-state))))
(def line-tool
  (reify
    ITool
    (-start [_ actions]
      (conj actions (new-line-action)))
    (-move [_ action x y]
      (update-in action [:points] conj [x y]))
    (-end [_ actions]
      actions)))

(defn clearContext [context]
  (let [canvas (.-canvas context)]
    (.clearRect context 0 0 (.-width canvas) (.-height canvas))))

(defn repaint [context actions]
  (clearContext context)
  (doseq [action (om/value actions)]
    (-draw action context)))

(defn start-drawing [canvas]
  (-> canvas
    (update-in [:actions] (partial -start line-tool))
    (assoc :drawing? true)))

(defn stop-drawing [canvas]
  (-> canvas
    (update-in [:actions] (partial -end line-tool))
    (assoc :drawing? false)))

(defn canvas [data owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (repaint (.getContext (om/get-node owner) "2d")
               (:actions data)))
    om/IDidUpdate
    (did-update [_ prev-props prev-state]
      (repaint (.getContext (om/get-node owner) "2d")
               (:actions data)))
    om/IRender
    (render [_]
      (dom/canvas
        (clj->js
          (merge (:dimensions data)
           {:onSelectStart (fn [_])
            :onMouseDown (fn [e]
                           (.preventDefault e)
                           (om/transact! data [] start-drawing))
            :onMouseUp (fn [_]
                         (om/transact! data [] stop-drawing :add-to-undo))
            :onMouseMove (fn [e]
                           (when (:drawing? @data)
                             (let [dom (om/get-node owner)
                                   idx (dec (count (:actions @data)))
                                   x (- (.-pageX e) (.-offsetLeft dom))
                                   y (- (.-pageY e) (.-offsetTop dom))]
                               (om/transact! data [:actions idx] #(-move line-tool % x y)))))}))
        nil))))

(defn tools-component [tools owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
               (apply dom/select
                      #js {:value (:lineWidth tools)
                           :onChange (fn [e]
                                       (let [newval (.-value (.-currentTarget e))]
                                         (om/update! tools [:lineWidth] newval)))}
                      (map (fn [i]
                             (dom/option #js {:value i} i))
                           (range 1 11)))
               (dom/input #js {:type "text"
                               :value (:strokeStyle tools)
                               :onChange (fn [e]
                                           (om/update! tools [:strokeStyle] (.-value (.-currentTarget e))))})))))

(defn app-component [app owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
               (om/build tools-component (:tools app))
               (om/build canvas (:canvas app))))))

(om/root
  app-component
  app-state
  {:target (. js/document (getElementById "app"))
   :tx-listen undo/handle-transaction})

;; History management

(defn history [undo-history owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
               (dom/button #js {:onClick (fn [_] (undo/do-undo app-state))
                                :disabled (not (undo/undo-is-possible))}
                           "Undo")

               (dom/button #js {:onClick (fn [_] (undo/do-redo app-state))
                                :disabled (not (undo/redo-is-possible))}
                           "Redo")))))

(undo/init-history app-state)

(om/root
  history
  undo/app-history
  {:target (. js/document (getElementById "history"))})

;; Keyboard shortcuts

(.bind js/Mousetrap #js ["u" "ctrl+z" "command+z"] #(undo/do-undo app-state))
(.bind js/Mousetrap #js ["ctrl+r"] #(undo/do-redo app-state))

(.bind js/Mousetrap "i" #(swap! app-state update-in [:canvas] start-drawing))
(.bind js/Mousetrap "esc" (fn [_]
                            (swap! app-state update-in [:canvas] stop-drawing)
                            (undo/push-onto-undo-stack @app-state)))

(defn bind-number [nkey width]
  (.bind js/Mousetrap
         (str nkey)
         (fn []
           (swap! app-state update-in [:tools] assoc :lineWidth width))))

(doseq [i (range 1 10)]
  (bind-number i i))
(bind-number 0 10)

(comment

(swap! app-state update-in [:canvas :actions 0 :props] assoc :strokeStyle "#999999")
(swap! app-state update-in [:canvas :actions] pop)

(deref app-state)

(swap! app-state update-in [:tools] assoc :lineWidth 3)

(swap! app-state update-in [:canvas :dimensions] assoc :width 900 :height 300)

)
