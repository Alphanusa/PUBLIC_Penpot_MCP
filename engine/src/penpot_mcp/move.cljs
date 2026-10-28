;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copies follow Penpot's paste (app.main.data.workspace.clipboard,
;; MPL-2.0, Copyright (c) KALEIDOS SUBSIDIARY SL): `generate-duplicate-changes`
;; with the target page and libraries, image fills pointing to media of the
;; target file.

(ns penpot-mcp.move
  "move_shapes: boards (or any shapes, with their children) to another page
  of the same file, or copies of them to a page of the same or another file
  of the team, as Penpot changes.

  - move (same file): the subtrees leave the source page and are added to
    the target page in the same update-file, keeping ids, child order, flex
    layouts, sizing, image fills and library refs. Main components keep
    working (their component points to the new page), flows starting at a
    moved board and guides of moved boards go with them, and prototype
    interactions that would cross pages are removed.
  - copy: Penpot's duplicate, as paste does, with new ids. To another file,
    image fills use media cloned into that file (or already in it), library
    color and typography refs of the source file's own library are remapped
    to the assets with the same names in the target file (else the raw value
    stays and is reported), refs to a shared library are kept when the target
    links it, main components become components of the target file, and
    copies of them are relinked; copies of components the target can't reach
    are detached (they look the same, unlinked)."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.types.component :as ctk]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [penpot-mcp.edit :as edit]
   [penpot-mcp.library :as library]
   [penpot-mcp.util :as u]))

;; --- request

