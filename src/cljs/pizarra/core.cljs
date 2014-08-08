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
         :tools {:current :freehand
                 :props {:lineWidth 4
                         :strokeStyle "#000000"}}}))

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
                           :lineJoin "round"} (get-in @app-state [:tools :props]))))
(def freehand-tool
  (reify
    ITool
    (-start [_ actions]
      (conj actions (new-line-action)))
    (-move [_ action x y]
      (update-in action [:points] conj [x y]))
    (-end [_ actions]
      actions)))

(def straight-line-tool
  (reify
    ITool
    (-start [_ actions]
      (conj actions (new-line-action)))
    (-move [_ action x y]
      (if (< (count (:points action)) 2)
        (update-in action [:points] conj [x y])
        (update-in action [:points 1] (fn [_] [x y]))))
    (-end [_ actions]
      actions)))

(def all-tools
  {:line straight-line-tool
   :freehand freehand-tool})

(defn current-tool []
  (all-tools (get-in @app-state [:tools :current])))

(defn clearContext [context]
  (let [canvas (.-canvas context)]
    (.clearRect context 0 0 (.-width canvas) (.-height canvas))))

(defn repaint [context actions]
  (clearContext context)
  (doseq [action (om/value actions)]
    (-draw action context)))

(defn start-drawing [canvas]
  (let [tool (current-tool)]
    (-> canvas
      (update-in [:actions] (partial -start tool))
      (assoc :drawing? true))))

(defn stop-drawing [canvas]
  (let [tool (current-tool)]
    (-> canvas
      (update-in [:actions] (partial -end tool))
      (assoc :drawing? false))))

(defn canvas [data owner {:keys [interact?]}]
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
             (when interact?
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
                                   (om/transact! data [:actions idx] #(-move (current-tool) % x y)))))})))
        nil))))

(defn tools-component [tools owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
               (dom/label nil "Line width")
               (dom/input #js {:type "text"
                               :value (get-in tools [:props :lineWidth])
                               :onChange (fn [e]
                                           (om/update! tools [:props :lineWidth] (js/parseInt (.-value (.-currentTarget e)))))})
               (dom/label nil "Color")
               (dom/input #js {:type "text"
                               :value (get-in tools [:props :strokeStyle])
                               :onChange (fn [e]
                                           (om/update! tools [:props :strokeStyle] (.-value (.-currentTarget e))))})
               (dom/button #js {:disabled (= (om/value (:current tools)) :freehand)
                                :onClick (fn [e]
                                           (om/update! tools [:current] :freehand))} "Freebird")
               (dom/button #js {:disabled (= (om/value (:current tools)) :line)
                                :onClick (fn [e]
                                           (om/update! tools [:current] :line))} "Line")
               ))))

(defn app-component [app owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
               (om/build tools-component (:tools app))
               (om/build canvas (:canvas app) {:opts {:interact? true}})))))

(om/root
  app-component
  app-state
  {:target (. js/document (getElementById "app"))
   :tx-listen undo/handle-transaction})

;; History management

(defn history-item [item owner {:keys [i]}]
  (reify om/IRenderState
    (render-state [_ {:keys [selected]}]
      (dom/li #js {:className (when selected "selected")
                   :onClick (fn [e]
                              (.preventDefault e)
                              (undo/jump-to-history app-state i))}
              (om/build canvas (:canvas item))))))

(defn history [undo-history owner]
  (reify
    om/IRender
    (render [_]
      (apply dom/ul nil
             (reverse (map-indexed
               (fn [i item]
                 (om/build history-item item {:opts {:i i}
                                              :state {:selected (= i (:current-idx undo-history))}}))
               (:states undo-history)))))))

(undo/push-state! @app-state)

(om/root
  history
  undo/app-history
  {:target (. js/document (getElementById "history"))})

;; Keyboard shortcuts

(.bind js/Mousetrap #js ["u" "ctrl+z" "command+z"] #(undo/undo app-state))
(.bind js/Mousetrap #js ["ctrl+r"] #(undo/redo app-state))

(.bind js/Mousetrap "i" #(swap! app-state update-in [:canvas] start-drawing))
(.bind js/Mousetrap "esc" (fn [_]
                            (swap! app-state update-in [:canvas] stop-drawing)
                            (undo/push-onto-undo-stack @app-state)))

(defn bounded-bump [f n]
  (let [m (f n)]
    (if (> m 0)
      m
      n)))

(.bind js/Mousetrap "["
       (fn [] (swap! app-state update-in [:tools :props :lineWidth] (partial bounded-bump dec))))

(.bind js/Mousetrap "]"
       (fn [] (swap! app-state update-in [:tools :props :lineWidth] (partial bounded-bump inc))))


(comment

(swap! app-state assoc-in [:canvas :actions 0 :props :strokeStyle] "#999999")
(swap! app-state update-in [:canvas :actions] pop)

(deref app-state)

(swap! app-state assoc-in [:tools :props :lineWidth] 3)
(swap! app-state assoc-in [:tools :current] :line)

(swap! app-state update-in [:canvas :dimensions] assoc :width 900 :height 300)

)
