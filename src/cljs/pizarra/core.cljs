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
         :tools {:snap false
                 :current :freehand
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

(defn rectangle [[[x0 y0] [x1 y1]]]
  [[x0 y0] [x1 y0] [x1 y1] [x0 y1]])

(defrecord RectangleAction [points props]
  Drawable
  (-draw [this context]
    (.save context)
    (doseq [[k val] props]
      (aset context (name k) val))
    (.beginPath context)
    (let [[x0 y0] (first points)]
      (.moveTo context x0 y0)
      (doseq [[x y] (rectangle points)]
        (.lineTo context x y))
      (.lineTo context x0 y0))
    (.stroke context)
    (.restore context)))

(defrecord CircleAction [origin radius props]
  Drawable
  (-draw [this context]
    (.save context)
    (doseq [[k val] props]
      (aset context (name k) val))
    (.beginPath context)
    (let [[x y] origin]
      (.arc context x y radius (* 2 js/Math.PI) false))
    (.stroke context)
    (.restore context)))

(defn new-line-action []
  (->LineAction [] (merge {:lineCap "round"
                           :lineJoin "round"} (get-in @app-state [:tools :props]))))
(defn new-rectangle-action []
  (->RectangleAction [] (merge {:lineCap "round"
                             :lineJoin "round"} (get-in @app-state [:tools :props]))))
(defn new-circle-action []
  (->CircleAction nil 0 (get-in @app-state [:tools :props])))

(def freehand-tool
  (reify
    ITool
    (-start [_ actions]
      (conj actions (new-line-action)))
    (-move [_ action x y]
      (update-in action [:points] conj [x y]))
    (-end [_ actions]
      actions)))

(defn slope [[[x0 y0] [x1 y1]]]
  (/ (- y1 y0) (- x1 x0)))

(defn distance [[x0 y0] [x1 y1]]
  (let [a (- x1 x0)
        b (- y1 y0)]
    (Math/sqrt (+ (* a a) (* b b)))))

(defn adjusted-slope [m]
  (cond
    (< 0.5 m 2) 1
    (< -0.5 m 0.5) 0
    (< -2 m -0.5) -1
    :else ::infinity))

(defn adjust-points [m [[x0 y0] [x1 y1]]]
  (if (= m ::infinity)
    [[x0 y0] [x0 y1]]
    (let [new-y (+ (* m (- x1 x0)) y0)]
      [[x0 y0] [x1 new-y]])))

(defn snap [points]
  (if (get-in @app-state [:tools :snap])
    (let [m (adjusted-slope (slope points))]
      (adjust-points m points))
    points))

(defn next-point [points x y f]
  (if (zero? (count points))
    [[x y] [x y]]
    (let [points (assoc points 1 [x y])]
      (f points))))

(def straight-line-tool
  (reify
    ITool
    (-start [_ actions]
      (conj actions (new-line-action)))
    (-move [_ action x y]
      (let [points (next-point (:points action) x y snap)]
        (assoc action :points points)))
    (-end [_ actions]
      actions)))

(def rectangle-tool
  (reify
    ITool
    (-start [_ actions]
      (conj actions (new-rectangle-action)))
    (-move [_ action x y]
      (let [points (next-point (:points action) x y identity)]
        (assoc action :points points)))
    (-end [_ actions]
      actions)))

(def circle-tool
  (reify
    ITool
    (-start [_ actions]
      (conj actions (new-circle-action)))
    (-move [_ action x y]
      (if-let [o (:origin action)]
        (assoc action :radius (distance o [x y]))
        (assoc action :origin [x y])))
    (-end [_ actions]
      actions)))

(def all-tools
  {:line straight-line-tool
   :freehand freehand-tool
   :rectangle rectangle-tool
   :circle circle-tool})

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

(defn tool-button [tools owner {:keys [tool label]}]
  (reify
    om/IRender
    (render [_]
      (dom/button #js {:disabled (= (om/value (:current tools)) tool)
                       :onClick (fn [e]
                                  (om/update! tools [:current] tool))} label))))

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
               (om/build tool-button tools {:opts {:tool :freehand :label "Freebird"}})
               (om/build tool-button tools {:opts {:tool :line :label "Line"}})
               (om/build tool-button tools {:opts {:tool :rectangle :label "Rectangle"}})
               (om/build tool-button tools {:opts {:tool :circle :label "Circle"}})))))

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

(.addEventListener js/document "keydown" (fn [e]
                                           (when (= (.-which e) 16) ;; shift
                                             (swap! app-state assoc-in [:tools :snap] true))))

(.addEventListener js/document "keyup" (fn [e]
                                           (when (= (.-which e) 16) ;; shift
                                             (swap! app-state assoc-in [:tools :snap] false))))

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

(deref app-state)

(swap! app-state update-in [:canvas :dimensions] assoc :width 900 :height 300)

(rectangle [[587 229] [554 333]])

)
