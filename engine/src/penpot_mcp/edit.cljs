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
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.render-wasm.api.select :as wselect]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.wasm :as rwasm]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

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

;; Builtin font (Source Sans Pro) variant ids by weight.
(def ^:private builtin-variants
  {"200" "200" "300" "300" "400" "regular" "600" "600" "700" "bold" "900" "black"})

;; --- input parsing

(defn- error!
  [msg data]
  (throw (ex-info msg (assoc data :type :invalid-op))))

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

(defn- number!
  [v field & {:keys [min]}]
  (when (some? v)
    (if (and (number? v) (js/isFinite v) (or (nil? min) (>= v min)))
      v
      (error! (str "Invalid " field ": " (pr-str v)) {:field field :value v}))))

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

(defn- fills-attrs
  [op]
  (when (contains? op :fill)
    (let [{:keys [fill fillOpacity]} op]
      {:fills (if (or (nil? fill) (= "none" fill))
                []
                [{:fill-color (color! fill "fill")
                  :fill-opacity (or (number! fillOpacity "fillOpacity" :min 0) 1)}])})))

(defn- stroke-attrs
  [op]
  (when (contains? op :stroke)
    (let [stroke (:stroke op)]
      {:strokes
       (if (or (nil? stroke) (= "none" stroke))
         []
         (let [{:keys [color width align opacity]} (if (string? stroke) {:color stroke} stroke)]
           [{:stroke-color (color! color "stroke.color")
             :stroke-opacity (or (number! opacity "stroke.opacity" :min 0) 1)
             :stroke-width (or (number! width "stroke.width" :min 0) 1)
             :stroke-style :solid
             :stroke-alignment (or (->kw align #{:inner :center :outer} "stroke.align") :inner)}]))})))

(defn- radius-attrs
  [type op]
  (when (and (contains? op :radius) (#{:rect :frame} type))
    (let [[r1 r2 r3 r4] (four-sides (or (:radius op) 0) "radius")]
      {:r1 r1 :r2 r2 :r3 r3 :r4 r4})))

(defn- layout-attrs
  "Flex layout attrs from `layout` ({type direction gap padding alignItems ...}),
  or ::remove for `false` / {type: \"none\"}."
  [layout]
  (cond
    (nil? layout) nil
    (or (false? layout) (= "none" (:type layout))) ::remove
    :else
    (let [{:keys [type direction gap rowGap columnGap padding alignItems
                  justifyContent alignContent wrap]} layout
          _ (when-not (contains? #{nil "flex"} type)
              (error! "Only flex layout is supported for now" {:field "layout.type" :value type}))
          [p1 p2 p3 p4] (when (some? padding) (four-sides padding "layout.padding"))
          justify #{:start :center :end :space-between :space-around :space-evenly :stretch}]
      (d/without-nils
       {:layout :flex
        :layout-flex-dir (->kw direction #{:row :row-reverse :column :column-reverse} "layout.direction")
        :layout-gap (when (or gap rowGap columnGap)
                      {:row-gap (or (number! rowGap "layout.rowGap" :min 0) (number! gap "layout.gap" :min 0) 0)
                       :column-gap (or (number! columnGap "layout.columnGap" :min 0) (number! gap "layout.gap" :min 0) 0)})
        :layout-padding (when p1 {:p1 p1 :p2 p2 :p3 p3 :p4 p4})
        :layout-padding-type (when p1 (if (and (= p1 p3) (= p2 p4)) :simple :multiple))
        :layout-align-items (->kw alignItems #{:start :end :center :stretch} "layout.alignItems")
        :layout-justify-content (->kw justifyContent justify "layout.justifyContent")
        :layout-align-content (->kw alignContent justify "layout.alignContent")
        :layout-wrap-type (when (some? wrap) (if wrap :wrap :nowrap))}))))

(defn- sizing-attrs
  [sizing]
  (when (map? sizing)
    (let [aliases {:hug :auto :fixed :fix}
          allowed #{:fix :fill :auto}]
      (d/without-nils
       {:layout-item-h-sizing (->kw (:h sizing) allowed "sizing.h" aliases)
        :layout-item-v-sizing (->kw (:v sizing) allowed "sizing.v" aliases)}))))

(defn- text-styles
  "Text node styles from the op, over `base` (the first text node of the
  existing content, if any)."
  [op base]
  (let [{:keys [fontSize fontWeight textAlign lineHeight letterSpacing]} op
        ;; on text shapes `fill` means the text color
        textColor (or (:textColor op) (when (string? (:fill op)) (:fill op)))
        weight (some-> fontWeight str)
        _ (when (and weight (not (contains? builtin-variants weight)))
            (error! (str "Unsupported fontWeight " weight ". Use one of "
                         (str/join ", " (sort (keys builtin-variants))))
                    {:field "fontWeight" :value fontWeight}))]
    (merge
     (select-keys base txt/text-all-attrs)
     (d/without-nils
      {:font-size (some-> (number! fontSize "fontSize" :min 1) str)
       :font-weight weight
       :font-variant-id (get builtin-variants weight)
       :text-align (some-> (->kw textAlign #{:left :center :right :justify} "textAlign") name)
       :line-height (some-> (number! lineHeight "lineHeight" :min 0) str)
       :letter-spacing (some-> (number! letterSpacing "letterSpacing") str)
       :fills (when textColor [{:fill-color (color! textColor "textColor") :fill-opacity 1}])}))))

(defn- text-op?
  [op]
  (some #(contains? op %) [:text :fontSize :fontWeight :textColor :fill :textAlign :lineHeight :letterSpacing]))

(defn- text-content
  "New text content for `op`, keeping the current text and styles of `content`
  where the op doesn't override them."
  [content op]
  (let [base   (first (txt/node-seq txt/is-text-node? content))
        styles (text-styles op (or base {:fills [{:fill-color "#000000" :fill-opacity 1}]}))
        text   (if (contains? op :text)
                 (str (:text op))
                 (or (some-> content txt/content->text) ""))]
    (txt/change-text nil text styles)))

;; --- shape attrs shared by create and update

(defn- prop-attrs
  [type op]
  (merge
   (when-not (= type :text) (fills-attrs op))
   (stroke-attrs op)
   (radius-attrs type op)
   (sizing-attrs (:sizing op))
   (d/without-nils
    {:name (some-> (:name op) str)
     :opacity (number! (:opacity op) "opacity" :min 0)
     :hidden (when (contains? op :visible) (not (:visible op)))
     :show-content (when (and (= type :frame) (contains? op :clip)) (not (:clip op)))
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
   "ellipse" :circle "circle" :circle "text" :text})

(def ^:private default-names
  {:frame "Board" :rect "Rectangle" :circle "Ellipse" :text "Text"})

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
  [state {:keys [type parent ref x y width height] :as op}]
  (let [objects   (objects-of state)
        type*     (or (get shape-types (some-> type str/lower))
                      (error! (str "Invalid type " (pr-str type) ". Use frame, rect, ellipse or text")
                              {:field "type" :value type}))
        parent-id (or (resolve-id state parent "parent") uuid/zero)
        parent    (or (get objects parent-id)
                      (error! (str "Parent " parent-id " not found on this page") {:field "parent"}))
        _         (when-not (#{:frame :group} (:type parent))
                    (error! "Parent must be a board (frame) or a group" {:field "parent" :value parent}))
        frame-id  (if (cfh/frame-shape? parent) parent-id (:frame-id parent))
        origin    (parent-origin objects parent-id)
        text?     (= type* :text)
        grow-type (when text? (or (->kw (:growType op) #{:auto-width :auto-height :fixed} "growType")
                                  (if width :auto-height :auto-width)))
        id        (uuid/next)
        shape     (-> (cts/setup-shape
                       (cond-> {:id id
                                :type type*
                                :name (or (some-> (:name op) str)
                                          (when text? (some-> (:text op) str (str/prune 30)))
                                          (get default-names type*))
                                :x (+ (:x origin) (or (number! x "x") 0))
                                :y (+ (:y origin) (or (number! y "y") 0))
                                :width (or (number! width "width" :min 0.01) (if text? 1 100))
                                :height (or (number! height "height" :min 0.01) (if text? 1 100))
                                :parent-id parent-id
                                :frame-id frame-id}
                         text? (assoc :grow-type grow-type
                                      :content (text-content nil op))))
                      (merge (prop-attrs type* (dissoc op :growType)))
                      (cond-> (= type* :frame) (with-layout (layout-attrs (:layout op))))
                      (cond-> text? (dissoc :position-data)))
        changes   (pcb/add-object (:changes state) shape)
        state     (cond-> (assoc state :changes changes)
                    (some? ref) (update :refs assoc (str ref) id)
                    true (update :created conj id)
                    text? (update :texts conj id)
                    (ctl/any-layout? shape) (update :reflow conj id))]
    (touch-layout-parent state (objects-of state) id)))

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
        text?   (= type :text)
        layout  (when (= type :frame) (layout-attrs (:layout op)))
        attrs   (prop-attrs type op)
        update-fn (fn [shape]
                    (cond-> (merge shape attrs)
                      (some? layout) (with-layout layout)
                      (and text? (text-op? op)) (-> (assoc :content (text-content (:content shape) op))
                                                    (dissoc :position-data))))
        changes (pcb/update-shapes (:changes state) [id] update-fn)
        modifs  (geometry-modifiers objects shape op)
        state   (cond-> (assoc state :changes changes)
                  (some? modifs) (update :modifs assoc id modifs)
                  (and text? (or (text-op? op) (contains? op :growType))) (update :texts conj id)
                  (or (some? layout) (and (= type :frame) (ctl/any-layout? shape))) (update :reflow conj id))]
    (touch-layout-parent state (objects-of state) id)))

(defn- op-delete
  [state op]
  (let [objects (objects-of state)
        id      (or (resolve-id state (:id op) "id") (error! "delete needs an id" {:field "id"}))
        _       (when-not (contains? objects id) (error! (str "Shape " id " not found on this page") {:field "id"}))
        state   (touch-layout-parent state objects id)
        ids     (cfh/get-children-ids-with-self objects id)
        changes (pcb/remove-objects (:changes state) ids)]
    (-> state
        (assoc :changes changes)
        (update :deleted into ids))))

(defn build
  "Applies `ops` (seq of maps with keywordized keys) to the page objects.
  Returns the state map; throws ex-info with a readable message on bad input."
  [page-id objects ops]
  (when-not (sequential? ops)
    (error! "ops must be an array of operations" {:field "ops"}))
  (let [changes (-> (pcb/empty-changes :mcp page-id)
                    (pcb/with-objects objects))
        init    {:changes changes :refs {} :created [] :deleted #{}
                 :texts #{} :reflow #{} :modifs {}}]
    (reduce (fn [state [idx op]]
              (try
                (case (some-> (:op op) str/lower)
                  "create" (op-create state op)
                  "update" (op-update state op)
                  "delete" (op-delete state op)
                  (error! (str "Unknown op " (pr-str (:op op)) ". Use create, update or delete") {:field "op"}))
                (catch :default cause
                  (throw (ex-info (str "ops[" idx "]: " (ex-message cause))
                                  (assoc (ex-data cause) :index idx)
                                  cause)))))
            init
            (map-indexed vector ops))))

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
            changes (pcb/update-shapes (:changes state) ids
                                       (fn [shape]
                                         (gsh/transform-shape shape (get-in result [(:id shape) :modifiers])))
                                       {:attrs transform-attrs})]
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

;; --- summaries for the caller / the AI

(defn- round [v] (when (number? v) (/ (js/Math.round (* v 100)) 100)))

(defn- fill-color [shape] (some-> shape :fills first :fill-color))

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
      :text (when (cfh/text-shape? shape) (txt/content->text (:content shape)))
      :layout (when (ctl/flex-layout? shape)
                (d/without-nils
                 {:type "flex"
                  :direction (some-> (:layout-flex-dir shape) name)
                  :gap (:layout-gap shape)
                  :padding (:layout-padding shape)}))
      :sizing (let [h (:layout-item-h-sizing shape) v (:layout-item-v-sizing shape)]
                (when (or h v) (d/without-nils {:h (some-> h name) :v (some-> v name)})))
      :hidden (when (:hidden shape) true)})))

(defn tree
  "Nested summary of `root-id`'s subtree, at most `max-depth` levels deep."
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
                           (into [] (keep #(walk % (inc depth))) (:shapes shape))
                           (str (count (:shapes shape)) " children (increase depth to see them)")))))))]
    (walk root-id 0)))
