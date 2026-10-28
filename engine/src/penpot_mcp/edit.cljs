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
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.render-wasm.api.select :as wselect]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.wasm :as rwasm]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [penpot-mcp.fonts :as fonts]
   [penpot-mcp.library :as library]
   [penpot-mcp.props :as props]
   [penpot-mcp.touched :as touched]
   [penpot-mcp.util :as u]))

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
  (let [fill (:fill op)]
    (cond
      (and (map? fill) (some? (:image fill))) (or (->kw (:fit fill) image-fits "fit") :cover)
      (some? (:image op)) (or (->kw (:fit op) image-fits "fit") :cover))))

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

(defn- fills-attrs
  "`fill`: \"#rrggbb\", a library color name, \"none\", {\"color\", \"opacity\"}
  or {\"image\", \"fit\", \"opacity\"}; `fillOpacity` applies to it."
  [state op]
  (let [{:keys [fill fillOpacity]} op]
    (cond
      (contains? op :fill)
      {:fills
       (cond
         (or (nil? fill) (= "none" fill))
         []

         (string? fill)
         [(color-fill state fill fillOpacity "fill")]

         (map? fill)
         (let [{:keys [color image fit opacity]} fill
               opacity (if (some? opacity) opacity fillOpacity)]
           (cond
             (and (some? image) (some? color))
             (error! "fill takes either \"color\" or \"image\", not both" {:field "fill"})

             (some? image)
             [(image-fill state image fit opacity "fill.image")]

             (some? color)
             (do (when (some? fit)
                   (error! "\"fit\" only applies to image fills" {:field "fill.fit"}))
                 [(color-fill state color opacity "fill.color")])

             :else
             (error! "fill needs \"color\" or \"image\"" {:field "fill"})))

         :else
         (error! (str "Invalid fill: " (pr-str fill)
                      " (use \"#rrggbb\", \"none\" or {\"image\": <media id>, \"fit\": \"cover\"})")
                 {:field "fill" :value fill}))}

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
         (let [{:keys [color width align opacity]} (if (string? stroke) {:color stroke} stroke)
               {c :color lib-opacity :opacity :keys [ref-id ref-file]} (paint state color "stroke.color")]
           (when (and ref-id (some? opacity))
             (error! "A library color sets its own opacity: remove stroke.opacity or use a #rrggbb color"
                     {:field "stroke.opacity"}))
           [(d/without-nils
             {:stroke-color c
              :stroke-opacity (or (number! opacity "stroke.opacity" :min 0 :max 1) lib-opacity 1)
              :stroke-color-ref-id ref-id
              :stroke-color-ref-file ref-file
              :stroke-width (or (number! width "stroke.width" :min 0) 1)
              :stroke-style :solid
              :stroke-alignment (or (->kw align #{:inner :center :outer} "stroke.align") :inner)})])

         :else
         (error! (str "Invalid stroke: " (pr-str stroke)
                      " (use \"#rrggbb\", \"none\" or {\"color\", \"width\", \"align\"})")
                 {:field "stroke" :value stroke}))})))

(defn- radius-attrs
  [op]
  (when (contains? op :radius)
    (let [[r1 r2 r3 r4] (four-sides (or (:radius op) 0) "radius")]
      {:r1 r1 :r2 r2 :r3 r3 :r4 r4})))

(defn- layout-attrs
  "Flex layout attrs from `layout` ({type direction gap padding alignItems ...}),
  or ::remove for `false` / {type: \"none\"}."
  [layout]
  (cond
    (nil? layout) nil
    (or (false? layout) (= "none" layout) (and (map? layout) (= "none" (:type layout)))) ::remove
    (or (true? layout) (= "flex" layout)) (layout-attrs {})
    (not (map? layout))
    (error! (str "Invalid layout: " (pr-str layout) " (use {\"type\": \"flex\", ...} or false)")
            {:field "layout" :value layout})
    :else
    (let [{:keys [type direction gap rowGap columnGap padding alignItems
                  justifyContent alignContent wrap]} layout
          _ (when-not (contains? #{nil "flex"} type)
              (error! (str "Invalid layout.type " (pr-str type) ": only flex layout is supported for now")
                      {:field "layout.type" :value type}))
          [p1 p2 p3 p4] (when (some? padding) (four-sides padding "layout.padding"))
          justify #{:start :center :end :space-between :space-around :space-evenly :stretch}]
      (d/without-nils
       {:layout :flex
        :layout-flex-dir (->kw direction #{:row :row-reverse :column :column-reverse} "layout.direction")
        :layout-gap (when (or (some? gap) (some? rowGap) (some? columnGap))
                      {:row-gap (or (number! rowGap "layout.rowGap" :min 0) (number! gap "layout.gap" :min 0) 0)
                       :column-gap (or (number! columnGap "layout.columnGap" :min 0) (number! gap "layout.gap" :min 0) 0)})
        :layout-padding (when p1 {:p1 p1 :p2 p2 :p3 p3 :p4 p4})
        :layout-padding-type (when p1 (if (and (= p1 p3) (= p2 p4)) :simple :multiple))
        :layout-align-items (->kw alignItems #{:start :end :center :stretch} "layout.alignItems")
        :layout-justify-content (->kw justifyContent justify "layout.justifyContent")
        :layout-align-content (->kw alignContent justify "layout.alignContent")
        :layout-wrap-type (when (some? (bool! wrap "layout.wrap")) (if wrap :wrap :nowrap))}))))

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
  (let [{:keys [fontSize textAlign lineHeight letterSpacing textTransform fill]} op
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
       :fills (when paint*
                [(d/without-nils
                  {:fill-color (:color paint*)
                   :fill-opacity (or (:opacity paint*) 1)
                   :fill-color-ref-id (:ref-id paint*)
                   :fill-color-ref-file (:ref-file paint*)})])}))))

(defn- text-op?
  [op]
  (some #(contains? op %) [:text :fontFamily :fontSize :fontWeight :fontStyle :textColor :fill
                           :textAlign :lineHeight :letterSpacing :textTransform :typography]))

(defn- text-content
  "New text content for `op`, keeping the current text and styles of `content`
  where the op doesn't override them."
  [state content op]
  (let [base   (first (txt/node-seq txt/is-text-node? content))
        styles (text-styles state op (or base {:fills [{:fill-color "#000000" :fill-opacity 1}]}))
        text   (if (contains? op :text)
                 (str (:text op))
                 (or (some-> content txt/content->text) ""))]
    (txt/change-text nil text styles)))

;; --- shape attrs shared by create and update

(defn- prop-attrs
  "Attrs of the generic props. `type` is the shape type; prop/type
  combinations were checked by `props/check-shape-op!` before."
  [state type op]
  (merge
   (when-not (= type :text) (fills-attrs state op))
   (stroke-attrs state op)
   (when (#{:rect :frame} type) (radius-attrs op))
   (sizing-attrs (:sizing op))
   (d/without-nils
    {:name (some-> (:name op) str)
     :opacity (number! (:opacity op) "opacity" :min 0 :max 1)
     :hidden (some-> (bool! (:visible op) "visible") not)
     :show-content (when (= type :frame) (some-> (bool! (:clip op) "clip") not))
     :grow-type (when (= type :text)
                  (->kw (:growType op) #{:auto-width :auto-height :fixed} "growType"))})))

(defn- with-layout
  [shape layout]
  (case layout
    nil shape
    ::remove (apply dissoc shape layout-keys)
    (merge (if (ctl/flex-layout? shape) shape (merge shape initial-flex-layout)) layout)))

;; --- operations

(def ^:private shape-types
  {"frame" :frame "board" :frame "rect" :rect "rectangle" :rect
   "ellipse" :circle "circle" :circle "text" :text "image" :image})

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

(defn- op-create
  [state {:keys [type parent ref x y] :as op}]
  (let [kind      (or (get shape-types (some-> type str str/lower))
                      (error! (str "Invalid type " (pr-str type) ". Use frame, rect, ellipse, text or image")
                              {:field "type" :value type}))
        _         (props/check-shape-op! :create kind op)
        image?    (= kind :image)
        type*     (if image? :rect kind)
        objects   (objects-of state)
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
                      (merge (prop-attrs state type* (cond-> (dissoc op :growType)
                                                       image? (dissoc :fillOpacity))))
                      (cond-> image? (assoc :fills [img-fill]))
                      (cond-> (= type* :frame) (with-layout (layout-attrs (:layout op))))
                      (cond-> text? (dissoc :position-data)))
        ;; like the workspace (Plugin API appendChild): in a flex/grid board the
        ;; new child goes to index 0 of :shapes, which is the end of the layout
        ;; (layout order = layers panel order = reverse of :shapes)
        changes   (pcb/add-object (:changes state) shape
                                  (when (ctl/any-layout? parent) {:index 0}))
        state     (cond-> (assoc state :changes changes)
                    (some? ref) (update :refs assoc (str ref) id)
                    true (update :created conj id)
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

(declare op-update)

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
        data      (library/library-data (:libraries state) comp)
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
        overrides (select-keys op [:name :width :height :sizing :visible :opacity])
        state     (if (seq overrides) (op-update state (assoc overrides :op "update" :id (str id))) state)
        objects   (objects-of state)
        candidates (descendant-texts objects id)]
    (reduce (fn [state [layer text]]
              (let [layer  (name layer)
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
        text?   (= type :text)
        layout  (when (= type :frame) (layout-attrs (:layout op)))
        attrs   (prop-attrs state type op)
        fit     (image-fit op)
        update-fn (fn [shape]
                    (cond-> (merge shape attrs)
                      (some? layout) (with-layout layout)
                      (and text? (text-op? op)) (-> (assoc :content (text-content state (:content shape) op))
                                                    (dissoc :position-data))))
        changes (pcb/update-shapes (:changes state) [id] update-fn)
        modifs  (geometry-modifiers objects shape op)
        state   (cond-> (assoc state :changes changes)
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
        (update :deleted into ids))))

(defn- op-make-component
  "Turns a board into a main component of the file's library, like
  \"Create component\" in the editor (`generate-add-component`). The board
  stays where it is as the component's main instance."
  [state op]
  (props/check-keys! op [:op :id :name] "for makeComponent")
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
        [_ component-id changes]
        (cll/generate-add-component (:changes state) [(get objects id)] objects (::page-id state) (::file-id state)
                                    cfsh/prepare-create-artboard-from-selection)]
    (-> state
        (assoc :changes changes)
        (update :components (fnil conj []) component-id))))

(defn- color-name?
  [v]
  (and (string? v) (not (str/starts-with? v "#")) (not= "none" v)))

(defn needs-libraries?
  "True when `ops` use library assets (components, library colors or
  typographies), so the caller loads the linked libraries before `build`."
  [ops]
  (boolean
   (when (sequential? ops)
     (some (fn [op]
             (when (map? op)
               (or (= "component" (some-> (:type op) str str/lower))
                   (contains? op :typography)
                   (color-name? (:fill op))
                   (color-name? (:textColor op))
                   (and (map? (:fill op)) (color-name? (:color (:fill op))))
                   (color-name? (:stroke op))
                   (and (map? (:stroke op)) (color-name? (:color (:stroke op)))))))
           ops))))

(defn start
  "Empty edit state for a page (see `build` for `opts`, plus `:file-id`)."
  [page-id objects {:keys [fonts media libraries file-id]}]
  {:changes (-> (pcb/empty-changes :mcp page-id)
                (pcb/with-objects objects))
   :refs {} :created [] :deleted #{} :texts #{} :reflow #{} :modifs {} :contain #{}
   :fonts (vec fonts)
   :media (or media {})
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

(defn image-refs
  "Media ids that image fills in `ops` refer to, so the caller can fetch
  their metadata before `build`. Invalid ids are left for `build` to report."
  [ops]
  (when (sequential? ops)
    (into #{}
          (keep (fn [op]
                  (when (map? op)
                    (let [image (or (:image op) (when (map? (:fill op)) (:image (:fill op))))]
                      (when (string? image) (uuid/parse* image))))))
          ops)))

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
   (let [init (start page-id objects opts)]
     (reduce (fn [state [idx op]]
               (try
                 (when-not (map? op)
                   (error! "Each op must be an object like {\"op\": \"create\", ...}" {:field "op"}))
                 (case (some-> (:op op) str str/lower)
                   "create" (if (= "component" (some-> (:type op) str str/lower))
                              (op-instance state op)
                              (op-create state op))
                   "update" (op-update state op)
                   "makecomponent" (op-make-component state op)
                   "delete" (op-delete state op)
                   (error! (str "Unknown op " (pr-str (:op op)) ". Use create, update, delete or makeComponent") {:field "op"}))
                 (catch :default cause
                   (throw (ex-info (str "ops[" idx "]: " (ex-message cause))
                                   (assoc (ex-data cause) :index idx)
                                   cause)))))
             init
             (map-indexed vector ops)))))

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

(defn- fill-color [shape] (some-> shape :fills first :fill-color))

(defn- fill-image [shape] (some-> shape :fills first :fill-image :id str))

(defn shape-summary
  "Compact, JSON-friendly description of a shape (x/y relative to its parent)."
  [objects shape]
  (let [origin (parent-origin objects (:parent-id shape))
        {:keys [x y width height]} (:selrect shape)]
    (d/without-nils
     {:id (str (:id shape))
      :name (:name shape)
      :type (name (:type shape))
      :x (round (- x (:x origin)))
      :y (round (- y (:y origin)))
      :width (round width)
      :height (round height)
      :fill (fill-color shape)
      :image (fill-image shape)
      :text (when (cfh/text-shape? shape) (txt/content->text (:content shape)))
      :layout (when (ctl/flex-layout? shape)
                (d/without-nils
                 {:type "flex"
                  :direction (some-> (:layout-flex-dir shape) name)
                  :gap (:layout-gap shape)
                  :padding (:layout-padding shape)}))
      :sizing (let [h (:layout-item-h-sizing shape) v (:layout-item-v-sizing shape)]
                (when (or h v) (d/without-nils {:h (some-> h name) :v (some-> v name)})))
      :component (when (:component-root shape) (str (:component-id shape)))
      :hidden (when (:hidden shape) true)})))

(defn tree
  "Nested summary of `root-id`'s subtree, at most `max-depth` levels deep.
  Children are listed like Penpot's layers panel (top layer first), which in
  flex boards is also their visual order."
  [objects root-id max-depth]
  (letfn [(walk [id depth]
            (let [shape (get objects id)]
              (when shape
                (cond-> (if (= id uuid/zero)
                          {:id (str uuid/zero) :type "root"}
                          (shape-summary objects shape))
                  (seq (:shapes shape))
                  (assoc :children
                         (if (< depth max-depth)
                           ;; layers panel order (top layer first) = visual order in flex boards
                           (into [] (keep #(walk % (inc depth))) (rseq (vec (:shapes shape))))
                           (str (count (:shapes shape)) " children (increase depth to see them)")))))))]
    (walk root-id 0)))
