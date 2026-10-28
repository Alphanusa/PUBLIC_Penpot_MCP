;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Parts of this namespace mirror Penpot's frontend (MPL-2.0, Copyright (c)
;; KALEIDOS SUBSIDIARY SL): grouping and ungrouping
;; (app.main.data.workspace.groups) and boolean shapes
;; (app.main.data.workspace.bool). `xml->clj` follows funcool/tubax
;; (Apache-2.0), which Penpot uses to read SVG files.

(ns penpot-mcp.vector
  "Vectors for edit ops: SVG markup imported as editable shapes (Penpot's
  SVG import), paths, groups and boolean shapes."
  (:require
   ["sax$default" :as sax]
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-builder :as sb]
   [app.common.geom.shapes :as gsh]
   [app.common.svg :as csvg]
   [app.common.types.component :as ctk]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [penpot-mcp.util :as u]))

;; --- SVG markup

(defn xml->clj
  "Parses XML text into {:tag :attrs :content} (strict, text trimmed, tags
  and attributes keywordized), the shape Penpot's SVG import reads."
  [text]
  (let [parser (.parser sax true #js {"trim" true "normalize" false "lowercase" true
                                      "xmlns" false "position" true "strictEntities" false})
        doc    (atom {:stack [] :current nil})
        result (atom nil)
        text!  (fn [t]
                 (when (seq t)
                   (swap! doc update-in [:current :content] (fnil conj []) t)))]
    (set! (.-onopentag parser)
          (fn [^js node]
            (swap! doc (fn [{:keys [stack current]}]
                         {:stack (cond-> stack (some? current) (conj current))
                          :current {:tag (keyword (.-name node))
                                    :attrs (not-empty (js->clj (.-attributes node) :keywordize-keys true))
                                    :content nil}}))))
    (set! (.-onclosetag parser)
          (fn [_]
            (swap! doc (fn [{:keys [stack current] :as doc}]
                         (if (empty? stack)
                           doc
                           {:stack (pop stack)
                            :current (update (peek stack) :content (fnil conj []) current)})))))
    (set! (.-ontext parser) text!)
    (set! (.-oncdata parser) text!)
    (set! (.-onend parser) #(when-not @result (reset! result {:ok (:current @doc)})))
    (set! (.-onerror parser) #(reset! result {:error (ex-message %)}))
    (try
      (.write parser text)
      (.close parser)
      (catch :default cause
        (when-not (:error @result) (reset! result {:error (ex-message cause)}))))
    (or (when-not (:error @result) (:ok @result))
        (u/error! (str "svg is not valid SVG markup: " (some-> (:error @result) str/lines first))
                  {:field "svg"}))))

