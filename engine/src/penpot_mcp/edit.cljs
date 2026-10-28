;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Parts of this namespace mirror Penpot's frontend (MPL-2.0, Copyright (c)
;; KALEIDOS SUBSIDIARY SL): the flex layout defaults and layout keys
;; (app.main.data.workspace.shape-layout), the transform attrs and the way
;; modifiers are applied (app.main.data.workspace.modifiers), text resizing
;; (app.main.data.workspace.wasm-text) and position data
;; (app.render-wasm.api/calculate-position-data).

(ns penpot-mcp.edit
  "Edits a page the way Penpot's workspace does, without a browser.

  Operations (plain maps, usually from JSON) are turned into Penpot changes with
  `common`'s changes-builder, which also keeps a local copy of the page up to
  date. Geometry that depends on layout (flex children, hug boards) and text
  size (auto-width / auto-height) is then computed with the same functions the
  workspace uses: `set-objects-modifiers` + `transform-shape`, and render-wasm's
  text layout for text dimensions and position data.

  The pipeline is split so the caller can do the async parts (font loading):

    (build page-id objects ops)        ; sync  -> state
    measure texts with render-wasm     ; async (fonts) — see `text-dimensions`
    (apply-geometry state dims)        ; sync  -> state
    position data with render-wasm     ; sync  — see `position-data`
    (with-position-data state pd)      ; sync  -> state

  `(:changes state)` holds the redo changes to send with `update-file`."
  (:require
   [app.common.data :as d]
   [app.common.files.changes :as cfc]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.files.variant :as cfv]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variants :as clv]
   [app.common.path-names :as cpn]
   [app.common.render-wasm.api.select :as wselect]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.wasm :as rwasm]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.path :as path]
   [app.common.types.text :as txt]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [penpot-mcp.fonts :as fonts]
   [penpot-mcp.library :as library]
   [penpot-mcp.link :as link]
   [penpot-mcp.props :as props]
   [penpot-mcp.tokens :as tokens]
   [penpot-mcp.touched :as touched]
   [penpot-mcp.util :as u]
   [penpot-mcp.vector :as vector]))

;; --- constants mirrored from the workspace

(def ^:private transform-attrs
  #{:selrect :points :x :y :r1 :r2 :r3 :r4 :shadow :blur :background-blur
    :strokes :width :height :content :transform :transform-inverse :rotation
    :flip-x :flip-y :grow-type :position-data :layout-gap :layout-padding
    :layout-item-h-sizing :layout-item-max-h :layout-item-max-w
    :layout-item-min-h :layout-item-min-w :layout-item-v-sizing
    :layout-padding-type :layout-item-margin :layout-item-margin-type
    :layout-grid-cells :layout-grid-columns :layout-grid-rows :fills :fill-image})

(def ^:private layout-keys
  [:layout :layout-flex-dir :layout-gap-type :layout-gap :layout-align-items
   :layout-justify-content :layout-align-content :layout-wrap-type
   :layout-padding-type :layout-padding])

(def ^:private initial-flex-layout
  {:layout                 :flex
   :layout-flex-dir        :row
   :layout-gap-type        :multiple
   :layout-gap             {:row-gap 0 :column-gap 0}
   :layout-align-items     :start
   :layout-justify-content :start
   :layout-align-content   :stretch
   :layout-wrap-type       :nowrap
   :layout-padding-type    :simple
   :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0}})

;; --- input parsing

(def ^:private error! u/error!)

(defn- ->kw
  "Parses an enum value: accepts \"spaceBetween\", \"space-between\" or
  \"SPACE_BETWEEN\"; `aliases` maps extra spellings."
  ([v allowed field] (->kw v allowed field nil))
  ([v allowed field aliases]
   (when (some? v)
     (let [k (keyword (str/kebab (str v)))
           k (get aliases k k)]
       (if (contains? allowed k)
         k
         (error! (str "Invalid " field ": " (pr-str v) ". Allowed: "
                      (str/join ", " (sort (map name allowed))))
                 {:field field :value v}))))))

