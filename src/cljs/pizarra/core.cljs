(ns pizarra.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :as async :refer [put! chan alts! <! >!]]
            [pizarra.timemachine :as undo]))

(enable-console-print!)

(def app-state
  (atom {:canvas {:dimensions {:width 800 :height 400}
                  :actions []}}))

(defprotocol Drawable
  (-draw [this context]))

(defprotocol ITool
  (-start [this actions])
  (-move [this action x y])
  (-end [this action]))

(defrecord LineAction [points props]
  Drawable
  (-draw [this context]
    (.save context)
    (doseq [[k val] props]
      (aset context (name k) val))
    (.beginPath context)
    (let [point (first points)]
      (.moveTo context (:x point) (:y point)))
    (doseq [point points]
      (.lineTo context (:x point) (:y point)))
    (.stroke context)
    (.restore context)))

(defn new-line-action []
  (->LineAction [] {:strokeStyle "#000000"
                    :lineCap "round"
                    :lineJoin "round"
                    :lineWidth 4}))
(def line-tool
  (reify
    ITool
    (-start [_ actions]
      (conj actions (new-line-action)))
    (-move [_ action x y]
      (update-in action [:points] conj {:x x :y y}))
    (-end [_ action]
      action)))

(defn clearContext [context]
  (let [canvas (.-canvas context)]
    (.clearRect context 0 0 (.-width canvas) (.-height canvas))))

(defn repaint [context actions]
  (clearContext context)
  (doseq [action (om/value actions)]
    (-draw action context)))

(defn canvas [data owner]
  (reify
    om/IInitState
    (init-state [_]
      {:drawing? false})
    om/IDidMount
    (did-mount [_]
      (repaint (.getContext (om/get-node owner) "2d")
               (:actions data)))
    om/IDidUpdate
    (did-update [_ prev-props prev-state]
      (repaint (.getContext (om/get-node owner) "2d")
               (:actions data)))
    om/IRenderState
    (render-state [_ {:keys [drawing?]}]
      (dom/canvas
        (clj->js
          (merge (:dimensions data)
           {:onSelectStart (fn [_])
            :onMouseDown (fn [e]
                           (.preventDefault e)
                           (om/transact! data [:actions] (partial -start line-tool))
                           (om/set-state! owner :drawing? true))
            :onMouseUp (fn [_]
                         (let [idx (dec (count (:actions @data)))]
                           (om/transact! data [:actions idx]
                                         (partial -end line-tool) :add-to-undo)
                           (om/set-state! owner :drawing? false)))
            :onMouseMove (fn [e]
                           (when drawing?
                             (let [dom (om/get-node owner)
                                   idx (dec (count (:actions @data)))
                                   x (- (.-clientX e) (.-offsetTop dom))
                                   y (- (.-clientY e) (.-offsetLeft dom))]
                               (om/transact! data [:actions idx] #(-move line-tool % x y)))))}))
        nil))))

(defn history [app owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
               (when (undo/undo-is-possible)
                 (dom/button #js {:onClick (fn [_] (undo/do-undo app-state))}
                             "Undo"))
               (when (undo/redo-is-possible)
                 (dom/button #js {:onClick (fn [_] (undo/do-redo app-state))}
                             "Redo"))))))

(defn app-component [app owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
               (om/build canvas (:canvas app))
               (om/build history app)))))

(undo/init-history app-state)

(om/root
  app-component
  app-state
  {:target (. js/document (getElementById "app"))
   :tx-listen undo/handle-transaction})

(comment

(swap! app-state update-in [:canvas :actions 0 :props] assoc :strokeStyle "#999999")
(swap! app-state update-in [:canvas :actions] pop)

(deref app-state)

)