(defn svg-shapes
  "Shapes for SVG markup `text`, as when pasting an SVG in the editor
  (`create-svg-shapes`): a group named `name` holding the SVG elements as
  rectangles, ellipses, paths and groups with their fills and strokes, the
  top-left of its viewBox at `x`/`y` (page coordinates). Embedded bitmaps
  are left out. Returns [root children]."
  [text name objects frame-id parent-id x y]
  (when-not (and (string? text) (str/includes? text "<svg"))
    (u/error! "svg must be SVG markup like \"<svg viewBox='0 0 24 24'>…</svg>\"" {:field "svg"}))
  (let [svg (xml->clj (str/trim text))
        _   (when-not (= :svg (:tag svg))
              (u/error! "svg must have an <svg> root element" {:field "svg"}))
        {:keys [viewBox width height]} (:attrs svg)
        [vx vy] (csvg/parse-numbers (or viewBox (str "0 0 " (or width 100) " " (or height 100))))]
    (try
      (sb/create-svg-shapes (uuid/next) (assoc svg :name name)
                            {:x (- x (or vx 0)) :y (- y (or vy 0))}
                            objects frame-id parent-id #{} false)
      (catch :default cause
        (u/error! (str "Could not import the svg: " (ex-message cause)) {:field "svg"})))))

(defn recolor
  "`shape` with its solid fills and strokes painted with `paint`
  ({:color :opacity :ref-id :ref-file}), like tinting an icon."
  [shape {:keys [color opacity ref-id ref-file]}]
  (let [ref (fn [m kid kfile]
              (if ref-id (assoc m kid ref-id kfile ref-file) (dissoc m kid kfile)))]
    (-> shape
        (d/update-when :fills
                       (fn [fills]
                         (mapv (fn [f]
                                 (if (:fill-color f)
                                   (-> (assoc f :fill-color color)
                                       (cond-> opacity (assoc :fill-opacity opacity))
                                       (ref :fill-color-ref-id :fill-color-ref-file))
                                   f))
                               fills)))
        (d/update-when :strokes
                       (fn [strokes]
                         (mapv (fn [s]
                                 (if (:stroke-color s)
                                   (-> (assoc s :stroke-color color)
                                       (cond-> opacity (assoc :stroke-opacity opacity))
                                       (ref :stroke-color-ref-id :stroke-color-ref-file))
                                   s))
                               strokes))))))

;; --- paths

(defn path-content
  "Path content from SVG path data (`d`)."
  [d]
  (when-not (and (string? d) (not (str/blank? d)))
    (u/error! "path needs \"d\": SVG path data like \"M0 0 L24 12 L0 24 Z\"" {:field "d"}))
  (let [content (try (path/from-string d)
                     (catch :default cause
                       (u/error! (str "Invalid path data: " (ex-message cause)) {:field "d"})))]
    (when (empty? (path/get-points content))
      (u/error! (str "Invalid path data " (pr-str (str/prune d 40)) ": no points") {:field "d"}))
    content))

;; --- groups

(defn- empty-groups-after
  "Groups left empty when `shapes` move to a new group (they each had only
  shapes that move), from `get-empty-groups-after-group-creation`."
  [objects parent-id shapes]
  (let [ids     (cfh/clean-loops objects (into #{} (map :id) shapes))
        parents (into #{} (map #(cfh/get-parent-id objects %)) ids)]
    (loop [current-id (first parents)
           to-check   (rest parents)
           removed?   ids
           result     #{}]
      (if-not current-id
        result
        (let [group (get objects current-id)]
          (if (and (not= :frame (:type group))
                   (not= current-id parent-id)
                   (empty? (remove removed? (:shapes group))))
            (let [to-check (concat to-check [(cfh/get-parent-id objects current-id)])]
              (recur (first to-check) (rest to-check) (conj removed? current-id) (conj result current-id)))
            (recur (first to-check) (rest to-check) removed? result)))))))

(defn group
  "Changes that put `shapes` (top layer first) in a new group `id` at the
  place of the top one, like Ctrl+G (`prepare-create-group`)."
  [changes objects id shapes name]
  (let [frame-id  (:frame-id (first shapes))
        parent-id (:parent-id (first shapes))
        selrect   (gsh/shapes->rect shapes)
        group-idx (inc (cfh/get-position-on-parent objects (:id (last shapes))))
        group     (cts/setup-shape {:id id :type :group :name name
                                    :shapes (mapv :id shapes)
                                    :selrect selrect
                                    :x (:x selrect) :y (:y selrect)
                                    :width (:width selrect) :height (:height selrect)
                                    :parent-id parent-id :frame-id frame-id
                                    :index group-idx})
        detach    (filter ctk/in-component-copy-not-head? shapes)
        to-delete (empty-groups-after objects parent-id shapes)
        cell      (when (ctl/grid-layout? objects parent-id)
                    (ctl/get-cell-by-shape-id (get objects parent-id) (-> shapes last :id)))
        grids     (into [] (comp (map :parent-id) (filter (partial ctl/grid-layout? objects))) shapes)]
    (-> changes
        (pcb/add-object group {:index group-idx})
        (pcb/update-shapes (map :id shapes)
                           (fn [shape]
                             (-> shape
                                 (d/assoc-when :constraints-h :scale)
                                 (d/assoc-when :constraints-v :scale))))
        (pcb/update-shapes (map :id shapes) ctl/remove-layout-item-data)
        (pcb/change-parent id (reverse shapes))
        (pcb/update-shapes (map :id detach) ctk/detach-shape)
        (cond-> cell
          (pcb/update-shapes [parent-id]
                             (fn [parent] (assoc-in parent [:layout-grid-cells (:id cell) :shapes] [id]))))
        (pcb/update-shapes grids ctl/assign-cells {:with-objects? true})
        (pcb/remove-objects to-delete))))

(defn ungroup
  "Changes that move the children of group, boolean or board `id` to its
  parent, at its place, and remove it (Shift+G / \"Remove board\")."
  [changes objects id]
  (let [shape     (get objects id)
        children  (->> (:shapes shape)
                       (cfh/order-by-indexed-shapes objects)
                       (mapv #(get objects %)))
        parent-id (cfh/get-parent-id objects id)
        index     (inc (cfh/get-position-on-parent objects id))]
    (-> changes
        (cond-> (ctl/any-layout? shape)
          (pcb/update-shapes (:shapes shape) ctl/remove-layout-item-data))
        (pcb/change-parent parent-id children index)
        (pcb/remove-objects [id]))))

;; --- boolean shapes

(def bool-types #{:union :difference :intersection :exclude})

(defn bool
  "Changes that combine `shapes` (bottom layer first) into a boolean shape
  `id` of `type`, like the boolean buttons of the toolbar
  (`create-bool-shape`). The shape takes its style from the top shape (the
  bottom one for a difference)."
  [changes objects id type name shapes]
  (let [rev    (reverse shapes)
        head   (if (= type :difference) (first rev) (last rev))
        head   (cond-> head
                 (and (contains? head :svg-attrs) (empty? (:fills head)))
                 (assoc :fills (path/get-default-bool-fills)))
        shape  (-> {:id id :type :bool :bool-type type
                    :frame-id (:frame-id head) :parent-id (:parent-id head)
                    :name name
                    :shapes (into [] (map :id) rev)}
                   (merge (select-keys head path/bool-style-properties))
                   (cts/setup-shape)
                   (path/update-bool-shape objects))
        index  (cfh/get-position-on-parent objects (:id head))]
    (-> changes
        (pcb/add-object shape {:index (inc index)})
        (pcb/update-shapes (map :id shapes) ctl/remove-layout-item-data)
        (pcb/change-parent id shapes))))
