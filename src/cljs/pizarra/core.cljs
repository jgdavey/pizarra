(ns pizarra.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cognitect.transit :as t]
            [cljs.reader :refer [read-string]]
            [cljs.core.async :as async :refer [put! chan alts! <! >!]]
            [pizarra.timemachine :as undo]))

(enable-console-print!)

(.initializeTouchEvents js/React true)

(def serialize pr-str)
(def deserialize read-string)

(defonce peerjs-api-key "3s8narvuzddkj4i")

(defonce app-state
  (atom {:canvas {:dimensions {:width 800 :height 400}
                  :actions []
                  :current-action nil}
         :rtc {:peer-id nil
               :status "not connected"
               :connect-to-peer nil}
         :tools {:snap false
                 :current :freehand
                 :props {:lineWidth 4
                         :strokeStyle "#000000"}}}))

(declare push-action! encode decode)

(defn store-connection! [conn]
  (.on conn "data" (comp push-action! decode))
  (.on conn "open" (fn []
                     (swap! app-state assoc-in [:rtc :connected?] true)
                     (swap! app-state assoc-in [:rtc :status] "connected")))
  (swap! app-state assoc-in [:rtc :status] "initializing")
  (swap! app-state assoc-in [:rtc :connection] conn))

(defonce peer-client
  (let [c (js/Peer. #js {:key peerjs-api-key})]
    (.on c "open"
       (fn [id] (swap! app-state assoc-in [:rtc :peer-id] id)))
    (.on c "connection" store-connection!)
    c))

; send (data)
; on ("data", fn...)

(defprotocol Drawable
  (-draw [this context]))

(defprotocol ITool
  (-start [this])
  (-move [this action x y]))

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


;; Serialization
(def ^:private -writer
  (letfn [(keysfn [& keys] #(select-keys % keys))
          (handler [name f] (t/write-handler (constantly name) f))]
    (t/writer :json
              {:handlers
               {LineAction      (handler "line" (keysfn :points :props))
                RectangleAction (handler "rect" (keysfn :points :props))
                CircleAction    (handler "circle" (keysfn :origin :radius :props))}})))

(def ^:private -reader
  (t/reader :json {:handlers {"line"   map->LineAction
                              "rect"   map->RectangleAction
                              "circle" map->CircleAction}}))

(defn encode [state]
  (t/write -writer state))

(defn decode [json-string]
  (t/read -reader json-string))



;; TOOLS

(def freehand-tool
  (reify
    ITool
    (-start [_]
      (new-line-action))
    (-move [_ action x y]
      (update-in action [:points] conj [x y]))))

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
    (-start [_] (new-line-action))
    (-move [_ action x y]
      (let [points (next-point (:points action) x y snap)]
        (assoc action :points points)))))

(def rectangle-tool
  (reify
    ITool
    (-start [_] (new-rectangle-action))
    (-move [_ action x y]
      (let [points (next-point (:points action) x y identity)]
        (assoc action :points points)))))

(def circle-tool
  (reify
    ITool
    (-start [_] (new-circle-action))
    (-move [_ action x y]
      (if-let [o (:origin action)]
        (assoc action :radius (distance o [x y]))
        (assoc action :origin [x y])))))

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
  (doseq [action actions]
    (-draw action context)))

(defn start-drawing [canvas]
  (let [tool (current-tool)]
    (-> canvas
      (assoc :current-action (-start tool)))))

(defn stop-drawing [canvas]
  (let [tool (current-tool)]
    (-> canvas
      (update-in [:actions] conj (:current-action canvas))
      (assoc :current-action nil))))

(defn get-coords [dom e]
  (let [x (- (.-pageX e) (.-offsetLeft dom))
        y (- (.-pageY e) (.-offsetTop dom))]
    [x y]))

