;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns penpot-mcp.library
  "Library assets — components, colors and typographies — of the edited file
  (its local library) and of the shared libraries linked to it, found by
  name for edit ops and described for the AI.

  Names are the ones Penpot shows in the assets panel: the asset path and
  name joined with \" / \" (\"Buttons / Primary\"). Lookups ignore case and
  the spacing around the slashes, so \"buttons/primary\" works too."
  (:require
   [app.common.data :as d]
   [app.common.files.changes :as cfc]
   [app.common.files.changes-builder :as pcb]
   [app.common.path-names :as cpn]
   [app.common.types.container :as ctn]
   [app.common.types.pages-list :as ctpl]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [penpot-mcp.fonts :as fonts]
   [penpot-mcp.props :as props]
   [penpot-mcp.util :as u]))

(defn full-name
  [{:keys [path name]}]
  (cpn/merge-path-item (cpn/join-path (cpn/split-path (str path))) (str name)))

(defn- name-key
  [s]
  (str/join "/" (map str/lower (cpn/split-path (str s)))))

(defn index
  "Assets of `files` ({:id :name :data}), the edited file first. Each asset
  carries `::file-id` / `::file-name` of the file that owns it."
  [files]
  (let [collect (fn [kind]
                  (vec (for [{:keys [id name data]} files
                             asset (vals (get data kind))
                             :when (not (:deleted asset))]
                         (assoc asset ::file-id id ::file-name name))))]
    {:components (collect :components)
     :colors (collect :colors)
     :typographies (collect :typographies)}))

(def ^:private kind-names
  {:components "component" :colors "library color" :typographies "typography"})

(defn- variant-desc
  [component]
  (some->> (:variant-properties component)
           (map (fn [{:keys [name value]}] (str name "=" value)))
           seq
           (str/join ", ")))