(defn- pages
  [data]
  (keep #(get-in data [:pages-index %]) (:pages data)))

(defn- page-desc
  [page]
  (str (pr-str (:name page)) " (" (:id page) ")"))

(defn resolve-page
  "Page of file `data` by id or name (case-insensitive)."
  [data v field what]
  (let [ps (pages data)
        id (when (string? v) (uuid/parse* v))]
    (or (when id (d/seek #(= id (:id %)) ps))
        (when (string? v)
          (let [k (str/lower (str/trim v))]
            (d/seek #(= k (str/lower (str/trim (str (:name %))))) ps)))
        (u/error! (str "No page " (pr-str v) " in " what ". Pages: " (str/join ", " (map page-desc ps)))
                  {:field field :value v}))))

(defn- number-or-nil
  [v field]
  (when (some? v)
    (if (and (number? v) (js/isFinite v))
      v
      (u/error! (str "Invalid " field ": " (pr-str v) " (expected a number)") {:field field :value v}))))

(defn- parent-origin
  [objects parent-id]
  (if (or (nil? parent-id) (= parent-id uuid/zero))
    (gpt/point 0 0)
    (let [{:keys [x y]} (:selrect (get objects parent-id))]
      (gpt/point x y))))

(defn plan
  "Checks a move_shapes request against the source and target files
  ({:id :data}). Returns {:mode :src-page :dst-page :ids (top shapes, in the
  given order, without shapes whose ancestor is also given) :same-file?
  :same-page? :delta}."
  [{:keys [source target page-id to-page ids mode x y]}]
  (let [mode       (or mode "move")
        _          (when-not (#{"move" "copy"} mode)
                     (u/error! (str "Invalid mode " (pr-str mode) ". Use move or copy") {:field "mode" :value mode}))
        same-file? (= (:id source) (:id target))
        _          (when-not (and (sequential? ids) (seq ids))
                     (u/error! "ids must be a non-empty array of shape ids" {:field "ids"}))
        ids        (into [] (comp (map (fn [v] (or (when (string? v) (uuid/parse* v))
                                                    (u/error! (str "Invalid shape id " (pr-str v)) {:field "ids" :value v}))))
                                  (distinct))
                         ids)
        data       (:data source)
        src-page   (if (some? page-id)
                     (resolve-page data page-id "pageId" "the file")
                     (or (d/seek #(contains? (:objects %) (first ids)) (pages data))
                         (u/error! (str "Shape " (first ids) " not found on any page of the file") {:field "ids"})))
        objects    (:objects src-page)
        _          (doseq [id ids]
                     (when (or (= id uuid/zero) (not (contains? objects id)))
                       (u/error! (str "Shape " id " not found on page " (page-desc src-page)
                                      ": all ids must be shapes of the same page")
                                 {:field "ids" :value (str id)})))
        given      (set ids)
        ids        (filterv (fn [id] (not (some given (cfh/get-parent-ids objects id)))) ids)
        dst-page   (resolve-page (:data target) to-page "toPageId" (if same-file? "the file" "the target file"))
        same-page? (and same-file? (= (:id src-page) (:id dst-page)))
        _          (when (and (= mode "move") (not same-file?))
                     (u/error! (str "mode move works within one file. To put the shapes in another file use mode "
                                    "copy (the originals stay where they are)")
                               {:field "mode"}))
        _          (when (and (= mode "move") same-page?)
                     (u/error! (str "The shapes are already on page " (page-desc dst-page) ". To change their parent "
                                    "or order on the page use edit_page with the move op")
                               {:field "toPageId"}))
        _          (when (= mode "move")
                     (doseq [id ids
                             :let [shape (get objects id)]]
                       (when (ctk/in-component-copy? (get objects (:parent-id shape)))
                         (u/error! (str "Shape " id " (" (pr-str (:name shape)) ") is a layer of a component copy: "
                                        "move the whole copy")
                                   {:field "ids" :value (str id)}))
                       (when (some #(ctk/main-instance? (get objects %)) (cfh/get-parent-ids objects id))
                         (u/error! (str "Shape " id " (" (pr-str (:name shape)) ") is a layer of a main component: "
                                        "move the component's board")
                                   {:field "ids" :value (str id)}))))
        x          (number-or-nil x "x")
        y          (number-or-nil y "y")
        first-shape (get objects (first ids))
        origin     (if same-page? (parent-origin objects (:parent-id first-shape)) (gpt/point 0 0))
        {fx :x fy :y} (:selrect first-shape)]
    {:mode mode
     :src-page src-page
     :dst-page dst-page
     :ids ids
     :same-file? same-file?
     :same-page? same-page?
     :delta (gpt/point (if (some? x) (- (+ (:x origin) (or x 0)) fx) 0)
                       (if (some? y) (- (+ (:y origin) (or y 0)) fy) 0))}))

(defn- subtree
  [objects ids]
  (into [] (mapcat #(cfh/get-children-ids-with-self objects %)) ids))

(defn media-ids
  "Media ids used by the image fills of the subtrees of `ids`."
  [objects ids]
  (into #{}
        (comp (keep (d/getf objects)) (mapcat cfh/collect-shape-media-refs))
        (subtree objects ids)))

(defn used-media
  "Media ids already used by the shapes of file `data` (reused as they are)."
  [data]
  (into #{}
        (comp (mapcat (comp vals :objects)) (mapcat cfh/collect-shape-media-refs))
        (pages data)))

(defn- zero-delta? [delta] (and (zero? (:x delta)) (zero? (:y delta))))

;; --- move within a file

(defn- strip-interactions
  "`shape` without the interactions whose destination satisfies `gone?`;
  returns [shape removed-count]."
  [shape gone?]
  (let [ias (vec (:interactions shape))]
    (if (empty? ias)
      [shape 0]
      (let [kept (filterv #(not (and (ctsi/has-destination %) (gone? (:destination %)))) ias)
            n    (- (count ias) (count kept))]
        [(if (pos? n) (assoc shape :interactions kept) shape) n]))))

(defn- moved-shapes
  "The shapes of `top-id`'s subtree for the target page: moved by `delta`,
  the top one at the page root, frame ids from their new ancestors."
  [objects top-id delta sub?]
  (letfn [(walk [acc id frame-id top?]
            (let [orig        (get objects id)
                  [shape n]   (strip-interactions orig (complement sub?))
                  shape       (cond-> (assoc shape :frame-id frame-id)
                                (not (zero-delta? delta)) (gsh/move delta)
                                ;; at the page root: no layout item data, no constraints
                                top? (-> (assoc :parent-id uuid/zero)
                                         (cond-> (ctl/any-layout? objects (:parent-id orig))
                                           (ctl/remove-layout-item-data))
                                         (dissoc :constraints-h :constraints-v)))
                  child-frame (if (cfh/frame-shape? shape) id frame-id)]
              (reduce #(walk %1 %2 child-frame false)
                      (-> acc (update :shapes conj shape) (update :interactions + n))
                      (:shapes orig))))]
    (walk {:shapes [] :interactions 0} top-id uuid/zero true)))

(defn- empty-groups
  "Groups (and booleans) left without children once `removed` are gone,
  bottom up."
  [objects removed tops]
  (loop [removed removed
         found   []
         check   (distinct (keep #(cfh/get-parent-id objects %) tops))]
    (let [hits (filterv (fn [id]
                          (let [shape (get objects id)]
                            (and (not (contains? removed id))
                                 (or (cfh/group-shape? shape) (cfh/bool-shape? shape))
                                 (every? removed (:shapes shape)))))
                        check)]
      (if (empty? hits)
        found
        (recur (into removed hits)
               (into found hits)
               (distinct (keep #(cfh/get-parent-id objects %) hits)))))))

(defn build-move
  "Moves the subtrees of `ids` from `src-page` to `dst-page` of file
  `file-id` (with `data`), keeping ids. `src-state` is an edit state of the
  source page (`penpot-mcp.edit/start`). Returns {:changes [redo changes
  for the target page, components, flows and guides] :src-state
  (removal added; needs the layout pass when its :reflow is not empty)
  :objects (target page objects after the move) :report}."
  [file-id data {:keys [src-page dst-page ids delta]} src-state]
  (let [objects  (:objects src-page)
        sub      (subtree objects ids)
        sub?     (set sub)
        src-id   (:id src-page)
        dst-id   (:id dst-page)
        prepared (map #(moved-shapes objects % delta sub?) ids)
        dst      (reduce (fn [changes shape] (pcb/add-object changes shape))
                         (-> (pcb/empty-changes :mcp dst-id) (pcb/with-objects (:objects dst-page)))
                         (mapcat :shapes prepared))
        comps    (->> sub
                      (keep (d/getf objects))
                      (filter #(and (ctk/main-instance? %) (= file-id (:component-file %))))
                      (map :component-id)
                      (distinct))
        comp-chs (:redo-changes
                  (reduce (fn [changes cid]
                            (pcb/update-component changes cid #(assoc % :main-instance-page dst-id)))
                          (-> (pcb/empty-changes :mcp) (pcb/with-library-data data))
                          comps))
        flows    (filterv #(sub? (:starting-frame %)) (vals (:flows src-page)))
        guides   (filterv #(sub? (:frame-id %)) (vals (:guides src-page)))
        extra    (concat
                  (mapcat (fn [{:keys [id] :as flow}]
                            [{:type :set-flow :page-id src-id :id id :params nil}
                             {:type :set-flow :page-id dst-id :id id :params flow}])
                          flows)
                  (mapcat (fn [{:keys [id axis] :as guide}]
                            [{:type :set-guide :page-id src-id :id id :params nil}
                             {:type :set-guide :page-id dst-id :id id
                              :params (update guide :position + (if (= :x axis) (:x delta) (:y delta)))}])
                          guides))
        ;; shapes left on the source page that point to moved boards
        pointing (into []
                       (filter (fn [shape]
                                 (and (not (sub? (:id shape)))
                                      (some #(and (ctsi/has-destination %) (sub? (:destination %)))
                                            (:interactions shape)))))
                       (vals objects))
        dangling (reduce + 0 (map (fn [shape] (second (strip-interactions shape sub?))) pointing))
        old-parents (into [] (comp (keep #(cfh/get-parent-id objects %)) (remove sub?) (distinct)) ids)
        groups   (empty-groups objects sub? ids)
        src-chs  (cond-> (:changes src-state)
                   (seq pointing) (pcb/update-shapes (mapv :id pointing) #(first (strip-interactions % sub?)))
                   true (pcb/remove-objects sub {:ignore-touched true})
                   (seq groups) (pcb/remove-objects groups)
                   true (pcb/resize-parents (remove (set groups) old-parents)))
        src-state (edit/touch (assoc src-state :changes src-chs) (remove (set groups) old-parents))]
    {:changes (-> (vec comp-chs) (into (:redo-changes dst)) (into extra))
     :src-state src-state
     :objects (pcb/get-objects dst)
     :report (d/without-nils
              {:components (when (seq comps) (count comps))
               :flows (when (seq flows) (mapv :name flows))
               :guides (when (seq guides) (count guides))
               :interactionsRemoved (let [n (+ dangling (reduce + 0 (map :interactions prepared)))]
                                      (when (pos? n) n))})}))

;; --- copy

(defn- translate-media
  "`shape` with its image fills pointing to the media in `media` (old id ->
  new id)."
  [shape media]
  (if (empty? media)
    shape
    (let [tr     (fn [img] (if-let [id (get media (:id img))] (assoc img :id id) img))
          fill   (fn [f] (d/update-when f :fill-image tr))
          stroke (fn [s] (d/update-when s :stroke-image tr))]
      (cond-> shape
        (seq (:fills shape)) (update :fills #(mapv fill %))
        (seq (:strokes shape)) (update :strokes #(mapv stroke %))
        (map? (:metadata shape)) (update :metadata tr)
        (map? (:fill-image shape)) (update :fill-image tr)
        (cfh/text-shape? shape)
        (update :content (fn [content]
                           (second (u/walk-content (fn [acc node]
                                                     [acc (d/update-when node :fills #(mapv fill %))])
                                                   nil content))))))))

(def ^:private kind-label {:colors "color" :typographies "typography"})

(defn- remap-ref
  "What becomes of a ref to library asset `id` of file `file` in the
  target: {:action :keep}, {:action :remap :name :target asset} or
  {:action :unlink :name :reason}."
  [{:keys [libs src-id dst-data src-libs]} kind id file]
  (let [asset (get-in src-libs [file :data kind id])
        name  (some-> asset library/full-name)]
    (cond
      (contains? libs file)
      {:action :keep}

      (= file src-id)
      (let [target (when name (library/asset-by-name dst-data kind name))]
        (cond
          (or (nil? asset) (:deleted asset))
          {:action :unlink :name "(deleted asset)" :reason (str "the " (kind-label kind) " no longer exists")}

          (nil? target)
          {:action :unlink :name name :reason (str "the target file has no " (kind-label kind) " with that name")}

          (and (= kind :colors) (not (string? (:color target))))
          {:action :unlink :name name :reason "the target's color with that name is not a solid color"}

          :else
          {:action :remap :name name :target target}))

      :else
      {:action :unlink
       :name (or name "(unknown asset)")
       :reason (str "its library" (when-let [n (get-in src-libs [file :name])] (str " " (pr-str n)))
                    " is not linked to the target file")})))

(defn- note
  [acc {:keys [action name reason]} kind shape-id]
  (if (= :keep action)
    acc
    (update-in acc [[action kind name reason]] (fnil conj #{}) shape-id)))

(def ^:private typography-attrs
  [:font-id :font-family :font-variant-id :font-size :font-weight :font-style :line-height :letter-spacing
   :text-transform])

(defn- remap-paint
  [ctx acc shape-id paint [kc ko kid kfile]]
  (let [id (get paint kid) file (get paint kfile)]
    (if (or (nil? id) (nil? file))
      [acc paint]
      (let [{:keys [action target] :as r} (remap-ref ctx :colors id file)
            acc (note acc r :color shape-id)]
        (case action
          :keep   [acc paint]
          :remap  [acc (assoc paint kid (:id target) kfile (:dst-id ctx) kc (:color target) ko (or (:opacity target) 1))]
          :unlink [acc (dissoc paint kid kfile)])))))

(def ^:private fill-keys [:fill-color :fill-opacity :fill-color-ref-id :fill-color-ref-file])
(def ^:private stroke-keys [:stroke-color :stroke-opacity :stroke-color-ref-id :stroke-color-ref-file])

(defn- remap-node
  [ctx acc shape-id node]
  (let [[acc node] (if (seq (:fills node))
                     (let [[acc fills] (u/map-acc #(remap-paint ctx %1 shape-id %2 fill-keys) acc (:fills node))]
                       [acc (assoc node :fills fills)])
                     [acc node])
        id   (:typography-ref-id node)
        file (:typography-ref-file node)]
    (if (or (nil? id) (nil? file))
      [acc node]
      (let [{:keys [action target] :as r} (remap-ref ctx :typographies id file)
            acc (note acc r :typography shape-id)]
        (case action
          :keep   [acc node]
          :remap  [acc (merge node (select-keys target typography-attrs)
                              {:typography-ref-id (:id target) :typography-ref-file (:dst-id ctx)})]
          :unlink [acc (dissoc node :typography-ref-id :typography-ref-file)])))))

(defn- remap-refs
  [ctx acc shape]
  (let [id (:id shape)
        [acc shape] (if (seq (:fills shape))
                      (let [[acc v] (u/map-acc #(remap-paint ctx %1 id %2 fill-keys) acc (:fills shape))]
                        [acc (assoc shape :fills v)])
                      [acc shape])
        [acc shape] (if (seq (:strokes shape))
                      (let [[acc v] (u/map-acc #(remap-paint ctx %1 id %2 stroke-keys) acc (:strokes shape))]
                        [acc (assoc shape :strokes v)])
                      [acc shape])]
    (if (and (cfh/text-shape? shape) (some? (:content shape)))
      (let [[acc v] (u/walk-content #(remap-node ctx %1 id %2) acc (:content shape))]
        [acc (assoc shape :content v)])
      [acc shape])))

(defn- refs-report
  [acc]
  (let [items (for [[[action kind name reason] shapes] acc]
                (d/without-nils {:action action :kind (clojure.core/name kind) :name name :reason reason
                                 :shapes (count shapes)}))
        pick  (fn [action] (->> items
                                (filter #(= action (:action %)))
                                (map #(dissoc % :action))
                                (sort-by (juxt :kind :name))
                                (vec)))]
    (d/without-nils
     {:remapped (not-empty (mapv #(dissoc % :reason) (pick :remap)))
      :unlinked (not-empty (pick :unlink))})))

(defn- fix-components
  "After a copy to a file that can't reach the source file's components:
  copies of components whose main was copied too (and became a component of
  the target) are relinked to it; other copies of unreachable components are
  detached. Returns [changes report]."
  [changes {:keys [libraries src-id dst-id src-data objects ids-map]}]
  (let [redo      (:redo-changes changes)
        inv       (into {} (map (fn [[o n]] [n o])) ids-map)
        new-comps (filterv #(= :add-component (:type %)) redo)
        comp-map  (into {}
                        (keep (fn [c]
                                (when-let [old-cid (some->> (get inv (:main-instance-id c)) (get objects) :component-id)]
                                  [old-cid (:id c)])))
                        new-comps)
        local     (pcb/get-objects changes)
        added     (into [] (comp (filter #(= :add-obj (:type %))) (map :id) (distinct)) redo)
        unknown?  (fn [file] (and (some? file) (not (contains? libraries file))))
        heads     (filterv (fn [id]
                             (let [s (get local id)]
                               (and (ctk/instance-head? s) (not (ctk/main-instance? s))
                                    (unknown? (:component-file s)))))
                           added)
        head?     (set heads)
        tops      (filterv (fn [id] (not (some head? (cfh/get-parent-ids local id)))) heads)
        relink?   (fn [ids]
                    (every? (fn [id]
                              (let [s (get local id)]
                                (and (or (nil? (:shape-ref s)) (contains? ids-map (:shape-ref s)))
                                     (or (nil? (:component-id s))
                                         (not (unknown? (:component-file s)))
                                         (and (= src-id (:component-file s))
                                              (contains? comp-map (:component-id s)))))))
                            ids))
        relink    (fn [s]
                    (cond-> s
                      (contains? ids-map (:shape-ref s))
                      (assoc :shape-ref (get ids-map (:shape-ref s)))
                      (and (= src-id (:component-file s)) (contains? comp-map (:component-id s)))
                      (assoc :component-id (get comp-map (:component-id s)) :component-file dst-id)))
        comp-name (fn [s] (or (some-> (get-in src-data [:components (:component-id s)]) library/full-name)
                              (:name s)))
        [changes relinked detached]
        (reduce (fn [[changes relinked detached] top]
                  (let [ids (cfh/get-children-ids-with-self local top)
                        s   (get local top)]
                    (if (relink? ids)
                      [(pcb/update-shapes changes ids relink {:ignore-touched true}) (inc relinked) detached]
                      [(pcb/update-shapes changes ids ctk/detach-shape {:ignore-touched true})
                       relinked
                       (update detached [(comp-name s)
                                         (if (= src-id (:component-file s))
                                           "its main component was not copied"
                                           "its library is not linked to the target file")]
                               (fnil inc 0))])))
                [changes 0 {}]
                tops)]
    [changes
     (d/without-nils
      {:created (not-empty (mapv library/full-name new-comps))
       :relinked (when (pos? relinked) relinked)
       :detached (not-empty (mapv (fn [[[n reason] c]] {:name n :reason reason :copies c}) detached))})]))

(defn build-copy
  "Copies the subtrees of `ids` to `dst-page` of the target file.

  `files`: {:source {:id :data} :target {:id :data} :libraries (target file
  + its linked libraries, id -> {:id :name :data}) :src-libs (source file +
  its linked libraries, to name assets) :media (old media id -> new, for
  another file)}. `dst-state` is an edit state of the target page
  (`penpot-mcp.edit/start`). Returns {:state (needs the layout pass)
  :ids-map {old new} :top-ids :report}."
  [{:keys [src-page dst-page ids delta same-page? same-file?]} {:keys [source target libraries src-libs media]} dst-state]
  (let [objects  (:objects src-page)
        sub      (subtree objects ids)
        ctx      {:libs (set (keys libraries))
                  :src-id (:id source)
                  :dst-id (:id target)
                  :dst-data (:data target)
                  :src-libs src-libs}
        [refs pobjects]
        (reduce (fn [[acc m] id]
                  (let [shape (get objects id)]
                    (if same-file?
                      [acc (assoc m id shape)]
                      (let [[acc shape] (remap-refs ctx acc (translate-media shape media))]
                        [acc (assoc m id shape)]))))
                [{} {}]
                sub)
        pobjects (if same-page?
                   pobjects
                   (reduce (fn [m id]
                             (update m id (fn [s]
                                            (-> s
                                                (assoc :parent-id uuid/zero :frame-id uuid/zero)
                                                (cond-> (ctl/any-layout? objects (:parent-id s))
                                                  (ctl/remove-layout-item-data))))))
                           pobjects
                           ids))
        dst-objs (:objects dst-page)
        _        (when-not same-page?
                   (when-let [clash (d/seek #(contains? dst-objs %) sub)]
                     (u/error! (str "Shape " clash " already exists on the target page: copy to another page") {:field "ids"})))
        all-objs (merge dst-objs pobjects)
        page     (assoc dst-page :objects dst-objs)
        changes  (cond-> (cll/generate-duplicate-changes (:changes dst-state) all-objs page ids delta
                                                         libraries (:data target) (:id target))
                   same-page? (cll/generate-duplicate-changes-update-indices objects ids)
                   true (-> (pcb/with-objects dst-objs) (pcb/apply-changes-local)))
        redo     (:redo-changes changes)
        ids-map  (into {}
                       (keep (fn [c] (when (and (= :add-obj (:type c)) (:old-id c)) [(:old-id c) (:id c)])))
                       redo)
        [changes comps] (if (contains? libraries (:id source))
                          [changes nil]
                          (fix-components changes {:libraries libraries
                                                   :src-id (:id source)
                                                   :dst-id (:id target)
                                                   :src-data (:data source)
                                                   :objects all-objs
                                                   :ids-map ids-map}))
        mains    (when (contains? libraries (:id source))
                   (count (filter #(ctk/main-instance? (get objects %)) sub)))
        local    (pcb/get-objects changes)
        texts    (into [] (comp (filter #(= :add-obj (:type %))) (map :id)
                                (filter #(cfh/text-shape? (get local %))))
                       (:redo-changes changes))
        state    (-> dst-state
                     (assoc :changes changes)
                     (edit/touch texts))]
    {:state state
     :ids-map ids-map
     :top-ids (into [] (keep ids-map) ids)
     :report (d/without-nils
              {:assets (not-empty (refs-report refs))
               :components (not-empty (cond-> (or comps {})
                                        (and mains (pos? mains)) (assoc :mainsCopiedAsInstances mains)))})}))