(defn canvas [data owner {:keys [interact?]}]
  (let [drawit #(repaint (.getContext (om/get-node owner) "2d")
                         (if-let [ca (om/value (:current-action data))]
                           (conj (om/value (:actions data)) ca)
                           (om/value (:actions data))))]
    (reify
      om/IDidMount
      (did-mount [_] (drawit))
      om/IDidUpdate
      (did-update [_ prev-props prev-state] (drawit))
      om/IRender
      (render [_]
        (dom/canvas
          (clj->js
            (merge (:dimensions data)
             (when interact?
               {:onSelectStart (fn [_])
                :onTouchStart (fn [e]
                                (.preventDefault e)
                                (om/transact! data [] start-drawing))
                :onMouseDown (fn [e]
                               (.preventDefault e)
                               (om/transact! data [] start-drawing))
                :onMouseUp  (fn [_] (om/transact! data [] stop-drawing :add-to-undo))
                :onTouchEnd (fn [_] (om/transact! data [] stop-drawing :add-to-undo))
                :onTouchMove (fn [e]
                               (when (:current-action @data)
                                 (let [dom (om/get-node owner)
                                       [x y] (get-coords dom (aget (.-touches e) 0))]
                                   (om/transact! data [:current-action] #(-move (current-tool) % x y)))))
                :onMouseMove (fn [e]
                               (when (:current-action @data)
                                 (let [dom (om/get-node owner)
                                       [x y] (get-coords dom e)]
                                   (om/transact! data [:current-action] #(-move (current-tool) % x y)))))})))
    nil)))))

(defn rtc-view [data _]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
               (dom/p nil
                      "Your Peer ID is "
                      (dom/strong nil (:peer-id data)))
               (if (:connection data)
                 (dom/p nil
                        "Connected to peer. Status: " (:status data))
                 (dom/input #js {:type "text"
                                 :value (:connect-to-peer data)
                                 :onChange (fn [e]
                                             (om/update! data [:connect-to-peer] (.. e -currentTarget -value)))}))))))

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
               (om/build rtc-view (:rtc app))
               (om/build tools-component (:tools app))
               (om/build canvas (:canvas app) {:opts {:interact? true}})))))

(defn push-state! [state]
  (undo/push-state! (get-in state [:canvas])))

(defn push-action!
  "push an action into the app state and add the new app state to history"
  [action]
  (swap! app-state update-in [:canvas :actions] conj action)
  (push-state! @app-state))

(defn send-to-peer [tx-data]
  (when (get-in @app-state [:rtc :connected?])
    (let [conn (get-in @app-state [:rtc :connection])
          action (peek (get-in tx-data [:new-value :actions]))]
      (.send conn (encode action)))))

(defn transact-listen [tx-data root-cursor]
  (when (= (:path tx-data) [:rtc :connect-to-peer])
    (store-connection! (.connect peer-client (:new-value tx-data))))
  (when (= (:tag tx-data) :add-to-undo)
    (send-to-peer tx-data)
    (push-state! (:new-state tx-data))))

;; History management
(defn- reset-to-history! [idx]
  (when-let [s (undo/jump-to-history! idx)]
    (swap! app-state assoc-in [:canvas] s)))

(defn- undo! []
  (when-let [s (undo/undo)]
    (swap! app-state assoc-in [:canvas] s)))

(defn- redo! []
  (when-let [s (undo/redo)]
    (swap! app-state assoc-in [:canvas] s)))

(defn push-state! [state]
  (undo/push-state! (get-in state [:canvas])))

(push-state! @app-state)

(defn history-item [item owner {:keys [i]}]
  (reify om/IRenderState
    (render-state [_ {:keys [selected]}]
      (dom/li #js {:className (when selected "selected")
                   :onClick (fn [e]
                              (.preventDefault e)
                              (reset-to-history! i))}
              (om/build canvas item)))))

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

(defn bounded-bump [f n]
  (let [m (f n)]
    (if (> m 0)
      m
      n)))

(defn setup-keyboard-shortcuts! []
  (.bind js/Mousetrap #js ["u" "ctrl+z" "command+z"] undo!)
  (.bind js/Mousetrap #js ["ctrl+r"] redo!)

  (.bind js/Mousetrap "i" #(swap! app-state update-in [:canvas] start-drawing))
  (.bind js/Mousetrap "esc" (fn [_]
                              (swap! app-state update-in [:canvas] stop-drawing)
                              (push-state! @app-state)))

  (.addEventListener js/document "keydown" (fn [e]
                                             (when (= (.-which e) 16) ;; shift
                                               (swap! app-state assoc-in [:tools :snap] true))))

  (.addEventListener js/document "keyup" (fn [e]
                                             (when (= (.-which e) 16) ;; shift
                                               (swap! app-state assoc-in [:tools :snap] false))))

  (.bind js/Mousetrap "["
         (fn [] (swap! app-state update-in [:tools :props :lineWidth] (partial bounded-bump dec))))

  (.bind js/Mousetrap "]"
         (fn [] (swap! app-state update-in [:tools :props :lineWidth] (partial bounded-bump inc)))))

(defn ^:export main []
  (setup-keyboard-shortcuts!)
  (om/root
    app-component
    app-state
    {:target (. js/document (getElementById "app"))
     :tx-listen transact-listen})
  (om/root
    history
    undo/app-history
    {:target (. js/document (getElementById "history"))}))

(comment

(deref app-state)

(encode (get-in @app-state [:canvas :actions]))

(swap! app-state update-in [:canvas :dimensions] assoc :width 900 :height 300)

)