(defn- not-found!
  [index kind query field]
  (let [parts   (->> (cpn/split-path (str query)) (map u/normalize) (filter #(>= (count %) 3)))
        assets  (get index kind)
        similar (->> assets
                     (filter (fn [asset]
                               (let [n (u/normalize (full-name asset))]
                                 (some #(str/includes? n %) parts))))
                     (map full-name)
                     (distinct)
                     (take 10))
        names   (if (seq similar) similar (->> assets (map full-name) distinct (take 10)))]
    (u/error! (str "No " (get kind-names kind) " named " (pr-str query)
                   (if (empty? assets)
                     (str " — the file and its linked libraries have no " (get kind-names kind) "s"
                          (when (= kind :components) " (link a library with link_library or import_library)"))
                     (str ". " (if (seq similar) "Similar: " "Some of the available: ")
                          (str/join ", " names)
                          (when (> (count assets) 10) " … (use get_library to list them)")))
                   (when (= kind :colors) " Colors are \"#rrggbb\" or the name of a library color."))
              {:field field :value query})))

(defn- variant-match?
  [component variant]
  (let [props (into {} (map (fn [{:keys [name value]}] [(str/lower name) (str/lower value)]))
                    (:variant-properties component))]
    (every? (fn [[k v]] (= (get props (str/lower (name k))) (str/lower (str v)))) variant)))

(defn find-asset
  "The asset of `kind` (:components, :colors, :typographies) named `query`.
  For components, `variant` ({property value}) picks one variant of a
  variant set. The edited file wins over linked libraries."
  ([index kind query field] (find-asset index kind query field nil))
  ([index kind query field variant]
   (when-not (and (string? query) (not (str/blank? query)))
     (u/error! (str "Invalid " field ": expected the name of a " (get kind-names kind)) {:field field :value query}))
   (let [k      (name-key query)
         hits   (filterv #(= k (name-key (full-name %))) (get index kind))
         picked (if (seq variant) (filterv #(variant-match? % variant) hits) hits)]
     (cond
       (empty? hits)
       (not-found! index kind query field)

       (empty? picked)
       (u/error! (str "No variant of " (pr-str query) " matches " (pr-str variant) ". Variants: "
                      (str/join "; " (keep variant-desc hits)))
                 {:field "variant" :value variant})

       (and (= kind :components) (> (count picked) 1) (some :variant-id picked)
            (= 1 (count (distinct (map ::file-id picked)))))
       (u/error! (str (pr-str query) " is a variant set: pick one with \"variant\", e.g. "
                      (let [{:keys [variant-properties]} (first picked)]
                        (str "{" (str/join ", " (map (fn [{:keys [name value]}] (str (pr-str name) ": " (pr-str value)))
                                                     variant-properties))
                             "}"))
                      ". Variants: " (str/join "; " (keep variant-desc picked)))
                 {:field "variant"})

       :else
       (first picked)))))

(defn library-data
  "Data of the file that owns `asset`, from `libraries` (id -> file)."
  [libraries asset]
  (get-in libraries [(::file-id asset) :data]))

(defn main-shape
  "Root shape of a component's main instance."
  [file-data component]
  (some-> (ctpl/get-page file-data (:main-instance-page component))
          (ctn/get-shape (:main-instance-id component))))

;; --- descriptions for the AI (get_library)

(defn- round [v] (when (number? v) (/ (js/Math.round (* v 100)) 100)))

(defn- describe-component
  [file-data component]
  (let [{:keys [width height]} (:selrect (main-shape file-data component))]
    (d/without-nils
     {:name (full-name component)
      :variant (variant-desc component)
      :width (round width)
      :height (round height)})))

(defn- describe-color
  [color]
  (d/without-nils
   {:name (full-name color)
    :color (:color color)
    :opacity (when (and (:opacity color) (not= 1 (:opacity color))) (:opacity color))
    :kind (cond (:gradient color) "gradient" (:image color) "image")}))

(defn- describe-typography
  [typography]
  (d/without-nils
   {:name (full-name typography)
    :fontFamily (:font-family typography)
    :fontWeight (:font-weight typography)
    :fontStyle (when (not= "normal" (:font-style typography)) (:font-style typography))
    :fontSize (:font-size typography)
    :lineHeight (:line-height typography)
    :letterSpacing (when (not= "0" (str (:letter-spacing typography))) (:letter-spacing typography))
    :textTransform (when (not= "none" (:text-transform typography)) (:text-transform typography))}))

(defn describe
  "JSON-friendly summary of the assets of `files` ({:id :name :data :local?}),
  filtered by `search` (substring of the full name) and capped at `limit`
  per kind and file."
  [files search limit]
  (let [q      (some-> search u/normalize not-empty)
        match? #(or (nil? q) (str/includes? (u/normalize (full-name %)) q))
        pick   (fn [assets describe-fn]
                 (let [matching (->> (vals assets) (remove :deleted) (filter match?)
                                     (sort-by (comp str/lower full-name)))]
                   [(mapv describe-fn (take limit matching)) (count matching)]))]
    (vec
     (for [{:keys [id name data local?]} files
           :let [[components n-components] (pick (:components data) (partial describe-component data))
                 [colors n-colors] (pick (:colors data) describe-color)
                 [typographies n-typographies] (pick (:typographies data) describe-typography)]]
       (d/without-nils
        {:fileId (str id)
         :name name
         :local (when local? true)
         :components components
         :colors colors
         :typographies typographies
         :totals {:components n-components :colors n-colors :typographies n-typographies}})))))

;; --- editing the file's own library (edit_library)

(def ^:private typography-keys
  [:fontFamily :fontWeight :fontStyle :fontSize :lineHeight :letterSpacing :textTransform])

(def ^:private library-op-props
  {["add" "color"]         [:op :type :name :color :opacity]
   ["update" "color"]      [:op :type :id :name :color :opacity]
   ["delete" "color"]      [:op :type :id]
   ["add" "typography"]    (into [:op :type :name] typography-keys)
   ["update" "typography"] (into [:op :type :id :name] typography-keys)
   ["delete" "typography"] [:op :type :id]})

(defn- split-name
  [v field]
  (when-not (and (string? v) (not (str/blank? v)))
    (u/error! (str "Invalid " field ": expected a name like \"Primary / 500\"") {:field field :value v}))
  (let [[path name] (cpn/split-group-name v)]
    (when (str/blank? name)
      (u/error! (str "Invalid " field ": " (pr-str v)) {:field field :value v}))
    [path name]))

(defn- hex!
  [v field]
  (cond
    (and (string? v) (re-matches #"^#[0-9a-fA-F]{6}$" v)) (str/lower v)
    (and (string? v) (re-matches #"^#[0-9a-fA-F]{3}$" v)) (let [[_ r g b] v] (str/lower (str "#" r r g g b b)))
    :else (u/error! (str "Invalid " field ": " (pr-str v) " (expected #rrggbb)") {:field field :value v})))

(defn- num!
  [v field & {:keys [min max]}]
  (when (some? v)
    (if (and (number? v) (js/isFinite v) (or (nil? min) (>= v min)) (or (nil? max) (<= v max)))
      v
      (u/error! (str "Invalid " field ": " (pr-str v)) {:field field :value v}))))

(defn- own-asset
  "Asset of the file's own library by id or full name."
  [data kind v]
  (let [assets (vals (get data kind))
        id     (when (string? v) (uuid/parse* v))]
    (or (when id (d/seek #(= id (:id %)) assets))
        (when (string? v) (d/seek #(= (name-key v) (name-key (full-name %))) assets))
        (u/error! (str "No " (get kind-names kind) " " (pr-str v) " in this file's library"
                       (when (seq assets)
                         (str ". It has: " (str/join ", " (take 15 (map full-name assets)))
                              (when (> (count assets) 15) " …"))))
                  {:field "id" :value v}))))

(defn- check-unique!
  [data kind full exclude-id]
  (when-let [other (d/seek #(and (= (name-key full) (name-key (full-name %))) (not= exclude-id (:id %)))
                           (vals (get data kind)))]
    (u/error! (str "The library already has a " (get kind-names kind) " named " (pr-str (full-name other))
                   ": use update, or another name")
              {:field "name" :value full})))

(defn- typography
  [team base op]
  (let [base   (or base {:font-id "sourcesanspro" :font-family "sourcesanspro" :font-variant-id "regular"
                         :font-weight "400" :font-style "normal" :font-size "14" :line-height "1.2"
                         :letter-spacing "0" :text-transform "none"})
        tt     (:textTransform op)
        _      (when (and (some? tt) (not (#{"none" "uppercase" "lowercase" "capitalize"} tt)))
                 (u/error! (str "Invalid textTransform: " (pr-str tt) ". Allowed: none, uppercase, lowercase, capitalize")
                           {:field "textTransform" :value tt}))]
    (merge base
           (fonts/text-font-attrs team op base)
           (d/without-nils
            {:font-size (some-> (num! (:fontSize op) "fontSize" :min 1) str)
             :line-height (some-> (num! (:lineHeight op) "lineHeight" :min 0) str)
             :letter-spacing (some-> (num! (:letterSpacing op) "letterSpacing") str)
             :text-transform tt}))))

(defn- library-op
  [{:keys [data team] :as state} op]
  (when-not (map? op)
    (u/error! "Each op must be an object like {\"op\": \"add\", \"type\": \"color\", ...}" {:field "op"}))
  (let [kind    (some-> (:op op) str str/lower)
        type    (some-> (:type op) str str/lower)
        allowed (or (get library-op-props [kind type])
                    (u/error! (str "Unknown op " (pr-str (:op op)) " / type " (pr-str (:type op))
                                   ". Use op add, update or delete with type color or typography")
                              {:field "op"}))
        _       (props/check-keys! op allowed (str "for " kind " " type))
        assets  (if (= type "color") :colors :typographies)
        current (when (#{"update" "delete"} kind) (own-asset data assets (:id op)))
        [path name] (if (or (contains? op :name) (= kind "add"))
                      (split-name (:name op) "name")
                      [(:path current) (:name current)])
        full    (cpn/merge-path-item path name)
        _       (when (#{"add" "update"} kind) (check-unique! data assets full (:id current)))
        asset   (case [kind type]
                  ["add" "color"]
                  {:id (uuid/next) :name name :path path
                   :color (hex! (:color op) "color")
                   :opacity (or (num! (:opacity op) "opacity" :min 0 :max 1) 1)}

                  ["update" "color"]
                  (cond-> (assoc current :name name :path path)
                    (contains? op :color) (assoc :color (hex! (:color op) "color"))
                    (contains? op :opacity) (assoc :opacity (num! (:opacity op) "opacity" :min 0 :max 1)))

                  ["add" "typography"]
                  (assoc (typography team nil op) :id (uuid/next) :name name :path path)

                  ["update" "typography"]
                  (assoc (typography team current op) :name name :path path)

                  nil)
        changes (-> (pcb/empty-changes :mcp)
                    (pcb/with-library-data data))
        changes (case [kind type]
                  ["add" "color"] (pcb/add-color changes (d/without-nils (dissoc asset ::file-id ::file-name)))
                  ["update" "color"] (pcb/update-color changes (d/without-nils (dissoc asset ::file-id ::file-name)))
                  ["delete" "color"] (pcb/delete-color changes (:id current))
                  ["add" "typography"] (pcb/add-typography changes (d/without-nils asset))
                  ["update" "typography"] (pcb/update-typography changes (d/without-nils asset))
                  ["delete" "typography"] (pcb/delete-typography changes (:id current)))
        redo    (:redo-changes changes)]
    (-> state
        (update :changes into redo)
        (assoc :data (cfc/process-changes data redo false))
        (cond-> (= kind "update") (update :updated conj [assets (:id current)]))
        (update :touched conj (or (:id asset) (:id current))))))

(defn build-ops
  "Applies library `ops` to the file `data`. Returns {:changes [redo
  changes] :data new-data :updated #{[kind id]}} — updated assets need a
  sync of the shapes that use them."
  [data team ops]
  (when-not (and (sequential? ops) (seq ops))
    (u/error! "ops must be a non-empty array of operations" {:field "ops"}))
  (reduce (fn [state [idx op]]
            (try
              (library-op state op)
              (catch :default cause
                (throw (ex-info (str "ops[" idx "]: " (ex-message cause))
                                (assoc (ex-data cause) :index idx)
                                cause)))))
          {:data data :team team :changes [] :updated #{} :touched []}
          (map-indexed vector ops)))