(defn- color!
  [v field]
  (cond
    (not (string? v))
    (error! (str "Invalid " field ": expected a hex color like \"#3366ff\"") {:field field :value v})

    (re-matches #"^#[0-9a-fA-F]{6}$" v)
    (str/lower v)

    (re-matches #"^#[0-9a-fA-F]{3}$" v)
    (let [[_ r g b] v] (str/lower (str "#" r r g g b b)))

    :else
    (error! (str "Invalid " field ": " (pr-str v) " (expected #rrggbb)") {:field field :value v})))

;; NOTE: `number!` & co. return nil for nil, so optional props stay optional.

(defn- paint
  "A color: \"#rrggbb\", or the name of a library color of the file or of a
  linked library (\"Primary / 500\"). Returns {:color :opacity? :ref-id?
  :ref-file?}; refs keep the shape linked to the library color, like
  applying it from the assets panel."
  [state v field]
  (if (and (string? v) (not (str/starts-with? v "#")))
    (let [c (library/find-asset (:assets state) :colors v field)]
      (when-not (:color c)
        (error! (str "Library color " (pr-str v) " is a "
                     (if (:gradient c) "gradient" "image") ": only solid library colors can be used here")
                {:field field :value v}))
      {:color (:color c)
       :opacity (:opacity c)
       :ref-id (:id c)
       :ref-file (::library/file-id c)})
    {:color (color! v field)}))

(defn- number!
  [v field & {:keys [min max]}]
  (when (some? v)
    (if (and (number? v) (js/isFinite v)
             (or (nil? min) (>= v min))
             (or (nil? max) (<= v max)))
      v
      (error! (str "Invalid " field ": " (pr-str v)
                   (cond
                     (and min max) (str " (expected a number from " min " to " max ")")
                     min (str " (expected a number >= " min ")")
                     :else " (expected a number)"))
              {:field field :value v}))))

(defn- bool!
  [v field]
  (when (some? v)
    (if (boolean? v)
      v
      (error! (str "Invalid " field ": " (pr-str v) " (expected true or false)") {:field field :value v}))))

(defn- four-sides
  "Number, [all], [vertical horizontal] or [top right bottom left]."
  [v field]
  (let [n #(number! % field :min 0)]
    (cond
      (number? v) (let [v (n v)] [v v v v])
      (and (sequential? v) (= 1 (count v))) (let [a (n (nth v 0))] [a a a a])
      (and (sequential? v) (= 2 (count v))) (let [a (n (nth v 0)) b (n (nth v 1))] [a b a b])
      (and (sequential? v) (= 4 (count v))) (mapv n v)
      :else (error! (str "Invalid " field ": use a number or [top right bottom left]") {:field field :value v}))))

;; --- attribute builders

(def ^:private image-fits #{:fill :contain :cover})

(defn- media-id!
  [v field]
  (or (when (string? v) (uuid/parse* v))
      (error! (str "Invalid " field ": " (pr-str v) " (expected a media id returned by upload_image)")
              {:field field :value v})))

(defn- image-fill
  "Fill with media `image` (an id returned by upload_image). `fit`: cover
  (default, Penpot's \"keep aspect ratio\": fills the shape, cropped),
  contain (whole image, centered — set from the final size by `fit-images`)
  or fill (stretched)."
  [state image fit opacity field]
  (let [id   (media-id! image field)
        info (or (get (:media state) id)
                 (error! (str "Image " id " not found or not readable: upload it with upload_image "
                              "and use the id it returns")
                         {:field field :value image}))
        fit  (or (->kw fit image-fits "fit") :cover)]
    {:fill-opacity (or (number! opacity "fill opacity" :min 0 :max 1) 1)
     :fill-image (d/without-nils
                  {:id id
                   :width (:width info)
                   :height (:height info)
                   :mtype (:mtype info)
                   :name (:name info)
                   :keep-aspect-ratio (not= fit :fill)})}))

(defn- image-fit
  "The fit of the image fill an op sets, if any."
  [op]
  (let [fill  (:fill op)
        fills (if (sequential? fill) fill [fill])]
    (or (some (fn [f] (when (and (map? f) (some? (:image f))) (or (->kw (:fit f) image-fits "fit") :cover))) fills)
        (when (some? (:image op)) (or (->kw (:fit op) image-fits "fit") :cover)))))

(defn- round4 [v] (/ (js/Math.round (* v 10000)) 10000))

(defn- gradient!
  "A gradient from {type: linear|radial, angle (CSS degrees, 180 = top to
  bottom), start/end ([x, y] as fractions of the shape), stops: [{color,
  opacity, offset}] or [\"#rrggbb\", …] (evenly spread)}."
  [g field]
  (when-not (map? g)
    (error! (str field " must be an object like {\"type\": \"linear\", \"angle\": 90, \"stops\": [\"#3366ff\", \"#00aa88\"]}")
            {:field field}))
  (props/check-keys! g [:type :angle :stops :start :end :width] (str "in \"" field "\""))
  (let [type  (or (->kw (:type g) #{:linear :radial} (str field ".type")) :linear)
        stops (:stops g)
        _     (when-not (and (sequential? stops) (>= (count stops) 2))
                (error! (str field ".stops needs two stops or more, e.g. [\"#3366ff\", \"#00aa88\"] or "
                             "[{\"color\": \"#3366ff\", \"offset\": 0}, {\"color\": \"#00aa88\", \"offset\": 1}]")
                        {:field (str field ".stops")}))
        n     (count stops)
        stops (into []
                    (map-indexed
                     (fn [i st]
                       (let [st (if (string? st) {:color st} st)]
                         (when-not (map? st) (error! "Each gradient stop is \"#rrggbb\" or {color, opacity, offset}" {:field field}))
                         (props/check-keys! st [:color :opacity :offset] "in a gradient stop")
                         {:color (color! (:color st) (str field ".stops.color"))
                          :opacity (or (number! (:opacity st) (str field ".stops.opacity") :min 0 :max 1) 1)
                          :offset (or (number! (:offset st) (str field ".stops.offset") :min 0 :max 1)
                                      (round4 (/ i (dec n))))})))
                    stops)
        point (fn [v k]
                (when (some? v)
                  (if (and (sequential? v) (= 2 (count v)) (every? number? v))
                    v
                    (error! (str field "." k " must be [x, y] (fractions of the shape: [0, 0] is the top left)")
                            {:field (str field "." k)}))))
        start (point (:start g) "start")
        end   (point (:end g) "end")
        _     (when (not= (some? start) (some? end))
                (error! (str field " needs both start and end (or an angle)") {:field field}))
        _     (when (and start (some? (:angle g)))
                (error! (str field ": give either angle or start/end") {:field field}))
        [sx sy ex ey]
        (cond
          start (concat start end)
          (= type :radial) [0.5 0.5 0.5 1]
          :else (let [a  (* (or (number! (:angle g) (str field ".angle")) 180) (/ js/Math.PI 180))
                      dx (/ (js/Math.sin a) 2)
                      dy (/ (- (js/Math.cos a)) 2)]
                  (mapv round4 [(- 0.5 dx) (- 0.5 dy) (+ 0.5 dx) (+ 0.5 dy)])))]
    {:type type :start-x sx :start-y sy :end-x ex :end-y ey
     :width (or (number! (:width g) (str field ".width") :min 0) 1)
     :stops stops}))

(defn- color-fill
  [state color opacity field]
  (let [{c :color lib-opacity :opacity :keys [ref-id ref-file]} (paint state color field)]
    (when (and ref-id (some? opacity))
      (error! (str "A library color sets its own opacity: remove the opacity or use a #rrggbb color")
              {:field field :value color}))
    (d/without-nils
     {:fill-color c
      :fill-opacity (or (number! opacity "fill opacity" :min 0 :max 1) lib-opacity 1)
      :fill-color-ref-id ref-id
      :fill-color-ref-file ref-file})))

(defn- one-fill
  [state fill fill-opacity field]
  (cond
    (string? fill)
    (color-fill state fill fill-opacity field)

    (map? fill)
    (let [{:keys [color image fit opacity gradient]} fill
          opacity (if (some? opacity) opacity fill-opacity)]
      (when (> (count (filter some? [color image gradient])) 1)
        (error! (str field " takes one of \"color\", \"image\" or \"gradient\"") {:field field}))
      (when (and (some? fit) (nil? image))
        (error! "\"fit\" only applies to image fills" {:field (str field ".fit")}))
      (cond
        (some? image)    (image-fill state image fit opacity (str field ".image"))
        (some? gradient) {:fill-color-gradient (gradient! gradient (str field ".gradient"))
                          :fill-opacity (or (number! opacity "fill opacity" :min 0 :max 1) 1)}
        (some? color)    (color-fill state color opacity (str field ".color"))
        :else            (error! (str field " needs \"color\", \"gradient\" or \"image\"") {:field field})))

    :else
    (error! (str "Invalid " field ": " (pr-str fill)
                 " (use \"#rrggbb\", a library color name, \"none\", {\"gradient\": …} or {\"image\": <media id>})")
            {:field field :value fill})))

(defn- fills-attrs
  "`fill`: \"#rrggbb\", a library color name, \"none\", {\"color\", \"opacity\"},
  {\"gradient\": …}, {\"image\", \"fit\", \"opacity\"}, or a list of those (the
  first on top); `fillOpacity` applies to a single fill."
  [state op]
  (let [{:keys [fill fillOpacity]} op]
    (cond
      (contains? op :fill)
      {:fills
       (cond
         (or (nil? fill) (= "none" fill))
         []

         (sequential? fill)
         (do (when (some? fillOpacity)
               (error! "fillOpacity applies to a single fill: give each fill its own opacity" {:field "fillOpacity"}))
             (into [] (map-indexed (fn [i f] (one-fill state f nil (str "fill[" i "]")))) fill))

         :else
         [(one-fill state fill fillOpacity "fill")])}

      (some? fillOpacity)
      (error! "fillOpacity needs a fill in the same op, e.g. \"fill\": \"#3366ff\", \"fillOpacity\": 0.5"
              {:field "fillOpacity" :value fillOpacity}))))

(defn- stroke-attrs
  [state op]
  (when (contains? op :stroke)
    (let [stroke (:stroke op)]
      {:strokes
       (cond
         (or (nil? stroke) (= "none" stroke))
         []

         (or (string? stroke) (map? stroke))
         (let [{:keys [color width align opacity gradient style]} (if (string? stroke) {:color stroke} stroke)
               _ (when (and (some? gradient) (some? color))
                   (error! "stroke takes either \"color\" or \"gradient\"" {:field "stroke"}))
               {c :color lib-opacity :opacity :keys [ref-id ref-file]}
               (if (some? gradient) {} (paint state (or color "#000000") "stroke.color"))]
           (when (and ref-id (some? opacity))
             (error! "A library color sets its own opacity: remove stroke.opacity or use a #rrggbb color"
                     {:field "stroke.opacity"}))
           [(d/without-nils
             {:stroke-color c
              :stroke-color-gradient (when (some? gradient) (gradient! gradient "stroke.gradient"))
              :stroke-opacity (or (number! opacity "stroke.opacity" :min 0 :max 1) lib-opacity 1)
              :stroke-color-ref-id ref-id
              :stroke-color-ref-file ref-file
              :stroke-width (or (number! width "stroke.width" :min 0) 1)
              :stroke-style (or (->kw style #{:solid :dotted :dashed :mixed} "stroke.style") :solid)
              :stroke-alignment (or (->kw align #{:inner :center :outer} "stroke.align") :inner)})])

         :else
         (error! (str "Invalid stroke: " (pr-str stroke)
                      " (use \"#rrggbb\", \"none\" or {\"color\", \"width\", \"align\", \"style\"})")
                 {:field "stroke" :value stroke}))})))

(defn- radius-attrs
  [op]
  (when (contains? op :radius)
    (let [[r1 r2 r3 r4] (four-sides (or (:radius op) 0) "radius")]
      {:r1 r1 :r2 r2 :r3 r3 :r4 r4})))

(def ^:private shadow-props [:style :x :y :blur :spread :color :opacity :visible])

(defn- shadow-attrs
  "`shadow`: {style: drop|inner, x, y, blur, spread, color, opacity,
  visible} (defaults: drop, 4, 4, 4, 0, #000000 at 0.2), a list of them, or
  \"none\"."
  [state op]
  (when (contains? op :shadow)
    (let [v     (:shadow op)
          items (cond
                  (or (nil? v) (false? v) (= "none" v)) []
                  (map? v) [v]
                  (sequential? v) v
                  :else (error! "shadow must be {style, x, y, blur, spread, color, opacity}, a list of them, or \"none\""
                                {:field "shadow" :value v}))]
      {:shadow
       (mapv (fn [sh]
               (when-not (map? sh)
                 (error! "Each shadow is an object like {\"y\": 2, \"blur\": 8, \"color\": \"#000000\", \"opacity\": 0.15}"
                         {:field "shadow"}))
               (props/check-keys! sh shadow-props "in \"shadow\"")
               (let [{c :color lib-opacity :opacity :keys [ref-id ref-file]} (paint state (or (:color sh) "#000000") "shadow.color")]
                 {:id (uuid/next)
                  :style (or (->kw (:style sh) #{:drop-shadow :inner-shadow} "shadow.style"
                                   {:drop :drop-shadow :inner :inner-shadow})
                             :drop-shadow)
                  :offset-x (or (number! (:x sh) "shadow.x") 4)
                  :offset-y (or (number! (:y sh) "shadow.y") 4)
                  :blur (or (number! (:blur sh) "shadow.blur" :min 0) 4)
                  :spread (or (number! (:spread sh) "shadow.spread") 0)
                  :hidden (false? (bool! (:visible sh) "shadow.visible"))
                  :color (d/without-nils
                          {:color c
                           :opacity (or (number! (:opacity sh) "shadow.opacity" :min 0 :max 1) lib-opacity 0.2)
                           :ref-id ref-id
                           :ref-file ref-file})}))
             items)})))

(defn- blur-value
  [v field type]
  (if (or (nil? v) (false? v) (= "none" v) (and (number? v) (zero? v)))
    ::none
    (do (when (map? v) (props/check-keys! v [:value] (str "in \"" field "\"")))
        {:id (uuid/next) :type type :value (number! (if (map? v) (:value v) v) field :min 0) :hidden false})))

(defn- effect-attrs
  "`shadow`, `blur` (layer blur radius), `backgroundBlur` and `blendMode`."
  [state op]
  (merge (shadow-attrs state op)
         (when (contains? op :blur) {:blur (blur-value (:blur op) "blur" :layer-blur)})
         (when (contains? op :backgroundBlur)
           {:background-blur (blur-value (:backgroundBlur op) "backgroundBlur" :background-blur)})
         (when (contains? op :blendMode)
           {:blend-mode (or (->kw (:blendMode op) cts/blend-modes "blendMode") :normal)})))

(defn- apply-attrs
  "`shape` with `attrs`; ::none removes an attr."
  [shape attrs]
  (reduce-kv (fn [s k v] (if (= ::none v) (dissoc s k) (assoc s k v))) shape attrs))

(defn- track!
  "A grid track: 100 or \"100px\" (fixed), \"1fr\" (flex), \"50%\", \"auto\", or
  {type, value}."
  [v field]
  (let [bad #(error! (str "Invalid " field " track " (pr-str v)
                          ": use 100 (px), \"1fr\", \"50%\", \"auto\" or {\"type\": \"flex\", \"value\": 1}")
                     {:field field :value v})]
    (cond
      (number? v) {:type :fixed :value (number! v field :min 0)}
      (map? v) (do (props/check-keys! v [:type :value] (str "in a " field " track"))
                   (let [t (->kw (:type v) ctl/grid-track-types (str field ".type") {:fr :flex :px :fixed})]
                     (d/without-nils {:type (or t (bad)) :value (when-not (= t :auto) (or (number! (:value v) field :min 0) 1))})))
      (string? v) (let [v' (str/trim (str/lower v))]
                    (cond
                      (= "auto" v') {:type :auto}
                      (re-matches #"^[0-9.]+fr$" v') {:type :flex :value (js/parseFloat v')}
                      (re-matches #"^[0-9.]+%$" v') {:type :percent :value (js/parseFloat v')}
                      (re-matches #"^[0-9.]+(px)?$" v') {:type :fixed :value (js/parseFloat v')}
                      :else (bad)))
      :else (bad))))

(defn- tracks!
  [v field]
  (cond
    (number? v) (vec (repeat (max 0 (js/Math.floor v)) ctl/default-track-value))
    (sequential? v) (mapv #(track! % field) v)
    :else (error! (str field " must be a list of tracks like [\"1fr\", \"1fr\", 240] or a count") {:field field})))

(defn- layout-attrs
  "Layout attrs from `layout` ({type: flex|grid, direction, gap, padding,
  alignItems, …; grid: columns, rows, justifyItems}), or ::remove for
  `false` / {type: \"none\"}."
  [layout]
  (cond
    (nil? layout) nil
    (or (false? layout) (= "none" layout) (and (map? layout) (= "none" (:type layout)))) ::remove
    (or (true? layout) (= "flex" layout)) (layout-attrs {})
    (= "grid" layout) (layout-attrs {:type "grid"})
    (not (map? layout))
    (error! (str "Invalid layout: " (pr-str layout) " (use {\"type\": \"flex\", ...}, {\"type\": \"grid\", ...} or false)")
            {:field "layout" :value layout})
    :else
    (let [{:keys [type direction gap rowGap columnGap padding alignItems justifyItems
                  justifyContent alignContent wrap columns rows]} layout
          kind    (or (->kw type #{:flex :grid} "layout.type") :flex)
          grid?   (= kind :grid)
          _       (when (and (not grid?) (some #(contains? layout %) [:columns :rows :justifyItems]))
                    (error! "columns, rows and justifyItems are grid layout properties: set \"type\": \"grid\""
                            {:field "layout"}))
          _       (when (and grid? (contains? layout :wrap))
                    (error! "wrap is a flex layout property (a grid places its children in cells)" {:field "layout.wrap"}))
          [p1 p2 p3 p4] (when (some? padding) (four-sides padding "layout.padding"))
          justify #{:start :center :end :space-between :space-around :space-evenly :stretch}]
      (d/without-nils
       {:layout kind
        :layout-flex-dir (when-not grid? (->kw direction #{:row :row-reverse :column :column-reverse} "layout.direction"))
        :layout-grid-dir (when grid? (->kw direction #{:row :column} "layout.direction"))
        :layout-gap (when (or (some? gap) (some? rowGap) (some? columnGap))
                      {:row-gap (or (number! rowGap "layout.rowGap" :min 0) (number! gap "layout.gap" :min 0) 0)
                       :column-gap (or (number! columnGap "layout.columnGap" :min 0) (number! gap "layout.gap" :min 0) 0)})
        :layout-padding (when p1 {:p1 p1 :p2 p2 :p3 p3 :p4 p4})
        :layout-padding-type (when p1 (if (and (= p1 p3) (= p2 p4)) :simple :multiple))
        :layout-align-items (->kw alignItems #{:start :end :center :stretch} "layout.alignItems")
        :layout-justify-items (when grid? (->kw justifyItems #{:start :end :center :stretch} "layout.justifyItems"))
        :layout-justify-content (->kw justifyContent justify "layout.justifyContent")
        :layout-align-content (->kw alignContent justify "layout.alignContent")
        :layout-wrap-type (when (some? (bool! wrap "layout.wrap")) (if wrap :wrap :nowrap))
        ::columns (some-> columns (tracks! "layout.columns"))
        ::rows (some-> rows (tracks! "layout.rows"))}))))

(defn- sizing-attrs
  [sizing]
  (cond
    (nil? sizing) nil
    (map? sizing)
    (let [aliases {:hug :auto :fixed :fix}
          allowed #{:fix :fill :auto}]
      (d/without-nils
       {:layout-item-h-sizing (->kw (:h sizing) allowed "sizing.h" aliases)
        :layout-item-v-sizing (->kw (:v sizing) allowed "sizing.v" aliases)}))
    :else
    (error! (str "Invalid sizing: " (pr-str sizing) " (use {\"h\": \"fix|fill|hug\", \"v\": \"fix|fill|hug\"})")
            {:field "sizing" :value sizing})))

(defn- opt
  "Attr `k` from prop `p` of `op` with `f`; null removes it (::none)."
  [op p k f]
  (when (contains? op p)
    (let [v (get op p)]
      {k (if (nil? v) ::none (f v))})))

(defn- constraints-attrs
  [c]
  (when (some? c)
    (when-not (map? c)
      (error! "constraints must be like {\"h\": \"leftright\", \"v\": \"top\"}" {:field "constraints"}))
    (props/check-keys! c [:h :v] "in \"constraints\"")
    (d/without-nils
     {:constraints-h (->kw (:h c) cts/horizontal-constraint-types "constraints.h" {:left-right :leftright :stretch :leftright})
      :constraints-v (->kw (:v c) cts/vertical-constraint-types "constraints.v" {:top-bottom :topbottom :stretch :topbottom})})))

(defn- item-attrs
  "Options of a layout child (margin, min/max sizes, alignSelf, absolute,
  zIndex) and constraints (how a shape follows its board's resizes outside
  layouts)."
  [op]
  (merge
   (when (contains? op :margin)
     (if (nil? (:margin op))
       {:layout-item-margin ::none :layout-item-margin-type ::none}
       (let [[m1 m2 m3 m4] (four-sides (:margin op) "margin")]
         {:layout-item-margin {:m1 m1 :m2 m2 :m3 m3 :m4 m4}
          :layout-item-margin-type (if (and (= m1 m3) (= m2 m4)) :simple :multiple)})))
   (opt op :minWidth :layout-item-min-w #(number! % "minWidth" :min 0))
   (opt op :maxWidth :layout-item-max-w #(number! % "maxWidth" :min 0))
   (opt op :minHeight :layout-item-min-h #(number! % "minHeight" :min 0))
   (opt op :maxHeight :layout-item-max-h #(number! % "maxHeight" :min 0))
   (opt op :alignSelf :layout-item-align-self
        #(if (= "auto" %) ::none (->kw % ctl/item-align-self-types "alignSelf")))
   (opt op :absolute :layout-item-absolute #(bool! % "absolute"))
   (opt op :zIndex :layout-item-z-index #(number! % "zIndex"))
   (constraints-attrs (:constraints op))))

(defn- cell!
  "A grid cell for a child: {row, column (from 1), rowSpan, columnSpan,
  alignSelf, justifySelf}."
  [c]
  (when-not (map? c)
    (error! "cell must be like {\"row\": 1, \"column\": 2, \"columnSpan\": 2}" {:field "cell"}))
  (props/check-keys! c [:row :column :rowSpan :columnSpan :alignSelf :justifySelf] "in \"cell\"")
  (let [n (fn [k] (when (some? (get c k))
                    (let [v (get c k)]
                      (if (and (integer? v) (>= v 1) (<= v 100))
                        v
                        (error! (str "cell." (name k) " must be a whole number from 1") {:field (str "cell." (name k))})))))]
    (when-not (and (some? (:row c)) (some? (:column c)))
      (error! "cell needs \"row\" and \"column\" (the first row and column are 1)" {:field "cell"}))
    {:row (n :row) :column (n :column)
     :row-span (or (n :rowSpan) 1) :column-span (or (n :columnSpan) 1)
     :align-self (->kw (:alignSelf c) ctl/grid-cell-align-self-types "cell.alignSelf")
     :justify-self (->kw (:justifySelf c) ctl/grid-cell-justify-self-types "cell.justifySelf")}))

(def ^:private typography-props
  [:fontFamily :fontWeight :fontStyle :fontSize :lineHeight :letterSpacing :textTransform])

(defn- typography-attrs
  "Text attrs of library typography `name`, linked to it like applying it from
  the assets panel."
  [state op]
  (let [conflicts (filter #(contains? op %) typography-props)]
    (when (seq conflicts)
      (error! (str "A typography sets the font, size, line height, letter spacing and text transform: remove "
                   (str/join ", " (map (fn [k] (str "\"" (name k) "\"")) conflicts))
                   " or the typography")
              {:field (name (first conflicts))}))
    (let [t (library/find-asset (:assets state) :typographies (:typography op) "typography")]
      (-> (select-keys t [:font-id :font-family :font-variant-id :font-size :font-weight :font-style
                          :line-height :letter-spacing :text-transform])
          (assoc :typography-ref-id (:id t)
                 :typography-ref-file (::library/file-id t))))))

(defn- text-styles
  "Text node styles from the op, over `base` (the first text node of the
  existing content, if any). A typography links the text to it; setting a
  font prop without one detaches the text from its typography, as in the
  editor."
  [state op base]
  (let [{:keys [fontSize textAlign lineHeight letterSpacing textTransform textDecoration fill]} op
        ;; on text shapes `fill` means the text color
        _ (when (and (some? fill) (not (string? fill)))
            (error! "On a text, fill is the text color: use \"#rrggbb\" or a library color name (image fills are not supported on texts)"
                    {:field "fill" :value fill}))
        text-color (or (:textColor op) fill)
        paint*     (when (some? text-color) (paint state text-color "textColor"))]
    (merge
     (select-keys base txt/text-all-attrs)
     (if (contains? op :typography)
       (typography-attrs state op)
       (merge
        (fonts/text-font-attrs (:fonts state) op (when (:font-id base) base))
        (when (some #(contains? op %) typography-props)
          {:typography-ref-id nil :typography-ref-file nil})
        (d/without-nils
         {:font-size (some-> (number! fontSize "fontSize" :min 1) str)
          :line-height (some-> (number! lineHeight "lineHeight" :min 0) str)
          :letter-spacing (some-> (number! letterSpacing "letterSpacing") str)
          :text-transform (some-> (->kw textTransform #{:none :uppercase :lowercase :capitalize} "textTransform") name)})))
     (d/without-nils
      {:text-align (some-> (->kw textAlign #{:left :center :right :justify} "textAlign") name)
       :text-decoration (some-> (->kw textDecoration #{:none :underline :line-through} "textDecoration"
                                      {:strikethrough :line-through})
                                name)
       :fills (when paint*
                [(d/without-nils
                  {:fill-color (:color paint*)
                   :fill-opacity (or (:opacity paint*) 1)
                   :fill-color-ref-id (:ref-id paint*)
                   :fill-color-ref-file (:ref-file paint*)})])}))))

(defn- text-op?
  [op]
  (some #(contains? op %) [:text :spans :fontFamily :fontSize :fontWeight :fontStyle :textColor :fill
                           :textAlign :lineHeight :letterSpacing :textTransform :textDecoration
                           :verticalAlign :typography]))

(def ^:private span-props
  [:text :typography :fontFamily :fontSize :fontWeight :fontStyle :textColor :fill :lineHeight
   :letterSpacing :textTransform :textDecoration])

(defn- spans-content
  "Text content from `spans` ([{text, …styles}]): each span is styled from
  the text's `styles` and its own props; a \"\\n\" starts a new paragraph."
  [state spans styles]
  (when-not (and (sequential? spans) (seq spans) (every? map? spans))
    (error! "spans must be a list like [{\"text\": \"Hello \"}, {\"text\": \"world\", \"fontWeight\": \"700\"}]"
            {:field "spans"}))
  (let [nodes (mapv (fn [sp]
                      (props/check-keys! sp span-props "in a span")
                      (when-not (string? (:text sp)) (error! "Each span needs \"text\"" {:field "spans"}))
                      [(:text sp) (text-styles state sp styles)])
                    spans)
        paras (reduce (fn [paras [text style]]
                        (let [[first-part & more] (vec (.split text "\n"))]
                          (reduce (fn [paras part] (conj paras [[part style]]))
                                  (update paras (dec (count paras)) conj [first-part style])
                                  more)))
                      [[]]
                      nodes)
        node  (fn [[text style]] (merge txt/default-text-attrs (select-keys style txt/text-node-attrs) {:text text}))]
    {:type "root"
     :children
     [{:type "paragraph-set"
       :children
       (mapv (fn [para]
               (let [kept (filterv #(seq (first %)) para)
                     kept (if (seq kept) kept [(first para)])]
                 (merge txt/default-text-attrs
                        (select-keys (second (first kept)) txt/text-all-attrs)
                        {:type "paragraph" :children (mapv node kept)})))
             paras)}]}))

(defn- text-content
  "New text content for `op`, keeping the current text and styles of `content`
  where the op doesn't override them."
  [state content op]
  (when (and (contains? op :text) (contains? op :spans))
    (error! "Give either \"text\" or \"spans\"" {:field "spans"}))
  (let [base   (first (txt/node-seq txt/is-text-node? content))
        styles (text-styles state (dissoc op :spans) (or base {:fills [{:fill-color "#000000" :fill-opacity 1}]}))
        valign (or (some-> (->kw (:verticalAlign op) #{:top :center :bottom} "verticalAlign") name)
                   (:vertical-align content))
        new    (if (contains? op :spans)
                 (spans-content state (:spans op) styles)
                 (txt/change-text nil (if (contains? op :text)
                                        (str (:text op))
                                        (or (some-> content txt/content->text) ""))
                                  styles))]
    (cond-> new valign (assoc :vertical-align valign))))

;; --- shape attrs shared by create and update

(defn- prop-attrs
  "Attrs of the generic props. `type` is the shape type; prop/type
  combinations were checked by `props/check-shape-op!` before."
  [state type op]
  (merge
   (when-not (= type :text) (fills-attrs state op))
   (stroke-attrs state op)
   (effect-attrs state op)
   (item-attrs op)
   (when (#{:rect :frame} type) (radius-attrs op))
   (sizing-attrs (:sizing op))
   (d/without-nils
    {:name (some-> (:name op) str)
     :opacity (number! (:opacity op) "opacity" :min 0 :max 1)
     :hidden (some-> (bool! (:visible op) "visible") not)
     :show-content (when (= type :frame) (some-> (bool! (:clip op) "clip") not))
     :grow-type (when (= type :text)
                  (->kw (:growType op) #{:auto-width :auto-height :fixed} "growType"))})))

(def ^:private grid-only-keys
  [:layout-grid-dir :layout-justify-items :layout-grid-rows :layout-grid-columns :layout-grid-cells])

(def ^:private flex-only-keys [:layout-flex-dir :layout-wrap-type])

(def ^:private initial-grid-layout
  {:layout                 :grid
   :layout-grid-dir        :row
   :layout-gap-type        :multiple
   :layout-gap             {:row-gap 0 :column-gap 0}
   :layout-align-items     :start
   :layout-justify-items   :start
   :layout-align-content   :stretch
   :layout-justify-content :stretch
   :layout-padding-type    :simple
   :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0}
   :layout-grid-cells      {}
   :layout-grid-rows       []
   :layout-grid-columns    []})

(defn- with-layout
  "`shape` with layout attrs from `layout-attrs`. New grid tracks rebuild
  the grid's cells; the children are placed in them again afterwards."
  [shape layout]
  (cond
    (nil? layout) shape
    (= ::remove layout) (apply dissoc shape (concat layout-keys grid-only-keys))

    (= :grid (:layout layout))
    (let [base  (if (ctl/grid-layout? shape) shape (merge (apply dissoc shape flex-only-keys) initial-grid-layout))
          shape (merge base (dissoc layout ::rows ::columns))]
      (if (or (::rows layout) (::columns layout))
        (let [rows (or (::rows layout) (:layout-grid-rows shape))
              cols (or (::columns layout) (:layout-grid-columns shape))
              grid (assoc shape :layout-grid-rows [] :layout-grid-columns [] :layout-grid-cells {})
              grid (reduce (fn [g t] (ctl/add-grid-column g t)) grid cols)]
          (reduce (fn [g t] (ctl/add-grid-row g t)) grid rows))
        shape))

    :else
    (merge (if (ctl/flex-layout? shape) shape (merge (apply dissoc shape grid-only-keys) initial-flex-layout))
           layout)))

;; --- design tokens on shapes

(def ^:private text-token-props
  #{:fontSize :fontFamily :fontWeight :letterSpacing :lineHeight :textCase :textDecoration :typography})

(defn- with-token-props
  "`op` with the props its `tokens` ({prop token-name}) stand for, and the
  applied-tokens to record: [op applied]. `shape` is the shape an update
  changes (nil on create), `type` its type."
  [state op shape type]
  (if-not (contains? op :tokens)
    [op nil]
    (let [m      (:tokens op)
          _      (when-not (and (map? m) (seq m) (every? string? (vals m)))
                   (error! (str "tokens must map properties to token names, e.g. "
                                "{\"fill\": \"color.primary\", \"padding\": \"space.md\"}")
                           {:field "tokens" :value m}))
          _      (when-let [k (and (not= type :text) (d/seek text-token-props (keys m)))]
                   (error! (str "tokens." (name k) " applies to texts") {:field (str "tokens." (name k))}))
          {:keys [props applied]} (tokens/shape-props (:tokens state) m)
          text?  (= type :text)
          lkeys  [:gap :rowGap :columnGap :padding]
          layout (select-keys props lkeys)
          top    (-> (apply dissoc props :typography :textCase :strokeWidth lkeys)
                     (merge (:typography props))
                     (cond-> (:textCase props) (assoc :textTransform (:textCase props))))
          _      (doseq [k (keys top)
                         :when (and (contains? op k)
                                    (not (and (= k :stroke) (map? (:stroke op)) (not (contains? (:stroke op) :color)))))]
                   (error! (str "Set either \"" (name k) "\" or tokens." (name k) ", not both") {:field (name k)}))
          _      (when (and (:strokeWidth props) (map? (:stroke op)) (contains? (:stroke op) :width))
                   (error! "Set either stroke.width or tokens.strokeWidth, not both" {:field "stroke.width"}))
          _      (doseq [k (keys layout)
                         :when (and (map? (:layout op)) (contains? (:layout op) k))]
                   (error! (str "Set either layout." (name k) " or tokens." (name k) ", not both") {:field (name k)}))
          _      (when (and (seq layout) (not (map? (:layout op))) (not (ctl/any-layout? shape)))
                   (error! "gap and padding tokens apply to boards with a flex or grid layout: set \"layout\" too"
                           {:field "tokens"}))
          stroke (when-let [{:keys [color opacity]} (:stroke top)]
                   (let [current (some-> shape :strokes first)]
                     (merge (d/without-nils
                             {:width (:stroke-width current)
                              :align (some-> (:stroke-alignment current) name)})
                            (when (map? (:stroke op)) (:stroke op))
                            (d/without-nils {:color color :opacity (when (< opacity 1) opacity)}))))
          stroke (cond
                   (nil? (:strokeWidth props)) stroke
                   (some? stroke) (assoc stroke :width (:strokeWidth props))
                   (map? (:stroke op)) (assoc (:stroke op) :width (:strokeWidth props))
                   (string? (:stroke op)) {:color (:stroke op) :width (:strokeWidth props)}
                   :else nil)
          fill   (when-let [{:keys [color opacity]} (:fill top)]
                   (if (or text? (= 1 opacity)) color {:color color :opacity opacity}))
          op     (cond-> (merge (dissoc op :tokens) (dissoc top :stroke :fill :textColor))
                   (some? fill) (assoc :fill fill)
                   (:textColor top) (assoc :textColor (:color (:textColor top)))
                   (some? stroke) (assoc :stroke stroke)
                   (and (:strokeWidth props) (nil? stroke)) (assoc ::stroke-width (:strokeWidth props))
                   (seq layout) (assoc :layout (merge (when (map? (:layout op)) (:layout op)) layout)))]
      [op applied])))

(defn- with-stroke-width
  "`shape` with the width of its first stroke set (a black stroke when it
  has none), like applying a stroke width token in the editor."
  [shape width]
  (let [sides {:stroke-width width :stroke-width-top width :stroke-width-right width
               :stroke-width-bottom width :stroke-width-left width}]
    (if (seq (:strokes shape))
      (update shape :strokes #(into [(merge (first %) sides)] (rest %)))
      (assoc shape :strokes [(merge {:stroke-alignment :inner :stroke-style :solid :stroke-color "#000000"
                                     :stroke-opacity 1}
                                    sides)]))))

(defn- update-applied
  "`shape` without the applied tokens of `unapply` and with `applied`."
  [shape unapply applied]
  (let [m (merge (apply dissoc (:applied-tokens shape) unapply) applied)]
    (if (seq m) (assoc shape :applied-tokens m) (dissoc shape :applied-tokens))))

;; --- operations

(def ^:private shape-types
  {"frame" :frame "board" :frame "rect" :rect "rectangle" :rect
   "ellipse" :circle "circle" :circle "text" :text "image" :image
   "svg" :svg "path" :path})

(def ^:private default-names
  {:frame "Board" :rect "Rectangle" :circle "Ellipse" :text "Text" :image "Image"})

(defn- image-size
  "Size of an image shape: the image's natural size, or the missing
  dimension from the image's aspect ratio when only one is given."
  [{iw :width ih :height} width height]
  (let [ratio (if (and (pos? iw) (pos? ih)) (/ iw ih) 1)]
    (cond
      (and width height) [width height]
      (some? width)      [width (/ width ratio)]
      (some? height)     [(* height ratio) height]
      :else              [(max 1 iw) (max 1 ih)])))

(defn- resolve-id
  "Resolves a uuid string or a `ref` given to a shape created earlier in the
  same batch."
  [{:keys [refs]} v field]
  (when (some? v)
    (or (get refs v)
        (let [id (when (string? v) (uuid/parse* v))]
          (or id (error! (str "Unknown " field ": " (pr-str v)
                              " (not a uuid nor a ref created earlier in this batch)")
                         {:field field :value v}))))))

(defn- objects-of [state] (pcb/lookup-objects (:changes state)))

(defn- parent-origin
  [objects parent-id]
  (if (= parent-id uuid/zero)
    (gpt/point 0 0)
    (let [{:keys [x y]} (:selrect (get objects parent-id))]
      (gpt/point x y))))

(defn- touch-layout-parent
  "Marks the layout of `shape-id`'s parent (if any) for reflow."
  [state objects shape-id]
  (let [parent-id (cfh/get-parent-id objects shape-id)]
    (cond-> state
      (and parent-id (ctl/any-layout? objects parent-id))
      (update :reflow conj parent-id))))

(defn- parent-of!
  "[parent-id parent frame-id] for a new shape in `parent` (id or ref, nil
  for the page root)."
  [state objects parent]
  (let [parent-id (or (resolve-id state parent "parent") uuid/zero)
        shape     (or (get objects parent-id)
                      (error! (str "Parent " parent-id " not found on this page") {:field "parent"}))]
    (when-not (#{:frame :group} (:type shape))
      (error! "Parent must be a board (frame) or a group" {:field "parent" :value parent}))
    [parent-id shape (if (cfh/frame-shape? shape) parent-id (:frame-id shape))]))

(defn- add-shapes
  "Adds `root` (with `children`, already parented to it) to `parent`, like
  op-create: at the end of a layout."
  [state root children parent ref]
  (let [id      (:id root)
        changes (pcb/add-object (:changes state) root (when (ctl/any-layout? parent) {:index 0}))
        changes (reduce #(pcb/add-object %1 %2) changes children)
        changes (cond-> changes
                  (seq children) (pcb/resize-parents (vec (reverse (cons id (map :id children))))))
        state   (cond-> (assoc state :changes changes)
                  (some? ref) (update :refs assoc (str ref) id)
                  true (update :created conj id))]
    (touch-layout-parent state (objects-of state) id)))

(declare op-update)

(defn- op-create-svg
  "Imports SVG markup as editable vectors (a group of paths, rectangles,
  ellipses…), like pasting an SVG in the editor. `width`/`height` scale it,
  `color` paints every fill and stroke (icons)."
  [state {:keys [parent ref x y] :as op}]
  (props/check-shape-op! :create :svg op)
  (let [objects (objects-of state)
        [parent-id parent frame-id] (parent-of! state objects parent)
        origin  (parent-origin objects parent-id)
        [root children] (vector/svg-shapes (:svg op) (or (some-> (:name op) str) "Vector") objects frame-id parent-id
                                           (+ (:x origin) (or (number! x "x") 0))
                                           (+ (:y origin) (or (number! y "y") 0)))
        paint*  (when (some? (:color op)) (paint state (:color op) "color"))
        children (cond->> children paint* (mapv #(vector/recolor % paint*)))
        state   (add-shapes state root children parent ref)
        more    (dissoc op :op :type :ref :parent :svg :name :x :y :color)]
    (if (seq more)
      (op-update state (assoc more :op "update" :id (str (:id root))))
      state)))

(defn- path-stroke
  "Paths take centered strokes (inner and outer only apply to closed
  shapes): the default for `stroke` on a path."
  [stroke]
  (cond
    (map? stroke) (merge {:align "center"} stroke)
    (and (string? stroke) (not= "none" stroke)) {:color stroke :align "center"}
    :else stroke))

(defn- op-create-path
  "A path from SVG path data `d`, in the parent's coordinates (like the
  path of an SVG inside the board). `x`/`y` move its top-left there,
  `width`/`height` scale it. Without fill or stroke it gets a 1px black
  stroke, like a path drawn in the editor."
  [state {:keys [parent ref] :as op}]
  (props/check-shape-op! :create :path op)
  (let [op      (cond-> op (contains? op :stroke) (update :stroke path-stroke))
        [op applied] (with-token-props state op nil :path)
        objects (objects-of state)
        [parent-id parent frame-id] (parent-of! state objects parent)
        origin  (parent-origin objects parent-id)
        content (path/move-content (vector/path-content (:d op)) origin)
        id      (uuid/next)
        styled? (or (contains? op :fill) (contains? op :stroke))
        shape   (-> (cts/setup-shape {:id id :type :path :name (or (some-> (:name op) str) "Path")
                                      :content content :parent-id parent-id :frame-id frame-id
                                      :fills []
                                      :strokes (if styled?
                                                 []
                                                 [{:stroke-color "#000000" :stroke-opacity 1 :stroke-width 1
                                                   :stroke-style :solid :stroke-alignment :center}])})
                    (apply-attrs (prop-attrs state :path (dissoc op :x :y :width :height :sizing :rotation)))
                    (cond-> (seq applied) (update-applied [] applied))
                    (cond-> (::stroke-width op) (with-stroke-width (::stroke-width op))))
        state   (add-shapes state shape [] parent ref)
        more    (select-keys op [:x :y :width :height :sizing :rotation])]
    (if (seq more)
      (op-update state (assoc more :op "update" :id (str id)))
      state)))

(defn- op-create
  [state {:keys [type parent ref x y] :as op}]
  (let [kind      (or (get shape-types (some-> type str str/lower))
                      (error! (str "Invalid type " (pr-str type) ". Use frame, rect, ellipse, text, image, svg, path "
                                   "or component")
                              {:field "type" :value type}))
        _         (props/check-shape-op! :create kind op)
        [op applied] (with-token-props state op nil (if (= kind :image) :rect kind))
        objects   (objects-of state)
        image?    (= kind :image)
        type*     (if image? :rect kind)
        parent-id (or (resolve-id state parent "parent") uuid/zero)
        parent    (or (get objects parent-id)
                      (error! (str "Parent " parent-id " not found on this page") {:field "parent"}))
        _         (when-not (#{:frame :group} (:type parent))
                    (error! "Parent must be a board (frame) or a group" {:field "parent" :value parent}))
        frame-id  (if (cfh/frame-shape? parent) parent-id (:frame-id parent))
        origin    (parent-origin objects parent-id)
        text?     (= type* :text)
        width     (number! (:width op) "width" :min 0.01)
        height    (number! (:height op) "height" :min 0.01)
        img-fill  (when image?
                    (when (nil? (:image op))
                      (error! "type image needs \"image\": a media id returned by upload_image" {:field "image"}))
                    (image-fill state (:image op) (:fit op) (:fillOpacity op) "image"))
        [width height] (if image? (image-size (:fill-image img-fill) width height) [width height])
        grow-type (when text? (or (->kw (:growType op) #{:auto-width :auto-height :fixed} "growType")
                                  (if width :auto-height :auto-width)))
        id        (uuid/next)
        shape     (-> (cts/setup-shape
                       (cond-> {:id id
                                :type type*
                                :name (or (some-> (:name op) str)
                                          (when text? (some-> (:text op) str (str/prune 30)))
                                          (get-in img-fill [:fill-image :name])
                                          (get default-names kind))
                                :x (+ (:x origin) (or (number! x "x") 0))
                                :y (+ (:y origin) (or (number! y "y") 0))
                                :width (or width (if text? 1 100))
                                :height (or height (if text? 1 100))
                                :parent-id parent-id
                                :frame-id frame-id}
                         text? (assoc :grow-type grow-type
                                      :content (text-content state nil op))))
                      (apply-attrs (prop-attrs state type* (cond-> (dissoc op :growType)
                                                             image? (dissoc :fillOpacity))))
                      (cond-> image? (assoc :fills [img-fill]))
                      (cond-> (= type* :frame) (with-layout (layout-attrs (:layout op))))
                      (cond-> (seq applied) (update-applied [] applied))
                      (cond-> (::stroke-width op) (with-stroke-width (::stroke-width op)))
                      (cond-> text? (dissoc :position-data)))
        ;; like the workspace (Plugin API appendChild): in a flex/grid board the
        ;; new child goes to index 0 of :shapes, which is the end of the layout
        ;; (layout order = layers panel order = reverse of :shapes)
        changes   (pcb/add-object (:changes state) shape
                                  (when (ctl/any-layout? parent) {:index 0}))
        state     (cond-> (assoc state :changes changes)
                    (some? ref) (update :refs assoc (str ref) id)
                    true (update :created conj id)
                    (some? (:rotation op)) (update :rotate assoc id (number! (:rotation op) "rotation"))
                    (= :contain (image-fit op)) (update :contain conj id)
                    text? (update :texts conj id)
                    (ctl/any-layout? shape) (update :reflow conj id))]
    (touch-layout-parent state (objects-of state) id)))

(defn- descendant-texts
  "Text shapes of `id`'s subtree, in layer order, by name."
  [objects id]
  (->> (cfh/get-children-ids objects id)
       (keep #(get objects %))
       (filter cfh/text-shape?)))

(defn- op-instance
  "Creates a copy of a library component, like dragging it from the assets
  panel: Penpot's `make-component-instance`, so the copy stays linked to its
  main component. Root props (name, size, sizing, visibility, opacity) and
  `texts` ({layer name: text}) are applied as overrides of the copy."
  [state {:keys [component variant parent ref x y texts] :as op}]
  (props/check-shape-op! :create :component op)
  (when (and (some? variant) (not (map? variant)))
    (error! "variant must be an object like {\"Type\": \"Primary\"}" {:field "variant" :value variant}))
  (when (and (some? texts) (not (and (map? texts) (every? string? (vals texts)))))
    (error! "texts must map text layer names to texts, e.g. {\"Label\": \"Save\"}" {:field "texts" :value texts}))
  (let [objects   (objects-of state)
        comp      (library/find-asset (:assets state) :components component "component" variant)
        data      (cond-> (library/library-data (:libraries state) comp)
                    ;; a component of this file made from a board of this page:
                    ;; its main as it is now, earlier ops of the batch included
                    (and (= (::library/file-id comp) (::file-id state))
                         (= (:main-instance-page comp) (::page-id state)))
                    (assoc-in [:pages-index (::page-id state) :objects] objects))
        _         (when-not (library/main-shape data comp)
                    (error! (str "The main instance of component " (pr-str component) " was not found in its library")
                            {:field "component"}))
        parent-id (or (resolve-id state parent "parent") uuid/zero)
        parent    (or (get objects parent-id)
                      (error! (str "Parent " parent-id " not found on this page") {:field "parent"}))
        _         (when-not (#{:frame :group} (:type parent))
                    (error! "Parent must be a board (frame) or a group" {:field "parent" :value parent}))
        _         (when (ctk/in-component-copy? parent)
                    (error! "Components can't be added inside a component copy" {:field "parent"}))
        frame-id  (if (cfh/frame-shape? parent) parent-id (:frame-id parent))
        origin    (parent-origin objects parent-id)
        position  (gpt/point (+ (:x origin) (or (number! x "x") 0))
                             (+ (:y origin) (or (number! y "y") 0)))
        page      {:id (::page-id state) :objects objects}
        [_ shapes] (ctn/make-component-instance page comp data position {:force-frame-id frame-id})
        root      (cond-> (assoc (first shapes) :parent-id parent-id :frame-id frame-id)
                    (ctn/in-any-component? objects parent) (dissoc :component-root))
        id        (:id root)
        changes   (pcb/add-object (:changes state) root
                                  (cond-> {:ignore-touched true}
                                    ;; flex children go to the end of the layout, like op-create
                                    (ctl/any-layout? parent) (assoc :index 0)))
        changes   (reduce #(pcb/add-object %1 %2 {:ignore-touched true}) changes (rest shapes))
        state     (cond-> (assoc state :changes changes)
                    (some? ref) (update :refs assoc (str ref) id)
                    true (update :created conj id)
                    (ctl/any-layout? root) (update :reflow conj id))
        state     (touch-layout-parent state (objects-of state) id)
        overrides (select-keys op [:name :width :height :sizing :visible :opacity :margin :minWidth :maxWidth
                                   :minHeight :maxHeight :alignSelf :absolute :zIndex :constraints])
        state     (if (seq overrides) (op-update state (assoc overrides :op "update" :id (str id))) state)
        objects   (objects-of state)
        candidates (descendant-texts objects id)]
    (reduce (fn [state [layer text]]
              (let [layer  (u/key-str layer)
                    target (or (d/seek #(= layer (:name %)) candidates)
                               (error! (str "No text layer named " (pr-str layer) " in " (pr-str component)
                                            ". Text layers: " (str/join ", " (map #(pr-str (:name %)) candidates)))
                                       {:field "texts" :value layer}))]
                (op-update state {:op "update" :id (str (:id target)) :text text})))
            state
            texts)))

(defn- geometry-modifiers
  "Move/resize modifiers for x/y/width/height in an update op (x/y relative to
  the parent board, like on create)."
  [objects shape op]
  (let [origin  (parent-origin objects (:parent-id shape))
        {:keys [x y]} (:selrect shape)
        move-x  (when-let [v (number! (:x op) "x")] (- (+ v (:x origin)) x))
        move-y  (when-let [v (number! (:y op) "y")] (- (+ v (:y origin)) y))
        resize? (or (contains? op :width) (contains? op :height))]
    (when (or move-x move-y resize?)
      (cond-> (ctm/empty)
        resize?
        (ctm/add-modifiers (ctm/change-size shape
                                            (number! (:width op) "width" :min 0.01)
                                            (number! (:height op) "height" :min 0.01)))
        (or move-x move-y)
        (ctm/add-modifiers (ctm/move-modifiers (or move-x 0) (or move-y 0)))))))

(defn- op-update
  [state op]
  (let [objects (objects-of state)
        id      (or (resolve-id state (:id op) "id") (error! "update needs an id" {:field "id"}))
        shape   (or (get objects id) (error! (str "Shape " id " not found on this page") {:field "id"}))
        type    (:type shape)
        _       (props/check-shape-op! :update type op)
        unapply (tokens/unapplied-attrs op)
        [op applied] (with-token-props state op shape type)
        text?   (= type :text)
        layout  (when (= type :frame) (layout-attrs (:layout op)))
        op      (cond-> op (and (= type :path) (contains? op :stroke)) (update :stroke path-stroke))
        attrs   (prop-attrs state type op)
        fit     (image-fit op)
        content (when (contains? op :d)
                  (path/move-content (vector/path-content (:d op)) (parent-origin objects (:parent-id shape))))
        tint    (when (contains? op :color) (paint state (:color op) "color"))
        update-fn (fn [shape]
                    (cond-> (apply-attrs shape attrs)
                      (or (seq unapply) (seq applied)) (update-applied unapply applied)
                      (::stroke-width op) (with-stroke-width (::stroke-width op))
                      (some? layout) (with-layout layout)
                      (some? content) (path/update-geometry content)
                      (and text? (text-op? op)) (-> (assoc :content (text-content state (:content shape) op))
                                                    (dissoc :position-data))))
        changes (pcb/update-shapes (:changes state) [id] update-fn)
        changes (cond-> changes
                  tint (pcb/update-shapes (cfh/get-children-ids-with-self objects id) #(vector/recolor % tint)))
        modifs  (geometry-modifiers objects shape op)
        state   (cond-> (assoc state :changes changes)
                  (contains? op :rotation) (update :rotate assoc id (number! (:rotation op) "rotation"))
                  (some? content) (update :regroup conj id)
                  (and (contains? op :fill) (not= :contain fit)) (update :contain disj id)
                  (= :contain fit) (update :contain conj id)
                  (some? modifs) (update :modifs assoc id modifs)
                  (and text? (or (text-op? op) (contains? op :growType))) (update :texts conj id)
                  (or (some? layout) (and (= type :frame) (ctl/any-layout? shape))) (update :reflow conj id))]
    (touch-layout-parent state (objects-of state) id)))

(defn- op-delete
  [state op]
  (props/check-shape-op! :delete nil op)
  (let [objects (objects-of state)
        id      (or (resolve-id state (:id op) "id") (error! "delete needs an id" {:field "id"}))
        _       (when-not (contains? objects id) (error! (str "Shape " id " not found on this page") {:field "id"}))
        state   (touch-layout-parent state objects id)
        ids     (cfh/get-children-ids-with-self objects id)
        changes (pcb/remove-objects (:changes state) ids)]
    (-> state
        (assoc :changes changes)
        (update :deleted into ids)
        (update :regroup conj (:parent-id (get objects id))))))

(def ^:private library-change-types
  #{:add-component :mod-component :del-component :restore-component :purge-component
    :add-color :mod-color :del-color :add-typography :mod-typography :del-typography})

(defn- sync-own-library
  "Applies the library changes of the batch not applied yet (components
  made, variants, annotations, …) to the file's own library data, so the
  next ops of the batch see them (copies of a component made earlier, by
  name)."
  [state]
  (let [redo    (vec (:redo-changes (:changes state)))
        from    (min (or (::lib-applied state) 0) (count redo))
        new     (filterv #(contains? library-change-types (:type %)) (subvec redo from))
        state   (assoc state ::lib-applied (count redo))
        file-id (::file-id state)
        own     (get-in state [:libraries file-id])]
    (if (and (seq new) own)
      (let [libs (assoc-in (:libraries state) [file-id :data]
                           (cfc/process-changes (or (:data own) {}) new false))]
        (assoc state
               :libraries libs
               :assets (library/index (cons (get libs file-id) (vals (dissoc libs file-id))))))
      state)))

(defn- own-data
  "The edited file's data, with the page as it is now in the batch."
  [state]
  (let [page-id (::page-id state)
        file-id (::file-id state)
        data    (or (get-in state [:libraries file-id :data]) {})]
    (-> data
        (cond-> (nil? (:id data)) (assoc :id file-id))
        (cond-> (not (some #{page-id} (:pages data))) (update :pages (fnil conj []) page-id))
        (update-in [:pages-index page-id] assoc :id page-id :objects (objects-of state)))))

(defn- libraries-now
  "file id -> {:id :data} of the file and its libraries, with the edited
  file as it is now in the batch."
  [state]
  (let [file-id (::file-id state)]
    (-> (:libraries state)
        (assoc file-id (assoc (get-in state [:libraries file-id]) :id file-id :data (own-data state))))))

(defn- run-sub
  "Runs one of Penpot's change generators on new changes for the page as it
  is now (and the file's own library) and appends what it generates to the
  batch: the generators set their own page and objects on the changes they
  get, which would replay the batch's earlier changes. `f` takes the changes
  and returns [result changes]; returns [result state]."
  [state f]
  (let [objects (objects-of state)
        page    {:id (::page-id state) :objects objects}
        sub     (-> (pcb/empty-changes :mcp (::page-id state))
                    (pcb/with-page page)
                    (pcb/with-objects objects)
                    (pcb/with-library-data (own-data state)))
        [result sub] (f sub)]
    [result (-> state
                (update :changes #(-> (pcb/concat-changes % sub) (pcb/apply-changes-local)))
                (sync-own-library))]))

(defn- op-make-component
  "Turns a board into a main component of the file's library, like
  \"Create component\" in the editor (`generate-add-component`). The board
  stays where it is as the component's main instance."
  [state op]
  (props/check-keys! op [:op :id :name :annotation] "for makeComponent")
  (let [objects (objects-of state)
        id      (or (resolve-id state (:id op) "id") (error! "makeComponent needs the id of a board" {:field "id"}))
        shape   (or (get objects id) (error! (str "Shape " id " not found on this page") {:field "id"}))
        _       (when-not (cfh/frame-shape? shape)
                  (error! "Only boards (frames) can be made components here" {:field "id"}))
        _       (when (or (ctk/instance-head? shape) (ctk/in-component-copy? shape))
                  (error! "This board is already a component or part of a component copy" {:field "id"}))
        _       (when-not (::file-id state)
                  (error! "makeComponent needs the file id" {:field "op"}))
        state   (if (some? (:name op)) (op-update state {:op "update" :id (str id) :name (:name op)}) state)
        objects (objects-of state)
        annotation (some-> (:annotation op) str str/trim not-empty)
        [_ component-id changes]
        (cll/generate-add-component (:changes state) [(get objects id)] objects (::page-id state) (::file-id state)
                                    cfsh/prepare-create-artboard-from-selection)
        changes (cond-> changes
                  annotation
                  (pcb/amend-changes #(cond-> %
                                        (and (= :add-component (:type %)) (= component-id (:id %)))
                                        (assoc :annotation annotation))))]
    (-> state
        (assoc :changes changes)
        (update :components (fnil conj []) component-id)
        (sync-own-library))))

(defn- layer-index!
  [v n]
  (if (and (integer? v) (>= v 0))
    (min v n)
    (error! (str "Invalid index " (pr-str v) " (expected 0 for the first layer, 1 for the second, …)")
            {:field "index" :value v})))

(defn- op-move
  "Re-parents and/or reorders a shape, like dragging it in the layers panel
  (Penpot's `generate-relocate`). `index` counts like the layers panel: 0 is
  the top layer, which in a flex board is the first item of the layout.
  Without `index`, a shape moved to a flex board goes to the end of the
  layout, and to another parent on top of its siblings, like a new shape.
  Outside a layout the shape keeps its position on the page unless `x`/`y`
  (relative to the new parent) are given."
  [state op]
  (props/check-keys! op [:op :id :parent :index :x :y] "for move")
  (let [objects    (objects-of state)
        id         (or (resolve-id state (:id op) "id") (error! "move needs an id" {:field "id"}))
        shape      (or (get objects id) (error! (str "Shape " id " not found on this page") {:field "id"}))
        _          (when-not (or (contains? op :parent) (contains? op :index))
                     (error! (str "move needs \"parent\" (a board or group id or ref, or null for the page root) "
                                  "and/or \"index\" (position among the parent's layers, 0 = top / first in a layout)")
                             {:field "op"}))
        old-parent (:parent-id shape)
        parent-id  (if (contains? op :parent)
                     (let [p (:parent op)]
                       (if (or (nil? p) (= "root" p) (= "page" p))
                         uuid/zero
                         (resolve-id state p "parent")))
                     old-parent)
        parent     (or (get objects parent-id)
                       (error! (str "Parent " parent-id " not found on this page") {:field "parent"}))
        _          (when-not (#{:frame :group} (:type parent))
                     (error! "Parent must be a board (frame) or a group" {:field "parent"}))
        _          (when (or (= parent-id id) (cfh/is-parent? objects parent-id id))
                     (error! "A shape can't be moved into itself or one of its children" {:field "parent"}))
        _          (when (or (ctk/in-component-copy? (get objects old-parent)) (ctk/in-component-copy? parent))
                     (error! (str "The layers of a component copy can't be moved or reordered (the copy follows its "
                                  "main component): change the main component instead")
                             {:field "id"}))
        _          (when (cfh/components-nesting-loop? objects id parent-id)
                     (error! "This would put a component inside itself" {:field "parent"}))
        siblings   (filterv #(not= % id) (:shapes parent))
        n          (count siblings)
        layout?    (ctl/any-layout? parent)
        pos        (if (contains? op :index)
                     (layer-index! (:index op) n)
                     (if layout? n 0))
        ;; :shapes lists the bottom layer first, so layer position `pos` is
        ;; index n - pos; :mov-objects inserts before removing the shape
        ;; from the list, so moving it up within its parent needs index + 1
        target     (- n pos)
        current    (when (= parent-id old-parent) (d/index-of (:shapes parent) id))
        param      (if (and (some? current) (< current target)) (inc target) target)
        own        (get-in state [:libraries (::file-id state) :data])
        state      (if (= current target)
                     state
                     (-> state
                         (assoc :changes (-> (:changes state)
                                             (cond-> (seq own) (pcb/with-library-data own))
                                             (cls/generate-relocate parent-id param [id])))
                         (cond-> (ctl/any-layout? objects old-parent) (update :reflow conj old-parent))
                         (cond-> layout? (update :reflow conj parent-id))))
        state      (update state :relocated (fnil conj []) id)]
    (if (or (contains? op :x) (contains? op :y))
      (op-update state (assoc (select-keys op [:x :y]) :op "update" :id (str id)))
      state)))

(defn- op-link-assets
  "Links raw colors (and, with typographies, text styles) of the page or a
  board's subtree to the library assets with the same values (see
  `penpot-mcp.link`)."
  [state op]
  (props/check-keys! op [:op :scope :colors :typographies :prefer] "for linkAssets")
  (let [objects  (objects-of state)
        scope    (:scope op)
        scope-id (if (or (nil? scope) (= "page" scope)) uuid/zero (resolve-id state scope "scope"))
        _        (when-not (contains? objects scope-id)
                   (error! (str "Shape " scope-id " not found on this page") {:field "scope"}))
        colors?  (not (false? (bool! (:colors op) "colors")))
        typos?   (true? (bool! (:typographies op) "typographies"))
        _        (when-not (or colors? typos?)
                   (error! "linkAssets with colors false needs typographies true" {:field "colors"}))
        prefer   (link/parse-prefer (:assets state) (:prefer op))
        ids      (if (= scope-id uuid/zero)
                   (into [] (remove #(= % uuid/zero)) (keys objects))
                   (cfh/get-children-ids-with-self objects scope-id))
        {:keys [shapes report]}
        (link/link-assets objects ids {:assets (:assets state)
                                       :colors? colors?
                                       :typographies? typos?
                                       :prefer prefer
                                       :own-file-id (::file-id state)})]
    (-> state
        (cond-> (seq shapes)
          (update :changes pcb/update-shapes (vec (keys shapes))
                  (fn [shape] (get shapes (:id shape) shape))
                  {:attrs #{:fills :strokes :content}}))
        (update :link-reports (fnil conj [])
                (assoc report :scope (if (= scope-id uuid/zero) "page" (str scope-id)))))))

;; --- groups and boolean shapes

(defn- shape-ids!
  "Shapes `v` (a list of ids or refs) of the page."
  [state v field]
  (when-not (and (sequential? v) (seq v))
    (error! (str field " must be a list of shape ids or refs") {:field field}))
  (let [objects (objects-of state)]
    (mapv (fn [x]
            (let [id (resolve-id state x field)]
              (when-not (contains? objects id) (error! (str "Shape " id " not found on this page") {:field field}))
              id))
          v)))

(defn- check-not-in-copy!
  [objects shapes what]
  (when-let [s (d/seek #(ctn/has-any-copy-parent? objects %) shapes)]
    (error! (str "Layers of a component copy can't be " what " (" (pr-str (:name s)) "): change the main component")
            {:field "ids"}))
  (when-let [s (d/seek ctk/is-variant? shapes)]
    (error! (str "Variants can't be " what " (" (pr-str (:name s)) ")") {:field "ids"})))

(defn- op-group
  "Puts shapes in a new group, at the place of the top one (Ctrl+G)."
  [state op]
  (props/check-keys! op [:op :ids :name :ref] "for group")
  (let [objects (objects-of state)
        ids     (shape-ids! state (:ids op) "ids")
        shapes  (->> ids (cfh/clean-loops objects) (cfh/order-by-indexed-shapes objects) reverse (mapv #(get objects %)))
        _       (check-not-in-copy! objects shapes "grouped")
        id      (uuid/next)
        state   (second (run-sub state (fn [changes]
                                         [nil (vector/group changes objects id shapes
                                                            (or (some-> (:name op) str) "Group"))])))]
    (-> state
        (cond-> (some? (:ref op)) (update :refs assoc (str (:ref op)) id))
        (update :created conj id)
        (touch-layout-parent (objects-of state) id))))

(defn- op-ungroup
  "Moves the layers of a group, boolean shape or board to its parent and
  removes it (Shift+G, or \"Remove board\" for a board)."
  [state op]
  (props/check-keys! op [:op :id] "for ungroup")
  (let [objects (objects-of state)
        shape   (shape! state (:id op) "id" "ungroup")
        _       (when-not (#{:group :bool :frame} (:type shape))
                  (error! "ungroup takes a group, a boolean shape or a board" {:field "id"}))
        _       (when (or (ctk/instance-head? shape) (ctk/is-variant-container? shape))
                  (error! "Components and variant sets can't be ungrouped (detach a copy first)" {:field "id"}))
        _       (check-not-in-copy! objects [shape] "ungrouped")
        kids    (vec (:shapes shape))
        state   (second (run-sub state (fn [changes] [nil (vector/ungroup changes objects (:id shape))])))]
    (-> state
        (update :deleted conj (:id shape))
        (update :relocated (fnil into []) kids)
        (touch-layout-parent objects (:id shape)))))

(defn- op-boolean
  "Combines shapes into a boolean shape (union, difference, intersection
  or exclude), like the boolean buttons of the toolbar. It takes its fill
  and stroke from the top shape (the bottom one for a difference)."
  [state op]
  (props/check-keys! op [:op :ids :kind :name :ref] "for boolean")
  (let [objects (objects-of state)
        ids     (shape-ids! state (:ids op) "ids")
        kind    (or (->kw (or (:kind op) "union") vector/bool-types "kind") :union)
        shapes  (->> ids (cfh/clean-loops objects) (cfh/order-by-indexed-shapes objects) (mapv #(get objects %)))
        _       (when (< (count shapes) 2) (error! "boolean needs at least two shapes" {:field "ids"}))
        _       (when-let [f (d/seek cfh/frame-shape? shapes)]
                  (error! (str "Boards can't be combined (" (pr-str (:name f)) "): use paths, rectangles, ellipses, "
                               "groups or other boolean shapes")
                          {:field "ids"}))
        _       (when (some cfh/text-shape? shapes)
                  (error! "Texts can't be combined into a boolean shape" {:field "ids"}))
        _       (check-not-in-copy! objects shapes "combined")
        id      (uuid/next)
        state   (second (run-sub state (fn [changes]
                                         [nil (vector/bool changes objects id kind
                                                           (or (some-> (:name op) str) (str/capital (name kind)))
                                                           shapes)])))]
    (-> state
        (cond-> (some? (:ref op)) (update :refs assoc (str (:ref op)) id))
        (update :created conj id)
        (touch-layout-parent (objects-of state) id))))

(defn regroup
  "Recomputes the groups and boolean shapes around the shapes the batch
  changed (their box, and a boolean's path), like the workspace's
  reg-objects after a transform."
  [state]
  (let [objects (objects-of state)
        bool-or-group? #(#{:group :bool} (:type (get objects %)))
        ids     (->> (concat (:created state) (:moved state) (:regroup state) (:relocated state))
                     (filter #(contains? objects %))
                     (filter #(some bool-or-group? (cons % (cfh/get-parent-ids objects %))))
                     (distinct)
                     (vec))]
    (if (empty? ids)
      state
      (update state :changes pcb/resize-parents ids))))

;; --- components: annotations, variants, swap, detach, reset

(declare touch)

(defn- shape!
  "The shape `v` (id or ref) of the page, or an error."
  [state v field what]
  (let [id (or (resolve-id state v field) (error! (str what " needs \"" field "\"") {:field field}))]
    (or (get (objects-of state) id)
        (error! (str "Shape " id " not found on this page") {:field field}))))

(defn- own-component!
  "The component of the file's own library whose main instance is `shape`."
  [state shape field]
  (or (when (and (ctk/main-instance? shape) (= (:component-file shape) (::file-id state)))
        (get-in (own-data state) [:components (:component-id shape)]))
      (error! (str (pr-str (:name shape)) " is not the main of a component of this file"
                   (cond
                     (ctk/in-component-copy? shape)
                     " (it is a copy: change its main component, or use swap, detach or resetOverrides)"
                     (ctk/main-instance? shape)
                     " (its component belongs to another file)"))
              {:field field})))

(defn- op-annotate
  "Sets (or with null / \"\" removes) the annotation of a component, the
  note shown with it in the assets panel and the design tab."
  [state op]
  (props/check-keys! op [:op :id :annotation] "for annotate")
  (let [shape (shape! state (:id op) "id" "annotate")
        comp  (own-component! state shape "id")
        text  (:annotation op)
        _     (when-not (or (nil? text) (string? text))
                (error! "annotation must be a text, or null to remove it" {:field "annotation"}))
        text  (some-> text str/trim not-empty)]
    (second
     (run-sub state (fn [changes]
                      [nil (pcb/update-component changes (:id comp)
                                                 #(if text (assoc % :annotation text) (dissoc % :annotation)))])))))

(defn- variant-props!
  "Variant properties from JSON ({\"Type\": \"Primary\"}) as [[name value] ...]."
  [m field]
  (when-not (and (map? m) (every? string? (vals m)))
    (error! (str field " must map property names to texts, e.g. {\"Type\": \"Primary\", \"Size\": \"M\"}")
            {:field field :value m}))
  (mapv (fn [[k v]]
          (let [k (ctv/normalize-property-text (u/key-str k))
                v (ctv/normalize-property-text v)]
            (when-not (ctv/valid-property-name? k)
              (error! (str "Invalid property name " (pr-str k) " in " field " (1 to 60 characters)") {:field field}))
            (when-not (ctv/valid-property-value? v)
              (error! (str "The value of " (pr-str k) " in " field " is too long (60 characters at most)") {:field field}))
            [k v]))
        m))

(defn- variant-components
  "Components of the variant set whose board is `container-id`, in the
  order of its layers."
  [state container-id]
  (vec (remove nil? (cfv/find-variant-components (own-data state) (objects-of state) container-id))))

(defn- merged-props
  "component id -> [[name value] ...] for `comps` with `updates` (component
  id -> [[name value] ...]): names keep their order, new names go last, with
  \"\" for the variants that don't set them."
  [comps updates]
  (let [cur   (fn [c] (mapv (juxt :name :value) (:variant-properties c)))
        names (reduce (fn [names n] (if (some #{n} names) names (conj names n)))
                      []
                      (concat (mapcat #(map first (cur %)) comps)
                              (mapcat #(map first (get updates (:id %))) comps)))]
    (into {}
          (map (fn [c]
                 (let [m (merge (into {} (cur c)) (into {} (get updates (:id c))))]
                   [(:id c) (mapv (fn [n] [n (get m n "")]) names)])))
          comps)))

(defn- props-text
  [props]
  (str/join ", " (map (fn [[n v]] (str n "=" v)) props)))

(defn- write-variant-props
  "Sets the properties of every variant of the set `container-id`
  (`props-of`: component -> [[name value] ...], same names in the same
  order for all), with the component and main names Penpot derives from
  them, like `generate-make-shapes-variant`."
  [state container-id props-of]
  (let [container (get (objects-of state) container-id)
        comps     (variant-components state container-id)
        [cpath cname] (cpn/split-group-name (:name container))
        new       (mapv (fn [c] [c (props-of c)]) comps)
        names     (mapv first (second (first new)))
        _         (when (empty? names)
                    (error! "A variant set needs at least one property" {:field "properties"}))
        _         (when-not (apply distinct? names)
                    (error! (str "Two properties would have the same name: " (str/join ", " names)) {:field "rename"}))
        dup       (->> new
                       (group-by (fn [[_ props]] (mapv (comp str/lower second) props)))
                       (vals)
                       (d/seek #(> (count %) 1)))
        _         (when dup
                    (error! (str "Variants must differ in at least one property: "
                                 (str/join " and " (map (fn [[c _]] (pr-str (:name (get (objects-of state) (:main-instance-id c)))))
                                                        (take 2 dup)))
                                 " would both be " (props-text (second (first dup))))
                            {:field "properties"}))]
    (second
     (run-sub state
              (fn [changes]
                [nil
                 (reduce (fn [changes [c props]]
                           (let [props (mapv (fn [[n v]] {:name n :value v}) props)]
                             (-> changes
                                 (pcb/update-component (:id c)
                                                       #(assoc % :variant-id container-id :variant-properties props
                                                               :name cname :path cpath))
                                 (pcb/update-shapes [(:main-instance-id c)]
                                                    #(assoc % :variant-id container-id
                                                            :variant-name (ctv/properties-to-name props)
                                                            :name (:name container))))))
                         changes
                         new)])))))

(defn- variant-defaults
  "The variant set board the editor makes: no fill, a purple dashed-look
  stroke, rounded, and (unless `layout` is false) a flex layout that
  arranges the variants."
  [layout]
  (let [flex (cond
               (map? layout) (merge {:direction "row" :gap 20 :padding 30} layout)
               (or (nil? layout) (true? layout)) {:direction "row" :gap 20 :padding 30}
               (false? layout) nil
               :else (error! "layout must be an object like {\"direction\": \"column\", \"gap\": 16} or false"
                             {:field "layout"}))]
    (d/without-nils
     {:fill "none"
      :radius 20
      :stroke {:color "#bb97d8" :width 2 :align "inner"}
      :layout flex
      :sizing (when flex {:h "hug" :v "hug"})})))

(defn- op-make-variants
  "Combines boards or main components into a variant set (a new one named
  `name`, or the existing set `container`), each with its `properties`,
  like \"Combine as variants\" in the editor. Boards that aren't
  components yet become components first."
  [state op]
  (props/check-keys! op [:op :ref :name :container :parent :x :y :layout :variants] "for makeVariants")
  (let [{:keys [variants container]} op
        _       (when-not (and (sequential? variants) (seq variants) (every? map? variants))
                  (error! (str "variants must be a list like [{\"id\": <board or main component>, "
                               "\"properties\": {\"Type\": \"Primary\"}}, …]")
                          {:field "variants"}))
        _       (doseq [v variants] (props/check-keys! v [:id :properties] "in a variant"))
        _       (when (and (some? container) (some #(contains? op %) [:name :parent :x :y :layout :ref]))
                  (error! "With \"container\" (an existing variant set) give only \"variants\"" {:field "container"}))
        _       (when (and (nil? container) (str/blank? (some-> (:name op) str)))
                  (error! (str "makeVariants needs \"name\" (the component, e.g. \"Button\") or \"container\" "
                               "(the board of an existing variant set)")
                          {:field "name"}))
        ids     (mapv #(:id (shape! state (:id %) "id" "Each variant")) variants)
        _       (when-not (apply distinct? ids) (error! "A shape is listed twice in variants" {:field "variants"}))
        props   (mapv (fn [v]
                        (let [p (when (some? (:properties v)) (variant-props! (:properties v) "properties"))]
                          (when (empty? p)
                            (error! "Each variant needs properties, e.g. {\"Type\": \"Primary\"}" {:field "properties"}))
                          p))
                      variants)
        ;; boards become components first
        state   (reduce (fn [state id]
                          (let [shape (get (objects-of state) id)]
                            (cond
                              (ctk/main-instance? shape)
                              (let [c (own-component! state shape "variants.id")]
                                (when (ctk/is-variant? c)
                                  (error! (str (pr-str (:name shape)) " is already a variant; change it with setVariant")
                                          {:field "variants.id"}))
                                state)

                              (and (cfh/frame-shape? shape) (not (ctk/instance-head? shape))
                                   (not (ctk/in-component-copy? shape)))
                              (op-make-component state {:op "makeComponent" :id (str id)})

                              :else
                              (error! (str (pr-str (:name shape)) ": variants are boards or main components") {:field "variants.id"}))))
                        state
                        ids)
        objects (objects-of state)
        cont    (when (some? container)
                  (let [c (shape! state container "container" "makeVariants")]
                    (when-not (ctk/is-variant-container? c)
                      (error! "container must be the board of a variant set" {:field "container"}))
                    c))
        state   (if cont
                  ;; the editor names a main the set's name when it joins the set
                  (update state :changes pcb/update-shapes ids #(assoc % :name (:name cont)))
                  (let [shapes    (mapv #(get objects %) ids)
                        parent-id (or (resolve-id state (:parent op) "parent") (:parent-id (first shapes)))
                        origin    (parent-origin objects parent-id)
                        rect      (gsh/shapes->rect shapes)
                        x         (number! (:x op) "x")
                        y         (number! (:y op) "y")]
                    (op-create state (merge {:op "create" :type "frame" :name (str/trim (str (:name op)))
                                             :parent (str parent-id)
                                             :x (or x (- (:x rect) (:x origin) 30))
                                             :y (or y (- (:y rect) (:y origin) 30))
                                             :width (+ (:width rect) 60) :height (+ (:height rect) 60)}
                                            (when (some? (:ref op)) {:ref (:ref op)})
                                            (variant-defaults (:layout op))))))
        cont-id (or (:id cont) (last (:created state)))
        start   (count (:shapes (get (objects-of state) cont-id)))
        state   (reduce (fn [state [i id]]
                          (op-move state {:op "move" :id (str id) :parent (str cont-id) :index (+ start i)}))
                        state
                        (map-indexed vector ids))
        state   (cond-> state
                  (nil? cont) (update :changes pcb/update-shapes [cont-id] #(assoc % :is-variant-container true)))
        comps   (variant-components state cont-id)
        by-main (into {} (map (juxt :main-instance-id :id)) comps)
        updates (into {} (map (fn [id p] [(get by-main id) p]) ids props))
        merged  (merged-props comps updates)]
    (-> (write-variant-props state cont-id #(get merged (:id %)))
        (update :variant-sets (fnil conj []) cont-id))))

(defn- variant-of!
  "[set-board-id component-id-or-nil] of a variant main or a set board."
  [state shape]
  (cond
    (ctk/is-variant-container? shape) [(:id shape) nil]
    (and (ctk/main-instance? shape) (ctk/is-variant? shape)) [(:variant-id shape) (:component-id shape)]
    :else (error! (str (pr-str (:name shape)) " is not a variant (the main of a variant component) nor a variant set")
                  {:field "id"})))

(defn- op-set-variant
  "Changes variant properties: `properties` of one variant (its main), and
  `rename` ({old: new}) / `remove` ([names]) for its whole set (the id of
  any variant or of the set's board)."
  [state op]
  (props/check-keys! op [:op :id :properties :rename :remove] "for setVariant")
  (let [shape   (shape! state (:id op) "id" "setVariant")
        [cont-id comp-id] (variant-of! state shape)
        _       (when-not (some #(contains? op %) [:properties :rename :remove])
                  (error! "setVariant needs \"properties\", \"rename\" or \"remove\"" {:field "op"}))
        _       (when (and (contains? op :properties) (nil? comp-id))
                  (error! "properties are set on one variant: give the id of its main, not of the set" {:field "properties"}))
        comps   (variant-components state cont-id)
        names   (mapv :name (:variant-properties (first comps)))
        unknown (fn [n field]
                  (when-not (some #{n} names)
                    (error! (str "No property named " (pr-str n) ". Properties: " (str/join ", " names)) {:field field})))
        rename  (when (contains? op :rename) (into {} (variant-props! (:rename op) "rename")))
        _       (doseq [[old new] rename]
                  (unknown old "rename")
                  (when (str/blank? new) (error! "New property names can't be empty" {:field "rename"})))
        remove* (when (contains? op :remove)
                  (let [r (:remove op)]
                    (when-not (and (sequential? r) (every? string? r))
                      (error! "remove must be a list of property names" {:field "remove"}))
                    (doseq [n r] (unknown n "remove"))
                    (set r)))
        updates (when comp-id {comp-id (variant-props! (:properties op) "properties")})
        comps'  (mapv (fn [c]
                        (update c :variant-properties
                                (fn [ps]
                                  (->> ps
                                       (remove #(contains? remove* (:name %)))
                                       (mapv #(update % :name (fn [n] (get rename n n))))))))
                      comps)
        merged  (merged-props comps' updates)]
    (write-variant-props state cont-id #(get merged (:id %)))))

(defn- copy-head!
  [state v op-name]
  (let [shape (shape! state v "id" op-name)]
    (when-not (and (ctk/instance-head? shape) (ctk/in-component-copy? shape))
      (error! (str op-name " needs a component copy (its top layer, or the top layer of a copy nested in it)"
                   (when (ctk/main-instance? shape) "; this is a main component"))
              {:field "id"}))
    shape))

(defn- prop-value
  [component n]
  (some #(when (= (str/lower n) (str/lower (:name %))) (:value %)) (:variant-properties component)))

(defn- swap-target
  "The component a swap goes to: `component` by name (and `variant`), or
  with only `variant`, the variant of the copy's set closest to its current
  one with those values (what the variant selector of the design tab
  does)."
  [state shape op]
  (let [libs    (libraries-now state)
        data    (get-in libs [(:component-file shape) :data])
        current (get-in data [:components (:component-id shape)])
        variant (when (contains? op :variant) (variant-props! (:variant op) "variant"))]
    (cond
      (some? (:component op))
      (let [c (library/find-asset (:assets state) :components (:component op) "component" (into {} variant))]
        [c (get-in libs [(::library/file-id c) :data])])

      (seq variant)
      (let [_       (when-not (ctk/is-variant? current)
                      (error! "This copy is not a variant: give \"component\" to swap it for another component"
                              {:field "variant"}))
            comps   (remove nil? (cfv/find-variant-components
                                  data (get-in data [:pages-index (:main-instance-page current) :objects])
                                  (:variant-id current)))
            names   (mapv :name (:variant-properties current))
            _       (doseq [[n] variant]
                      (when-not (some #(= (str/lower n) (str/lower %)) names)
                        (error! (str "No property named " (pr-str n) ". Properties: " (str/join ", " names))
                                {:field "variant"})))
            target  (mapv (fn [{:keys [name value]}]
                            (or (some (fn [[n v]] (when (= (str/lower n) (str/lower name)) v)) variant) value))
                          (:variant-properties current))
            valid   (filter (fn [c] (every? (fn [[n v]] (= (str/lower v) (str/lower (str (prop-value c n))))) variant))
                            comps)]
        (if (empty? valid)
          (error! (str "No variant has " (props-text variant) ". Variants: "
                       (str/join "; " (map #(props-text (map (juxt :name :value) (:variant-properties %))) comps)))
                  {:field "variant"})
          [(apply min-key #(ctv/distance target (mapv :value (:variant-properties %))) valid) data]))

      :else
      (error! "swap needs \"component\" (a component name) and/or \"variant\" ({property: value})" {:field "op"}))))

(defn- op-swap
  "Swaps a component copy for another component, or switches it to another
  variant, like the component menu and the variant selector of the design
  tab (`generate-component-swap`). By default the overrides of the copy
  (texts, colors, swapped icons, …) are kept on the layers the new
  component has too, like a variant switch in the editor."
  [state op]
  (props/check-keys! op [:op :id :component :variant :keepOverrides] "for swap")
  (let [shape   (copy-head! state (:id op) "swap")
        id      (:id shape)
        [target ldata] (swap-target state shape op)
        objects (objects-of state)]
    (if (and (= (:id target) (:component-id shape)) (= (:id ldata) (:component-file shape)))
      state
      (let [libs   (libraries-now state)
            tpage  (ctf/get-component-page ldata target)
            root   (ctf/get-component-root ldata target)
            _      (when (and tpage root
                              (cfh/components-nesting-loop? (cfh/get-children-with-self (:objects tpage) (:id root))
                                                            (cfh/get-parents-with-self objects (:parent-id shape))))
                     (error! "This would put a component inside itself" {:field "component"}))
            parent (get objects (:parent-id shape))
            index  (d/index-of (:shapes parent) id)
            cell   (when (ctl/grid-layout? parent) (ctl/get-cell-by-shape-id parent id))
            keep?  (not (false? (bool! (:keepOverrides op) "keepOverrides")))
            orig   (when keep? (cfh/get-children-with-self objects id))
            page   {:id (::page-id state) :objects objects}
            [new-shape state]
            (run-sub state
                     (fn [changes]
                       (let [[new-shape _ changes]
                             (cll/generate-component-swap changes objects shape ldata page libs (:id target)
                                                          index cell (select-keys shape ctk/swap-keep-attrs) keep?)
                             [changes _] (if keep?
                                           (clv/generate-keep-touched changes new-shape shape orig page libs ldata)
                                           [changes nil])]
                         [new-shape changes])))
            new-id (:id new-shape)]
        (-> state
            (touch (cfh/get-children-ids-with-self (objects-of state) new-id))
            (update :relocated (fnil conj []) new-id)
            (update :swapped (fnil conj []) new-id))))))

(defn- op-detach
  "Detaches a component copy: it becomes plain layers, no longer updated
  from its main (copies nested in it stay copies)."
  [state op]
  (props/check-keys! op [:op :id] "for detach")
  (let [shape     (copy-head! state (:id op) "detach")
        container (ctn/make-container (get-in (own-data state) [:pages-index (::page-id state)]) :page)]
    (-> (second (run-sub state (fn [changes]
                                 [nil (cll/generate-detach-instance changes container (libraries-now state) (:id shape))])))
        (update :detached (fnil conj []) (:id shape)))))

(defn- op-reset-overrides
  "Resets a component copy to its main: all overrides (texts, colors,
  sizes, swaps) are dropped, like \"Reset overrides\" in the editor."
  [state op]
  (props/check-keys! op [:op :id] "for resetOverrides")
  (let [shape     (copy-head! state (:id op) "resetOverrides")
        data      (own-data state)
        container (ctn/make-container (get-in data [:pages-index (::page-id state)]) :page)
        state     (second (run-sub state (fn [changes]
                                           [nil (cll/generate-reset-component changes {:id (:id data) :data data}
                                                                              (libraries-now state) container (:id shape))])))]
    (-> state
        (touch (cfh/get-children-ids-with-self (objects-of state) (:id shape)))
        (update :relocated (fnil conj []) (:id shape)))))

(defn changed-mains
  "Ids of the components of the edited file whose main instance (any layer
  of it) changed in this batch: their copies must be updated, as the editor
  does after editing a main. `before`: the page objects before the batch."
  [state before]
  (let [objects (objects-of state)
        file-id (::file-id state)
        ids     (into #{}
                      (mapcat (fn [c]
                                (when (= (:page-id c) (::page-id state))
                                  (case (:type c)
                                    (:add-obj :mod-obj :del-obj) [(:id c)]
                                    :mov-objects (cons (:parent-id c) (:shapes c))
                                    :reorder-children [(:parent-id c)]
                                    nil))))
                      (:redo-changes (:changes state)))]
    (into #{}
          (comp (mapcat (fn [id]
                          (let [objs (if (contains? objects id) objects before)]
                            (keep #(get objs %) (cons id (cfh/get-parent-ids objs id))))))
                (filter ctk/main-instance?)
                (filter #(= file-id (:component-file %)))
                (map :component-id))
          ids)))


(defn- color-name?
  [v]
  (and (string? v) (not (str/starts-with? v "#")) (not= "none" v)))

(defn- op-colors
  "The color values an op sets (fills, strokes, text colors, shadows, tints,
  spans), to tell whether it uses library colors."
  [op]
  (let [fills  (let [f (:fill op)] (if (sequential? f) f [f]))
        shadow (let [v (:shadow op)] (if (sequential? v) v [v]))
        spans  (when (sequential? (:spans op)) (filter map? (:spans op)))]
    (concat (map #(if (map? %) (:color %) %) fills)
            [(:textColor op) (:color op)
             (let [st (:stroke op)] (if (map? st) (:color st) st))]
            (map #(when (map? %) (:color %)) shadow)
            (mapcat (fn [sp] [(:textColor sp) (:fill sp)]) spans))))

(defn needs-libraries?
  "True when `ops` use library assets (components, library colors or
  typographies), so the caller loads the linked libraries before `build`."
  [ops]
  (boolean
   (when (sequential? ops)
     (some (fn [op]
             (when (map? op)
               (or (= "component" (some-> (:type op) str str/lower))
                   (contains? #{"linkassets" "swap" "detach" "resetoverrides"} (some-> (:op op) str str/lower))
                   (contains? op :typography)
                   (some #(and (map? %) (contains? % :typography)) (when (sequential? (:spans op)) (:spans op)))
                   (some color-name? (op-colors op)))))
           ops))))

(defn- place-cell
  [state id cell]
  (let [objects (objects-of state)
        parent  (get objects (:parent-id (get objects id)))]
    (when-not (ctl/grid-layout? parent)
      (error! "cell applies to the children of a board with a grid layout" {:field "cell"}))
    (update state :cells (fnil conj []) [id cell])))

(defn- ensure-tracks
  [grid rows cols]
  (as-> grid g
    (reduce (fn [g _] (ctl/add-grid-row g ctl/default-track-value)) g (range (count (:layout-grid-rows g)) rows))
    (reduce (fn [g _] (ctl/add-grid-column g ctl/default-track-value)) g (range (count (:layout-grid-columns g)) cols))))

(defn- place-grid-children
  "Puts the children of the grid boards the batch touched in cells, like
  the workspace after adding a child (`assign-cells`), and the shapes given
  a `cell` in theirs (adding rows and columns when needed)."
  [state]
  (let [objects (objects-of state)
        cells   (:cells state)
        grids   (into #{}
                      (filter #(ctl/grid-layout? objects %))
                      (concat (:reflow state) (map (fn [[id]] (:parent-id (get objects id))) cells)))]
    (if (empty? grids)
      state
      (let [changes (pcb/update-shapes
                     (:changes state) (vec grids)
                     (fn [grid objects]
                       (reduce (fn [grid [id {:keys [row column row-span column-span align-self justify-self]}]]
                                 (if (= (:id grid) (:parent-id (get objects id)))
                                   (let [grid (-> (ensure-tracks grid (+ row row-span -1) (+ column column-span -1))
                                                  (ctl/push-into-cell [id] row column))
                                         grid (if (or (> row-span 1) (> column-span 1))
                                                (ctl/resize-cell-area grid row column row column row-span column-span)
                                                grid)
                                         cell (ctl/get-cell-by-shape-id grid id)]
                                     (-> grid
                                         (cond-> cell
                                           (update-in [:layout-grid-cells (:id cell)] merge
                                                      (d/without-nils {:align-self align-self :justify-self justify-self})))
                                         (ctl/assign-cells objects)))
                                   grid))
                               (ctl/assign-cells grid objects)
                               cells))
                     {:with-objects? true})]
        (-> state
            (assoc :changes (pcb/reorder-grid-children changes (vec grids)))
            (update :reflow into grids))))))

(defn start
  "Empty edit state for a page (see `build` for `opts`, plus `:file-id`)."
  [page-id objects {:keys [fonts media libraries file-id tokens]}]
  {:changes (-> (pcb/empty-changes :mcp page-id)
                (pcb/with-objects objects))
   :refs {} :created [] :deleted #{} :texts #{} :reflow #{} :modifs {} :contain #{} :regroup #{} :rotate {}
   :fonts (vec fonts)
   :media (or media {})
   :tokens (or tokens {})
   ::page-id page-id
   ::file-id file-id
   :libraries (into {} (map (juxt :id identity)) libraries)
   :assets (library/index libraries)})

(defn touch
  "Marks shapes `ids`, changed outside of ops (e.g. by a library sync), for
  the layout pass: their texts are measured again, and their layouts and
  their parents' layouts reflowed."
  [state ids]
  (let [objects (objects-of state)
        alive   (filter #(contains? objects %) ids)]
    (reduce (fn [state id]
              (let [shape (get objects id)]
                (cond-> (touch-layout-parent state objects id)
                  (cfh/text-shape? shape) (update :texts conj id)
                  (ctl/any-layout? shape) (update :reflow conj id))))
            state
            alive)))

(defn needs-tokens?
  "True when `ops` apply design tokens, so the caller loads the file's
  tokens before `build`."
  [ops]
  (boolean (when (sequential? ops) (some #(and (map? %) (contains? % :tokens)) ops))))

(defn image-refs
  "Media ids that image fills in `ops` refer to, so the caller can fetch
  their metadata before `build`. Invalid ids are left for `build` to report."
  [ops]
  (when (sequential? ops)
    (into #{}
          (comp (filter map?)
                (mapcat (fn [op]
                          (let [f (:fill op)]
                            (cons (:image op) (map #(when (map? %) (:image %)) (if (sequential? f) f [f]))))))
                (keep #(when (string? %) (uuid/parse* %))))
          ops)))

(defn- apply-op
  [state op]
  (case (some-> (:op op) str str/lower)
    "create" (case (some-> (:type op) str str/lower)
               "component" (op-instance state op)
               "svg" (op-create-svg state op)
               "path" (op-create-path state op)
               (op-create state op))
    "update" (op-update state op)
    "makecomponent" (op-make-component state op)
    "delete" (op-delete state op)
    "move" (op-move state op)
    "linkassets" (op-link-assets state op)
    "annotate" (op-annotate state op)
    "makevariants" (op-make-variants state op)
    "setvariant" (op-set-variant state op)
    "swap" (op-swap state op)
    "detach" (op-detach state op)
    "resetoverrides" (op-reset-overrides state op)
    "group" (op-group state op)
    "ungroup" (op-ungroup state op)
    "boolean" (op-boolean state op)
    (error! (str "Unknown op " (pr-str (:op op))
                 ". Use create, update, delete, move, group, ungroup, boolean, makeComponent, "
                 "makeVariants, setVariant, swap, detach, resetOverrides, annotate or linkAssets")
            {:field "op"})))

(defn- apply-op*
  "`apply-op` with the `cell` of a grid child (any create or update)."
  [state op]
  (let [kind  (some-> (:op op) str str/lower)
        cell  (when (and (#{"create" "update"} kind) (contains? op :cell)) (cell! (:cell op)))
        state (sync-own-library (apply-op state (dissoc op :cell)))]
    (if cell
      (place-cell state (if (= kind "create") (last (:created state)) (resolve-id state (:id op) "id")) cell)
      state)))

(defn build
  "Applies `ops` (seq of maps with keywordized keys) to the page objects.
  Returns the state map; throws ex-info with a readable message on bad input.

  opts: `:fonts` the team's font families (`penpot-mcp.fonts/team-fonts`),
  `:media` media id -> {:width :height :mtype} for image fills,
  `:libraries` file id -> {:id :name :data}: the edited file and its linked
  libraries, for components, library colors and typographies (edited file
  first)."
  ([page-id objects ops] (build page-id objects ops nil))
  ([page-id objects ops opts]
   (when-not (sequential? ops)
     (error! "ops must be an array of operations" {:field "ops"}))
   (-> (reduce (fn [state [idx op]]
                 (try
                   (when-not (map? op)
                     (error! "Each op must be an object like {\"op\": \"create\", ...}" {:field "op"}))
                   (apply-op* state op)
                   (catch :default cause
                     (throw (ex-info (str "ops[" idx "]: " (ex-message cause))
                                     (assoc (ex-data cause) :index idx)
                                     cause)))))
               (start page-id objects opts)
               (map-indexed vector ops))
       (place-grid-children))))

;; --- text measuring with render-wasm (call after the scene and fonts are loaded)

(defn text-dimensions
  "Width/height of the laid-out text of shape `id` in the loaded WASM scene."
  [id]
  (wselect/use-shape id)
  (let [ptr (h/call rwasm/internal-module "_get_text_dimensions")]
    (when-not (zero? ptr)
      (let [offset (mem/->offset-32 ptr)
            heap   (mem/get-heap-f32)
            result {:width (aget heap offset) :height (aget heap (+ offset 1))}]
        (mem/free)
        result))))

(def ^:private POSITION-DATA-U32-SIZE 9)

(defn position-data
  "Position data of text shape `shape` from the loaded WASM scene."
  [shape]
  (wselect/use-shape (:id shape))
  (let [heapf32 (mem/get-heap-f32)
        heapu32 (mem/get-heap-u32)
        offset  (mem/->offset-32 (h/call rwasm/internal-module "_calculate_position_data"))
        length  (aget heapu32 offset)
        entries (vec (for [i (range length)
                           :let [o (+ offset 1 (* i POSITION-DATA-U32-SIZE))]]
                       {:paragraph (aget heapu32 o)
                        :span      (aget heapu32 (+ o 1))
                        :start-pos (aget heapu32 (+ o 2))
                        :end-pos   (aget heapu32 (+ o 3))
                        :x         (aget heapf32 (+ o 4))
                        :y         (aget heapf32 (+ o 5))
                        :width     (aget heapf32 (+ o 6))
                        :height    (aget heapf32 (+ o 7))
                        :direction (aget heapu32 (+ o 8))}))
        content (:content shape)]
    (mem/free)
    (into []
          (keep
           (fn [{:keys [paragraph span start-pos end-pos direction x y width height]}]
             (let [element (-> content :children (get 0) :children (get paragraph) :children (get span))
                   text    (:text element)]
               (when (and element text)
                 (d/patch-object
                  (txt/get-default-text-attrs)
                  (d/without-nils
                   {:x x
                    :y (+ y height)
                    :width width
                    :height height
                    :direction       (if (zero? direction) "rtl" "ltr")
                    :font-id         (get element :font-id)
                    :font-family     (get element :font-family)
                    :font-size       (str (get element :font-size) "px")
                    :font-weight     (get element :font-weight)
                    :text-transform  (get element :text-transform)
                    :text-decoration (get element :text-decoration)
                    :letter-spacing  (str (get element :letter-spacing) "px")
                    :font-style      (get element :font-style)
                    :fills           (get element :fills)
                    :text            (subs text (min start-pos end-pos) (max start-pos end-pos))}))))))
          entries)))

;; --- geometry

(defn objects
  "Current local copy of the page objects."
  [state]
  (objects-of state))

(defn- text-resize-modifiers
  [{:keys [points selrect grow-type] :as shape} dims]
  (when (and dims (not= :fixed grow-type))
    (let [width  (if (= grow-type :auto-height) (:width selrect) (max 1 (:width dims)))
          height (max 1 (:height dims))]
      (when (or (not= width (:width selrect)) (not= height (:height selrect)))
        (ctm/resize-modifiers (gpt/point (/ width (:width selrect)) (/ height (:height selrect)))
                              (first points)
                              (:transform shape)
                              (:transform-inverse shape))))))

(defn apply-geometry
  "Resizes measured texts and moves/resizes shapes, then lets Penpot's
  modifiers engine propagate everything through layouts and constraints, like
  the workspace's apply-modifiers. `dims` maps text id -> {:width :height}."
  [state dims]
  (let [objects    (objects-of state)
        alive?     #(contains? objects %)
        modif-tree (as-> {} $
                     (reduce (fn [tree [id modifiers]]
                               (cond-> tree (alive? id) (assoc id {:modifiers modifiers})))
                             $ (:modifs state))
                     (reduce (fn [tree id]
                               (if-let [m (and (alive? id) (text-resize-modifiers (get objects id) (get dims id)))]
                                 (update-in tree [id :modifiers] ctm/add-modifiers m)
                                 tree))
                             $ (:texts state))
                     (reduce (fn [tree id]
                               (cond-> tree
                                 (and (alive? id) (not (contains? tree id)))
                                 (assoc id {:modifiers (ctm/reflow-modifiers)})))
                             $ (:reflow state)))]
    (if (empty? modif-tree)
      state
      (let [result  (gm/set-objects-modifiers modif-tree objects {:ignore-constraints false})
            ids     (into [] (comp (map key) (remove #(= % uuid/zero))) result)
            ;; copies moved as a whole by a layout keep following their main
            ;; component (see penpot-mcp.touched)
            ignore  (touched/ignore-tree result objects)
            changes (reduce (fn [changes id]
                              (pcb/update-shapes changes [id]
                                                 (fn [shape]
                                                   (gsh/transform-shape shape (get-in result [(:id shape) :modifiers])))
                                                 (d/without-nils {:attrs transform-attrs
                                                                  :ignore-geometry? (get ignore id)})))
                            (:changes state)
                            ids)]
        (-> state
            (assoc :changes changes)
            (update :moved (fnil into #{}) ids))))))

(defn apply-rotations
  "Rotates the shapes given a `rotation` in the batch, around their center,
  once their size and position are set (like the rotation field of the
  design tab). Children rotate with their board or group."
  [state]
  (let [objects (objects-of state)
        tree    (into {}
                      (keep (fn [[id angle]]
                              (when-let [shape (get objects id)]
                                (let [delta (- angle (or (:rotation shape) 0))]
                                  (when-not (zero? (mod delta 360))
                                    [id {:modifiers (ctm/rotation-modifiers shape (gsh/shape->center shape) delta)}])))))
                      (:rotate state))
        state   (assoc state :rotate {})]
    (if (empty? tree)
      state
      (let [result  (gm/set-objects-modifiers tree objects {:ignore-constraints false})
            ids     (into [] (comp (map key) (remove #(= % uuid/zero))) result)
            changes (reduce (fn [changes id]
                              (pcb/update-shapes changes [id]
                                                 (fn [shape]
                                                   (gsh/transform-shape shape (get-in result [(:id shape) :modifiers])))
                                                 {:attrs transform-attrs}))
                            (:changes state)
                            ids)]
        (-> state
            (assoc :changes changes)
            (update :moved (fnil into #{}) ids))))))

(defn second-pass
  "Prepares a second geometry pass: auto-height texts whose width was just set
  by a layout (e.g. `sizing.h = fill`) need their height measured again with
  the new width, which the workspace does reactively."
  [state]
  (let [objects (objects-of state)
        ids     (into #{}
                      (filter (fn [id]
                                (let [shape (get objects id)]
                                  (and (cfh/text-shape? shape)
                                       (= :auto-height (:grow-type shape))))))
                      (:moved state))]
    (-> state
        (update :all-texts (fnil into #{}) (:texts state))
        (assoc :texts ids :modifs {} :reflow #{}))))

(defn text-ids
  "Text shapes whose position data must be (re)computed."
  [state]
  (let [objects (objects-of state)]
    (into #{}
          (filter #(cfh/text-shape? (get objects %)))
          (concat (:all-texts state) (:texts state) (:moved state)))))

(defn with-position-data
  [state pd-by-id]
  (if (empty? pd-by-id)
    state
    (update state :changes pcb/update-shapes (keys pd-by-id)
            (fn [shape] (assoc shape :position-data (get pd-by-id (:id shape))))
            {:attrs #{:position-data}})))

(defn- contain-transform
  "Image transform (fractions of the shape box) that shows the whole image,
  centered, at its aspect ratio."
  [w h iw ih]
  (when (and (pos? w) (pos? h) (pos? iw) (pos? ih))
    (let [image-ratio (/ iw ih)
          box-ratio   (/ w h)]
      (if (> image-ratio box-ratio)
        (let [th (/ box-ratio image-ratio)] {:x 0 :y (/ (- 1 th) 2) :width 1 :height th})
        (let [tw (/ image-ratio box-ratio)] {:x (/ (- 1 tw) 2) :y 0 :width tw :height 1})))))

(defn fit-images
  "Sets the transform of the `contain` image fills set in this batch from the
  shapes' final size (after layout), so the whole image shows."
  [state]
  (let [objects (objects-of state)
        ids     (filterv #(contains? objects %) (:contain state))]
    (if (empty? ids)
      state
      (update state :changes pcb/update-shapes ids
              (fn [shape]
                (let [{:keys [width height]} (:selrect shape)]
                  (update shape :fills
                          (fn [fills]
                            (mapv (fn [fill]
                                    (if-let [{iw :width ih :height} (:fill-image fill)]
                                      (if-let [tf (contain-transform width height iw ih)]
                                        (assoc-in fill [:fill-image :transform] tf)
                                        (update fill :fill-image dissoc :transform))
                                      fill))
                                  fills)))))))))

;; --- summaries for the caller / the AI

(defn- round [v] (when (number? v) (/ (js/Math.round (* v 100)) 100)))

(defn- num-val
  "A number from a number or a numeric string (text styles are strings)."
  [v]
  (let [n (cond (number? v) v (string? v) (js/parseFloat v))]
    (when (and (number? n) (js/isFinite n)) (round n))))

(defn- ref-name
  "Name of the library asset `id` refers to: from `names` (asset id ->
  {:name}), or a marker when its library could not be read."
  [names id]
  (when (some? id)
    (or (:name (get names id)) "(library asset not available)")))

(defn- fill-image [shape] (some-> shape :fills first :fill-image :id str))

(defn- gradient-summary
  [g]
  (when g
    (d/without-nils
     {:type (some-> (:type g) name)
      :stops (mapv (fn [{:keys [color opacity offset]}]
                     (d/without-nils {:color color :offset (round offset)
                                      :opacity (when (and (number? opacity) (< opacity 1)) (round opacity))}))
                   (:stops g))})))

(defn- fill-summary
  [names shape]
  (let [fills (vec (:fills shape))
        fill  (first fills)
        o     (:fill-opacity fill)]
    (d/without-nils
     {:fill (:fill-color fill)
      :fillOpacity (when (and (or (:fill-color fill) (:fill-color-gradient fill)) (number? o) (< o 1)) (round o))
      :fillAsset (ref-name names (:fill-color-ref-id fill))
      :fillGradient (gradient-summary (:fill-color-gradient fill))
      :fills (when (> (count fills) 1) (count fills))})))

(defn- effects-summary
  [shape]
  (d/without-nils
   {:shadow (some->> (:shadow shape)
                     (remove :hidden)
                     (map (fn [{:keys [style offset-x offset-y blur spread color]}]
                            (d/without-nils
                             {:style (when (= style :inner-shadow) "inner")
                              :x (round offset-x) :y (round offset-y) :blur (round blur)
                              :spread (when (and spread (not (zero? spread))) (round spread))
                              :color (:color color)
                              :opacity (round (:opacity color))})))
                     seq
                     vec)
    :blur (when-not (get-in shape [:blur :hidden]) (some-> (get-in shape [:blur :value]) round))
    :backgroundBlur (when-not (get-in shape [:background-blur :hidden])
                      (some-> (get-in shape [:background-blur :value]) round))
    :blendMode (let [b (:blend-mode shape)] (when (and b (not= :normal b)) (name b)))}))

(defn- stroke-summary
  [names shape]
  (let [strokes (vec (:strokes shape))
        s       (first strokes)
        o       (:stroke-opacity s)]
    (when (string? (:stroke-color s))
      (d/without-nils
       {:color (:stroke-color s)
        :width (round (:stroke-width s))
        :align (some-> (:stroke-alignment s) name)
        :opacity (when (and (number? o) (< o 1)) (round o))
        :asset (ref-name names (:stroke-color-ref-id s))
        :count (when (> (count strokes) 1) (count strokes))}))))

(defn- radius-summary
  [shape]
  (when (#{:rect :frame} (:type shape))
    (let [rs (mapv #(or (round (get shape %)) 0) [:r1 :r2 :r3 :r4])]
      (cond
        (every? zero? rs) nil
        (apply = rs) (first rs)
        :else rs))))

(defn- text-style
  [names node]
  (d/without-nils
   {:fontFamily (let [f (:font-family node)] (if (= "sourcesanspro" f) "Source Sans Pro" f))
    :fontWeight (some-> (:font-weight node) str)
    :fontStyle (when (= "italic" (some-> (:font-style node) name)) "italic")
    :fontSize (num-val (:font-size node))
    :lineHeight (num-val (:line-height node))
    :letterSpacing (let [v (num-val (:letter-spacing node))] (when (and v (not (zero? v))) v))
    :textTransform (let [t (some-> (:text-transform node) name)]
                     (when (and (some? t) (not= "none" t) (not= "" t)) t))
    :typography (ref-name names (:typography-ref-id node))}))

(defn- text-summary
  "Color and style of the first span of a text; `mixedStyles` when the
  other spans differ."
  [names shape]
  (let [nodes (txt/node-seq txt/is-text-node? (:content shape))
        node  (first nodes)
        fill  (first (:fills node))
        style #(-> (select-keys % [:font-id :font-variant-id :font-size :line-height :letter-spacing
                                   :text-transform :typography-ref-id])
                   (assoc :color (some-> % :fills first :fill-color)))]
    (when node
      (let [mixed? (some #(not= (style %) (style node)) (rest nodes))]
        (d/without-nils
         {:textColor (:fill-color fill)
          :textColorAsset (ref-name names (:fill-color-ref-id fill))
          :textStyle (text-style names node)
          :textDecoration (let [t (:text-decoration node)] (when (and t (not= "none" t)) t))
          :verticalAlign (let [v (:vertical-align (:content shape))] (when (and v (not= "top" v)) v))
          :mixedStyles (when mixed? true)
          :spans (when mixed?
                   (let [base (text-style names node)]
                     (mapv (fn [n]
                             (let [st (text-style names n)]
                               (merge {:text (:text n)}
                                      (into {} (remove (fn [[k v]] (= v (get base k)))) st)
                                      (d/without-nils
                                       {:textColor (let [c (some-> n :fills first :fill-color)]
                                                     (when (not= c (:fill-color fill)) c))
                                        :textDecoration (let [t (:text-decoration n)]
                                                          (when (and t (not= "none" t)) t))}))))
                           nodes)))})))))

(defn- track-summary
  [{:keys [type value]}]
  (case type
    :fixed (round value)
    :flex (str (round value) "fr")
    :percent (str (round value) "%")
    "auto"))

(defn- not-default
  [v default]
  (when (and (some? v) (not= v default)) (name v)))

(defn- layout-summary
  [shape]
  (cond
    (ctl/flex-layout? shape)
    (d/without-nils
     {:type "flex"
      :direction (some-> (:layout-flex-dir shape) name)
      :gap (:layout-gap shape)
      :padding (:layout-padding shape)
      :wrap (when (= :wrap (:layout-wrap-type shape)) true)
      :alignItems (not-default (:layout-align-items shape) :start)
      :justifyContent (not-default (:layout-justify-content shape) :start)})

    (ctl/grid-layout? shape)
    (d/without-nils
     {:type "grid"
      :direction (not-default (:layout-grid-dir shape) :row)
      :columns (mapv track-summary (:layout-grid-columns shape))
      :rows (mapv track-summary (:layout-grid-rows shape))
      :gap (:layout-gap shape)
      :padding (:layout-padding shape)
      :alignItems (not-default (:layout-align-items shape) :start)
      :justifyItems (not-default (:layout-justify-items shape) :start)})))

(defn- cell-summary
  [objects shape]
  (let [parent (get objects (:parent-id shape))]
    (when (ctl/grid-layout? parent)
      (when-let [{:keys [row column row-span column-span align-self justify-self]}
                 (ctl/get-cell-by-shape-id parent (:id shape))]
        (d/without-nils
         {:row row :column column
          :rowSpan (when (> row-span 1) row-span)
          :columnSpan (when (> column-span 1) column-span)
          :alignSelf (not-default align-self :auto)
          :justifySelf (not-default justify-self :auto)})))))

(defn- item-summary
  [objects shape]
  (let [in-layout? (ctl/any-layout? objects (:parent-id shape))
        {:keys [m1 m2 m3 m4]} (:layout-item-margin shape)
        margin (when (some #(and % (not (zero? %))) [m1 m2 m3 m4]) [(or m1 0) (or m2 0) (or m3 0) (or m4 0)])]
    (d/without-nils
     {:margin margin
      :minWidth (round (:layout-item-min-w shape))
      :maxWidth (round (:layout-item-max-w shape))
      :minHeight (round (:layout-item-min-h shape))
      :maxHeight (round (:layout-item-max-h shape))
      :alignSelf (some-> (:layout-item-align-self shape) name)
      :absolute (when (:layout-item-absolute shape) true)
      :zIndex (let [z (:layout-item-z-index shape)] (when (and z (not (zero? z))) z))
      :constraints (when (and (not in-layout?)
                              (cfh/frame-shape? (get objects (:parent-id shape)))
                              (not= uuid/zero (:parent-id shape)))
                     (let [h (:constraints-h shape) v (:constraints-v shape)]
                       (when (or (and h (not= h :left)) (and v (not= v :top)))
                         (d/without-nils {:h (some-> h name) :v (some-> v name)}))))})))

(defn shape-summary
  "Compact, JSON-friendly description of a shape (x/y relative to its
  parent). `names` (asset id -> {:name}) names the library colors and
  typographies the shape uses."
  ([objects shape] (shape-summary objects shape nil))
  ([objects shape names]
   (let [origin (parent-origin objects (:parent-id shape))
         {:keys [x y width height]} (:selrect shape)
         text?  (cfh/text-shape? shape)
         o      (:opacity shape)]
     (merge
      (d/without-nils
       {:id (str (:id shape))
        :name (:name shape)
        :type (name (:type shape))
        :x (round (- x (:x origin)))
        :y (round (- y (:y origin)))
        :width (round width)
        :height (round height)})
      (fill-summary names shape)
      (effects-summary shape)
      (d/without-nils
       {:image (fill-image shape)
        :bool (some-> (:bool-type shape) name)
        :stroke (stroke-summary names shape)
        :radius (radius-summary shape)
        :opacity (when (and (number? o) (< o 1)) (round o))
        :rotation (let [r (:rotation shape)] (when (and (number? r) (not (zero? (round r)))) (round r)))
        :text (when text? (txt/content->text (:content shape)))})
      (when text? (text-summary names shape))
      (d/without-nils
       {:layout (layout-summary shape)
        :cell (cell-summary objects shape)
        :sizing (let [h (:layout-item-h-sizing shape) v (:layout-item-v-sizing shape)]
                  (when (or h v) (d/without-nils {:h (some-> h name) :v (some-> v name)})))
        :component (when (ctk/instance-head? shape)
                     (let [info (get names (:component-id shape))]
                       (d/without-nils
                        {:id (str (:component-id shape))
                         :name (or (:name info) "(component not available)")
                         :variant (:variant info)
                         :main (when (ctk/main-instance? shape) true)
                         :nested (when (and (not (:component-root shape)) (not (ctk/main-instance? shape))) true)})))
        :variantSet (when (ctk/is-variant-container? shape) true)
        :tokens (some->> (:applied-tokens shape) not-empty (into {} (map (fn [[k v]] [(name k) v]))))
        :hidden (when (:hidden shape) true)})
      (item-summary objects shape)))))

(defn tree
  "Nested summary of `root-id`'s subtree, at most `max-depth` levels deep.
  Children are listed like Penpot's layers panel (top layer first), which in
  flex boards is also their visual order."
  ([objects root-id max-depth] (tree objects root-id max-depth nil))
  ([objects root-id max-depth names]
   (letfn [(walk [id depth]
             (let [shape (get objects id)]
               (when shape
                 (cond-> (if (= id uuid/zero)
                           {:id (str uuid/zero) :type "root"}
                           (shape-summary objects shape names))
                   (seq (:shapes shape))
                   (assoc :children
                          (if (< depth max-depth)
                            ;; layers panel order (top layer first) = visual order in flex boards
                            (into [] (keep #(walk % (inc depth))) (rseq (vec (:shapes shape))))
                            (str (count (:shapes shape)) " children (increase depth to see them)")))))))]
     (walk root-id 0))))

(defn ref-file-ids
  "Ids of the files that library refs (colors, typographies) of `shapes`
  point to, to name them in summaries."
  [shapes]
  (into #{}
        (comp (mapcat (fn [shape]
                        (concat [(:component-file shape)]
                                (map :fill-color-ref-file (:fills shape))
                                (map :stroke-color-ref-file (:strokes shape))
                                (when (cfh/text-shape? shape)
                                  (mapcat (fn [node] (cons (:typography-ref-file node)
                                                           (map :fill-color-ref-file (:fills node))))
                                          (tree-seq map? :children (:content shape)))))))
              (remove nil?))
        shapes))
