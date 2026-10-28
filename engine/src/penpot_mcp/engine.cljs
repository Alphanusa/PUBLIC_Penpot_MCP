;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Font and image provisioning follow Penpot's exporter (app.wasm.render,
;; MPL-2.0, Copyright (c) KALEIDOS SUBSIDIARY SL).

(ns penpot-mcp.engine
  "Browser-free Penpot engine for the MCP server.

  Runs Penpot's own code under Node — `common` for the data model, changes and
  layout, and the render-wasm (Skia) module for text layout and rendering —
  reusing the headless pieces of Penpot's exporter (`app.wasm`,
  `app.wasm.serialize`) unchanged. What differs from the exporter:

  - auth: the user's Penpot access token (`Authorization: Token ...`);
  - transport: base URI passed per call instead of process config;
  - output: bytes/values returned instead of written to temp files;
  - editing: see `penpot-mcp.edit`.

  The JS API (exports in `shadow-cljs.edn`) is promise based and takes and
  returns plain JS values, so the TypeScript MCP server can call it directly."
  (:require
   ["node:fs" :as fs]
   ["node:path" :as path]
   ["undici" :as http]
   [app.common.data :as d]
   [app.common.files.changes :as cfc]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.tokens :as cfo]
   [app.common.files.helpers :as cfh]
   [app.common.fonts :as cfnt]
   ;; Required for side effects: transit read handlers and deftype impls the
   ;; RPC responses are decoded into.
   [app.common.geom.matrix]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect]
   [app.common.logging :as l]
   [app.common.logic.libraries :as cll]
   [app.common.transit :as t]
   [app.common.types.fills.impl]
   [app.common.types.objects-map]
   [app.common.types.page :as ctp]
   [app.common.types.path.impl]
   [app.common.types.shape :as cts]
   [app.common.types.shape.images :as images]
   [app.common.uuid :as uuid]
   [app.wasm :as wasm]
   [app.wasm.serialize :as serialize]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [penpot-mcp.audit :as audit]
   [penpot-mcp.edit :as edit]
   [penpot-mcp.fonts :as fonts]
   [penpot-mcp.library :as library]
   [penpot-mcp.media :as media]
   [penpot-mcp.move :as move]
   [penpot-mcp.pages :as pages]
   [penpot-mcp.tokens :as tokens]
   [penpot-mcp.util :as u]
   [promesa.core :as p]))

(l/set-level! :warn)

;; --- module lifecycle

(defonce ^:private module* (atom nil))
(defonce ^:private fonts-dir* (atom nil))

(defn- ensure-module!
  []
  (or @module*
      (reset! module* (wasm/init!))))

(defn init
  "Loads the render-wasm module from `artifact-dir` (render-wasm.js/.wasm).
  `fonts-dir`, when given, holds Penpot's builtin font TTFs (Source Sans Pro),
  read locally instead of downloaded. Safe to call more than once."
  ([artifact-dir] (init artifact-dir nil))
  ([artifact-dir fonts-dir]
   (when artifact-dir
     (set! wasm/artifact-dir artifact-dir))
   (when fonts-dir
     (reset! fonts-dir* fonts-dir))
   (->> (ensure-module!)
        (p/fmap (constantly true)))))

;; --- backend access with the user's access token

(defn- join-uri
  [base path]
  (str (str/rtrim base "/") "/" path))

(defn- auth-headers
  [token]
  (cond-> #js {"Content-Type" "application/transit+json"
               "Accept"       "application/transit+json"}
    (some? token) (doto (unchecked-set "Authorization" (str "Token " token)))))

(defn- fetch-bytes
  "GETs `uri` and resolves to an ArrayBuffer, or nil on any failure (callers
  degrade: a missing font or image must not fail the whole render)."
  [uri token]
  (if (nil? uri)
    (p/resolved nil)
    (->> (p/do (http/fetch uri #js {:method "GET"
                                    :headers (cond-> #js {}
                                               (some? token)
                                               (doto (unchecked-set "Authorization" (str "Token " token))))}))
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.arrayBuffer resp)
                     (do (l/warn :hint "fetch non-200" :uri uri :status (.-status resp))
                         (p/resolved nil)))))
         (p/merr (fn [cause]
                   (l/warn :hint "fetch failed" :uri uri :cause cause)
                   (p/resolved nil))))))

(defn- rpc-error
  [command status body]
  (let [data (try (t/decode-str body) (catch :default _ nil))
        hint (or (:hint data) (:code data) (some-> body (str/prune 300)))]
    (ex-info (str "Penpot " (name command) " failed (HTTP " status "): " hint)
             {:status status :command command :code (:code data) :type (:type data)})))

(defn- handle-response
  [command ^js resp]
  (->> (.text resp)
       (p/mcat (fn [body]
                 (if (= 200 (.-status resp))
                   (p/resolved (when-not (str/blank? body) (t/decode-str body)))
                   (p/rejected (rpc-error command (.-status resp) body)))))))

(defn rpc
  "Calls a backend RPC command and resolves to the decoded response."
  [{:keys [base-uri token]} command params]
  (when (str/blank? base-uri)
    (throw (ex-info "Penpot base URI is not configured" {})))
  (let [uri (join-uri base-uri (str "api/rpc/command/" (name command)))]
    (->> (p/do (http/fetch uri #js {:method "POST"
                                   :headers (auth-headers token)
                                   :body (t/encode-str params)}))
         (p/mcat (partial handle-response command)))))

(defn- rpc-multipart
  "Calls a multipart RPC command (uploads), like the frontend does: plain
  form fields plus one file part."
  [{:keys [base-uri token]} command fields {:keys [field data mtype filename]}]
  (when (str/blank? base-uri)
    (throw (ex-info "Penpot base URI is not configured" {})))
  (let [uri  (join-uri base-uri (str "api/main/methods/" (name command)))
        form (http/FormData.)]
    (doseq [[k v] fields]
      (.append form k (str v)))
    (.append form field (js/Blob. #js [data] #js {:type mtype}) filename)
    (->> (p/do (http/fetch uri #js {:method "POST"
                                   :headers (cond-> #js {"Accept" "application/transit+json"}
                                              (some? token)
                                              (doto (unchecked-set "Authorization" (str "Token " token))))
                                   :body form}))
         (p/mcat (partial handle-response command)))))

;; --- server-sent events (import-binfile)

(defn- parse-sse
  "[event data] pairs of a server-sent events body (Penpot writes transit
  data, one event per block)."
  [body]
  (into []
        (keep (fn [block]
                (let [lines (str/lines block)
                      event (some #(when (str/starts-with? % "event:") (str/trim (subs % 6))) lines)
                      data  (->> lines
                                 (filter #(str/starts-with? % "data:"))
                                 (map #(str/trim (subs % 5)))
                                 (str/join "\n"))]
                  (when event
                    [event (when-not (str/blank? data)
                             (try (t/decode-str data) (catch :default _ data)))]))))
        (str/split (str body) #"\r?\n\r?\n")))

(defn- rpc-sse
  "Calls a streaming RPC command and resolves to its events once the stream
  ends."
  [{:keys [base-uri token]} command params]
  (when (str/blank? base-uri)
    (throw (ex-info "Penpot base URI is not configured" {})))
  (let [uri (join-uri base-uri (str "api/main/methods/" (name command)))]
    (->> (p/do (http/fetch uri #js {:method "POST"
                                   :headers (doto (auth-headers token)
                                              (unchecked-set "Accept" "text/event-stream"))
                                   :body (t/encode-str params)}))
         (p/mcat (fn [^js resp]
                   (->> (.text resp)
                        (p/mcat (fn [body]
                                  (if (= 200 (.-status resp))
                                    (p/resolved (parse-sse body))
                                    (p/rejected (rpc-error command (.-status resp) body)))))))))))

;; --- chunked uploads (fonts, .penpot packages)

(def ^:private chunk-size (* 20 1024 1024))

(defn- upload-session!
  "Uploads `data` with the chunked-upload API, like the frontend
  (create-upload-session + upload-chunk), and resolves to the session id."
  [ctx ^js data mtype filename]
  (let [total  (.-byteLength data)
        chunks (max 1 (js/Math.ceil (/ total chunk-size)))]
    (->> (rpc ctx :create-upload-session {:total-chunks chunks})
         (p/mcat (fn [{:keys [session-id]}]
                   (->> (range chunks)
                        (reduce (fn [prev i]
                                  (p/mcat (fn [_]
                                            (rpc-multipart ctx :upload-chunk
                                                           {"session-id" session-id "index" i}
                                                           {:field "content"
                                                            :data (.subarray data (* i chunk-size)
                                                                             (min total (* (inc i) chunk-size)))
                                                            :mtype mtype
                                                            :filename filename}))
                                          prev))
                                (p/resolved nil))
                        (p/fmap (constantly session-id))))))))

;; --- fonts

(defn- family-uuid
  [id]
  (uuid/from-unsigned-parts (aget id 0) (aget id 1) (aget id 2) (aget id 3)))

(defn- find-variant
  [variants font-uuid weight style]
  (let [style-str (if (zero? style) "normal" "italic")]
    (or (d/seek (fn [v] (and (= (:font-id v) font-uuid)
                             (= (:font-weight v) weight)
                             (= (name (:font-style v)) style-str)))
                variants)
        (d/seek (fn [v] (and (= (:font-id v) font-uuid)
                             (= (:font-weight v) weight)))
                variants)
        (d/seek (fn [v] (= (:font-id v) font-uuid)) variants))))

(defonce ^:private font-cache (atom {}))

(defn- cached-bytes
  [cache-key fetch-fn]
  (if-let [hit (get @font-cache cache-key)]
    hit
    (let [pending (->> (fetch-fn)
                       (p/fmap (fn [buf]
                                 (when-not buf (swap! font-cache dissoc cache-key))
                                 buf)))]
      (swap! font-cache assoc cache-key pending)
      pending)))

(defn- builtin-font-bytes
  [{:keys [base-uri]} ttf-file]
  (let [local (some-> @fonts-dir* (path/join ttf-file))]
    (if (and local (fs/existsSync local))
      (cached-bytes local #(p/resolved (fs/readFileSync local)))
      (let [uri (some-> base-uri (join-uri (str "fonts/" ttf-file)))]
        (cached-bytes uri #(fetch-bytes uri nil))))))

(defn- gfont-bytes
  "Google font file through the Penpot instance's gfonts proxy, like the
  exporter. Offline (no backend), only when `:gfonts-direct?` is set: straight
  from fonts.gstatic.com (tests)."
  [{:keys [base-uri gfonts-direct?]} ttf-url]
  (cond
    (some? base-uri)
    (let [uri (cfnt/gstatic->proxy-url ttf-url (join-uri base-uri "internal/gfonts/font"))]
      (cached-bytes uri #(fetch-bytes uri nil)))

    gfonts-direct?
    (cached-bytes ttf-url #(fetch-bytes ttf-url nil))

    :else
    (p/resolved nil)))

(defn- make-resolve-font
  [variants {:keys [base-uri token] :as ctx}]
  (fn [{:keys [id weight style]}]
    (let [font-uuid (family-uuid id)
          variant   (find-variant variants font-uuid weight style)]
      (cond
        (and (:ttf-file-id variant) base-uri)
        (fetch-bytes (join-uri base-uri (str "assets/by-id/" (:ttf-file-id variant))) token)

        (= uuid/zero font-uuid)
        (builtin-font-bytes ctx (cfnt/resolve-ttf-file weight style))

        :else
        (if-let [gurl (cfnt/resolve-ttf-url font-uuid weight style)]
          (gfont-bytes ctx gurl)
          (p/resolved nil))))))

(defn- scene-fallback-fonts
  [scene]
  (let [texts  (for [shape (vals scene)
                     :when (= :text (:type shape))
                     node  (or (some->> (:content shape) (tree-seq :children :children)) [])
                     :let  [text (:text node)]
                     :when (string? text)]
                 text)
        emoji? (boolean (some cfnt/contains-emoji? texts))
        langs  (reduce cfnt/collect-used-languages #{} texts)]
    (distinct
     (cond-> (cfnt/add-noto-fonts [] langs)
       emoji? (cfnt/add-emoji-font)))))

(defn- provision-fallback-fonts!
  [ctx scene]
  (->> (scene-fallback-fonts scene)
       (map (fn [{:keys [font-id weight style is-emoji is-fallback]}]
              (if-let [font-uuid (cfnt/gfont-id->uuid font-id)]
                (if-let [ttf-url (cfnt/resolve-ttf-url font-uuid weight style)]
                  (->> (gfont-bytes ctx ttf-url)
                       (p/fmap (fn [buf]
                                 (when buf
                                   (wasm/store-font! {:id (uuid/get-u32 font-uuid)
                                                      :weight weight
                                                      :style style
                                                      :emoji? (boolean is-emoji)
                                                      :fallback? (boolean is-fallback)}
                                                     buf)))))
                  (p/resolved nil))
                (p/resolved nil))))
       (p/all)))

;; --- images

(defn- image-bytes
  "Encoded bytes of file media `image-id`: given by the caller (`:images`,
  offline) or fetched from the backend."
  [{:keys [base-uri token images]} image-id]
  (if-let [local (get images image-id)]
    (p/resolved local)
    (if (nil? base-uri)
      (p/resolved nil)
      (fetch-bytes (join-uri base-uri (str "assets/by-file-media-id/" image-id)) token))))

(defn- provision-images!
  [ctx scene]
  (->> (remove wasm/image-cached? (images/scene-image-ids scene))
       (map (fn [image-id]
              (->> (image-bytes ctx image-id)
                   (p/fmap (fn [buf]
                             (if buf
                               (wasm/store-image! image-id buf)
                               (l/warn :hint "image unavailable" :media-id (str image-id))))))))
       (p/all)))

;; media id -> {:width :height :mtype}; media objects never change
(defonce ^:private media-info-cache (atom {}))

(defn- media-info!
  "Natural size and type of file media `image-id`, read from the image (the
  backend has no read RPC for media object metadata), or nil when it can't
  be read. The bytes also go to the render-wasm image store, which the text
  measuring and the render that follow reuse. Needs the module loaded."
  [ctx image-id]
  (if-let [hit (get @media-info-cache image-id)]
    (p/resolved hit)
    (->> (image-bytes ctx image-id)
         (p/fmap (fn [buf]
                   (when-let [info (some-> buf media/info)]
                     (when-not (wasm/image-cached? image-id)
                       (wasm/store-image! image-id buf))
                     (swap! media-info-cache assoc image-id info)
                     info))))))

(defn- media-infos!
  "image id -> info for the image fills referenced in `ops`."
  [ctx ops]
  (->> (edit/image-refs ops)
       (map (fn [id]
              (->> (media-info! ctx id)
                   (p/fmap (fn [info] (when info [id info])))
                   (p/merr (fn [cause]
                             (l/warn :hint "media info failed" :media-id (str id) :cause cause)
                             (p/resolved nil))))))
       (p/all)
       (p/fmap #(into {} (remove nil?) %))))

;; --- scene loading

(defn- load-scene!
  "Loads `scene` (an objects map) into the WASM state with fonts and images for
  the subtrees of `ids`, and lays out every text with the real fonts."
  [ctx scene ids variants]
  (serialize/serialize-scene! scene)
  (wasm/clear-fonts!)
  (->> (p/all [(provision-images! ctx scene)
               (provision-fallback-fonts! ctx scene)])
       (p/mcat (fn [_]
                 (wasm/provision-fonts! ids (make-resolve-font (or variants []) ctx))))
       (p/fmap (fn [_]
                 (doseq [shape (vals scene)
                         :when (= :text (:type shape))]
                   (wasm/update-text-layout! (:id shape)))
                 nil))))

(defn- reset-on-error
  [promise]
  (p/merr (fn [cause]
            ;; A panic can leave the instance aborted: rebuild next time.
            (when-not (= :invalid-op (:type (ex-data cause)))
              (reset! module* nil))
            (p/rejected cause))
          promise))

(defn- render-ids
  "Renders each id of the loaded scene."
  [ids {:keys [scale format]}]
  (mapv (fn [id]
          (case format
            :svg (wasm/render-shape-svg id scale)
            :pdf (wasm/render-shape-pdf id)
            (wasm/render-shape-raster id scale format)))
        ids))

(defn- render-scene!
  [ctx scene object-ids {:keys [variants] :as opts}]
  (->> (load-scene! ctx scene object-ids variants)
       (p/fmap (fn [_] (render-ids object-ids opts)))
       (reset-on-error)))

(defn- ->uuid [v] (if (uuid? v) v (uuid/parse v)))

(defn- ->ctx [^js opts] {:base-uri (.-baseUri opts) :token (.-token opts)})

(defn- js-images
  "{uuid-string: bytes} (JS object) -> {uuid bytes}."
  [^js images]
  (when images
    (into {} (map (fn [k] [(uuid/parse k) (unchecked-get images k)])) (js-keys images))))

(defn- guard
  "Calls `f` and always returns a promise: synchronous throws become
  rejections, so JS callers only ever need `await`/`catch`."
  [f]
  (try
    (p/promise (f))
    (catch :default cause
      (p/rejected cause))))

(defn- ->js
  "clj->js that also turns uuids into strings."
  [v]
  (clj->js (walk/postwalk #(if (uuid? %) (str %) %) v)))

(declare themed-objects!)

(defn render-shape
  "JS entry. Renders one shape of a file to image bytes.

  opts: {baseUri, token, fileId, pageId, objectId, scale=1, format='png'
  (png, jpeg, webp, svg, pdf), theme?: token theme(s) to preview ('Mode/Dark'
  or a list), applied to the render only}
  Resolves to a Uint8Array."
  [^js opts]
  (guard
   #(let [ctx       (->ctx opts)
          file-id   (->uuid (.-fileId opts))
          page-id   (->uuid (.-pageId opts))
          object-id (->uuid (.-objectId opts))
          scale     (or (.-scale opts) 1)
          format    (keyword (or (.-format opts) "png"))
          themes    (let [t (some-> (.-theme opts) js->clj)] (cond (string? t) [t] (sequential? t) (vec t)))]
    (if (seq themes)
      (reset-on-error
       (p/let [_        (ensure-module!)
               [file variants] (p/all [(rpc ctx :get-file {:id file-id}) (font-variants! ctx file-id)])
               objects  (themed-objects! ctx file page-id themes variants)]
         (p/fmap first (render-scene! ctx objects [object-id] {:scale scale :format format :variants variants}))))
      (->> (ensure-module!)
         (p/mcat (fn [_]
                   (p/all [(rpc ctx :get-page {:file-id file-id
                                               :page-id page-id
                                               :object-id #{object-id}})
                           (->> (rpc ctx :get-font-variants {:file-id file-id})
                                (p/merr (fn [_] (p/resolved []))))])))
         (p/mcat (fn [[page variants]]
                   (render-scene! ctx (:objects page) [object-id]
                                  {:scale scale :format format :variants variants})))
         (p/fmap first))))))

;; --- editing

(defn- measure-texts!
  "Text id -> {:width :height} laid out by render-wasm with the real fonts."
  [ctx state variants]
  (let [texts (:texts state)]
    (if (empty? texts)
      (p/resolved {})
      (->> (load-scene! ctx (edit/objects state) (vec texts) variants)
           (p/fmap (fn [_]
                     (into {} (keep (fn [id] (when-let [dims (edit/text-dimensions id)] [id dims])))
                           texts)))))))

(defn- add-position-data!
  "Recomputes the position data of texts that were created, edited or moved."
  [ctx state variants]
  (let [ids (edit/text-ids state)]
    (if (empty? ids)
      (p/resolved state)
      (let [objects (edit/objects state)]
        (->> (load-scene! ctx objects (vec ids) variants)
             (p/fmap (fn [_]
                       (edit/with-position-data
                         state
                         (into {} (map (fn [id] [id (edit/position-data (get objects id))])) ids)))))))))

(defn- layout!
  "The layout pass of an edit: measure texts, apply layouts and geometry
  (twice, for auto-height texts sized by a layout), size `contain` image
  fills, compute text position data. Resolves to the state."
  [ctx state variants]
  (p/let [dims  (measure-texts! ctx state variants)
          state (p/do (edit/second-pass (edit/apply-rotations (edit/apply-geometry state dims))))
          dims  (measure-texts! ctx state variants)
          state (p/do (edit/regroup (edit/fit-images (edit/apply-geometry state dims))))]
    (add-position-data! ctx state variants)))

(defn- edit-objects!
  "Runs the edit pipeline on `objects`: build changes from ops, then the
  layout pass. `variants` are the team's font variants; `opts` the
  `:libraries` (edited file first) and `:file-id`. Resolves to the state."
  ([ctx page-id objects ops variants] (edit-objects! ctx page-id objects ops variants nil))
  ([ctx page-id objects ops variants opts]
   (reset-on-error
    (p/let [_     (ensure-module!)
            media (media-infos! ctx ops)
            state (p/do (edit/build page-id objects ops (assoc opts
                                                               :fonts (fonts/team-fonts variants)
                                                               :media media)))]
      (layout! ctx state variants)))))

(defn- edit-result
  [state]
  (let [objects (edit/objects state)
        names   (library/asset-names (:assets state))]
    (cond-> {:changes (count (:redo-changes (:changes state)))
             :created (into {} (map (fn [[ref id]] [ref (str id)])) (:refs state))
             :createdIds (mapv str (:created state))
             :deleted (mapv str (:deleted state))
             :components (some->> (:components state) seq (mapv str))
             :shapes (into []
                           (keep (fn [id] (some-> (get objects id) (as-> s (edit/shape-summary objects s names)))))
                           (distinct (concat (:created state) (:relocated state) (:moved state))))}
      (seq (:link-reports state)) (assoc :linkAssets (vec (:link-reports state))))))

(defn- ops->clj [ops] (js->clj ops :keywordize-keys true))

(defn- first-page-id
  [file]
  (first (get-in file [:data :pages])))

(defn- save-changes!
  "Saves `changes` to `file` in one update-file (unless it is a dry run or
  nothing changed) and adds :revn and :saved to `result`."
  [ctx file changes dry-run? result]
  (if (or dry-run? (empty? changes))
    (p/resolved (assoc result :revn (:revn file) :saved false))
    (->> (rpc ctx :update-file {:id (:id file)
                                :session-id (uuid/next)
                                :revn (:revn file)
                                :vern (or (:vern file) 0)
                                :changes (vec changes)})
         (p/fmap (fn [_] (assoc result :revn (inc (:revn file)) :saved true))))))

(defn- font-variants!
  "The team's font variants for a file; none when they can't be read (the
  edit still works with Google and builtin fonts)."
  [ctx file-id]
  (->> (rpc ctx :get-font-variants {:file-id file-id})
       (p/merr (fn [_] (p/resolved [])))))

(defn- own-library
  [file]
  {:id (:id file) :name (:name file) :data (:data file) :local? true})

(defn- linked-libraries!
  "The shared libraries linked to a file (also indirectly), with their data."
  [ctx file-id]
  (->> (rpc ctx :get-file-libraries {:file-id file-id})
       (p/mcat (fn [libraries]
                 (->> libraries
                      (map (fn [{:keys [id]}]
                             (p/fmap (fn [lib] {:id (:id lib) :name (:name lib) :data (:data lib)})
                                     (rpc ctx :get-file {:id id}))))
                      (p/all))))))

(declare tokens-source-data!)

(declare sync-file!)

(defn- edit-file!
  "The edit pipeline on a loaded `file`: `ops` on page `page-id`, then the
  copies of the file's components whose main changed are updated on every
  page, like the editor does after editing a main. `linked`: the linked
  libraries, `toks`: the active tokens. Resolves to {:changes :data :result}."
  [ctx file page-id ops variants linked toks]
  (let [page (or (get-in file [:data :pages-index page-id])
                 (throw (ex-info (str "Page " page-id " not found in file " (:id file)) {:type :invalid-op})))
        libs (into [(own-library file)] linked)]
    (p/let [state  (edit-objects! ctx page-id (:objects page) ops variants
                                  {:libraries libs :file-id (:id file) :tokens toks :flows (:flows page)})
            redo   (vec (:redo-changes (:changes state)))
            data   (p/do (cfc/process-changes (:data file) redo false))
            mains  (p/do (edit/changed-mains state (:objects page)))
            synced (if (seq mains)
                     (sync-file! ctx (assoc file :data data) libs (:id file)
                                 (mapv (fn [id] [:components id]) mains) variants)
                     {:changes [] :data data})]
      (let [extra (:changes synced)]
        {:changes (into redo extra)
         :data (:data synced)
         :result (cond-> (assoc (edit-result state) :pageId (str page-id))
                   (some #(#{"flow" "deleteflow"} (some-> (:op %) str str/lower)) ops)
                   (assoc :flows (edit/flows state))
                   (seq extra)
                   (assoc :copiesUpdated (count (into #{} (comp (filter #(= :mod-obj (:type %))) (map :id)) extra))))}))))

(defn apply-ops
  "JS entry. Applies `ops` to a page of a Penpot file and saves them (with
  the copies of components whose main changed).

  opts: {baseUri, token, fileId, pageId?, ops, dryRun?}
  Resolves to {changes, created: {ref: id}, createdIds, deleted, shapes, revn}."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))
          ops     (ops->clj (.-ops opts))]
      (p/let [[file variants] (p/all [(rpc ctx :get-file {:id file-id})
                                      (font-variants! ctx file-id)])
              linked  (if (edit/needs-libraries? ops) (linked-libraries! ctx file-id) [])
              toks    (if (edit/needs-tokens? ops)
                        (p/fmap (fn [src] (tokens/active-tokens (:data file) src)) (tokens-source-data! ctx file))
                        {})
              page-id (or (some-> (.-pageId opts) ->uuid) (first-page-id file))
              {:keys [changes result]} (edit-file! ctx file page-id ops variants linked toks)
              result  (save-changes! ctx file changes (.-dryRun opts) result)]
        (->js result)))))

(defn apply-ops-offline
  "JS entry. Applies `ops` to an empty in-memory page (no Penpot needed) and
  renders `renderRef` (a ref or id; default: the first created shape).

  opts: {renderRef?, scale?, images?: {mediaId: bytes} for image fills,
         gfonts?: true to load Google fonts straight from fonts.gstatic.com}
  Resolves to {png, changes, created, shapes, tree}."
  [^js ops ^js opts]
  (guard
   #(let [page-id (uuid/next)
          objects (:objects (ctp/make-empty-page {:id page-id :name "Page 1"}))
          ops     (ops->clj ops)
          scale   (or (some-> opts .-scale) 1)
          ctx     {:images (js-images (some-> opts .-images))
                   :gfonts-direct? (boolean (some-> opts .-gfonts))}
          file-id (uuid/next)
          libs    (vec (some-> opts .-libraries))
          toks    (when-let [f (some-> opts .-tokensFile)] (tokens/active-tokens (:data f) (:data f)))]
    (->> (edit-objects! ctx page-id objects ops []
                        {:libraries (into [{:id file-id :name "Offline" :data {} :local? true}] libs)
                         :file-id file-id
                         :tokens toks})
         (p/mcat (fn [state]
                   (let [objects (edit/objects state)
                         target  (let [r (some-> opts .-renderRef)]
                                   (or (get (:refs state) r) (some-> r uuid/parse*) (first (:created state))))]
                     (->> (if target
                            (render-scene! ctx objects [target] {:scale scale :format :png :variants []})
                            (p/resolved [nil]))
                          (p/fmap (fn [[png]]
                                    (-> (edit-result state)
                                        (assoc :tree (edit/tree objects uuid/zero 8))
                                        (assoc :objects (into {}
                                                              (map (fn [[id shape]]
                                                                     [(str id) (dissoc shape :points :transform :transform-inverse
                                                                                       :position-data :svg-defs :svg-attrs)]))
                                                              (dissoc objects uuid/zero)))
                                        (->js)
                                        (doto (unchecked-set "png" png)))))))))))))

(defn- ref-names!
  "Asset id -> {:name …} for the library refs of `shapes`, read from the
  files the refs point to (files that can't be read are skipped: their
  assets are shown as not available)."
  [ctx shapes]
  (let [file-ids (edit/ref-file-ids shapes)]
    (if (empty? file-ids)
      (p/resolved {})
      (->> file-ids
           (map (fn [id]
                  (->> (rpc ctx :get-file {:id id})
                       (p/fmap (fn [f] {:id (:id f) :name (:name f) :data (:data f)}))
                       (p/merr (fn [_] (p/resolved nil))))))
           (p/all)
           (p/fmap #(library/asset-names (library/index (remove nil? %))))))))

(defn page-summary
  "JS entry. Compact tree of a page for the AI.

  opts: {baseUri, token, fileId, pageId?, rootId?, depth=4}"
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))
          page-id (some-> (.-pageId opts) ->uuid)]
      (p/let [page  (rpc ctx :get-page (cond-> {:file-id file-id}
                                         page-id (assoc :page-id page-id)))
              names (ref-names! ctx (vals (:objects page)))]
        (let [objects (:objects page)
              root    (or (some-> (.-rootId opts) ->uuid) uuid/zero)]
          (->js (d/without-nils
                 {:pageId (str (:id page))
                  :pageName (:name page)
                  :flows (some->> (vals (:flows page))
                                  (map (fn [f] {:name (:name f) :start (str (:starting-frame f))
                                                :startName (:name (get objects (:starting-frame f)))}))
                                  seq
                                  vec)
                  :tree (edit/tree objects root (or (.-depth opts) 4) names)})))))))

;; --- projects, files and pages

(defn create-project
  "JS entry. Creates a project.

  opts: {baseUri, token, name, teamId?} — without teamId, the account's
  default team. Resolves to {projectId, name, teamId, teamName?}."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          team-id (some-> (.-teamId opts) ->uuid)]
    (p/let [teams   (if team-id (p/resolved nil) (rpc ctx :get-teams {}))
            team    (when-not team-id
                      (or (d/seek :is-default teams) (first teams)
                          (throw (ex-info "This account has no team to create the project in" {}))))
            team-id (or team-id (:id team))
            project (rpc ctx :create-project {:team-id team-id :name (.-name opts)})]
      (->js (d/without-nils {:projectId (:id project)
                             :name (:name project)
                             :teamId team-id
                             :teamName (:name team)}))))))

(defn file-pages
  "JS entry. Pages of a file, in order: [{id, name}]."
  [^js opts]
  (guard
   #(->> (rpc (->ctx opts) :get-file {:id (->uuid (.-fileId opts))})
         (p/fmap (fn [file] (->js (:pages (pages/result (pages/initial (:data file))))))))))

(defn create-file
  "JS entry. Creates a file in a project.

  opts: {baseUri, token, projectId, name, pages?: [name, ...], shared?} —
  `pages` names the first page and adds the others, in order, in the same
  call; `shared` publishes the file as a shared library.
  Resolves to {fileId, name, projectId, pages: [{id, name}], revn}."
  [^js opts]
  (guard
   #(let [ctx        (->ctx opts)
          project-id (->uuid (.-projectId opts))
          names      (some-> (.-pages opts) js->clj seq vec)]
    (p/let [created (rpc ctx :create-file {:name (.-name opts) :project-id project-id})
            file    (rpc ctx :get-file {:id (:id created)})
            data    (:data file)
            ops     (when names
                      (into [{:op "rename" :id (str (first (:pages data))) :name (first names)}]
                            (map (fn [n] {:op "add" :name n}))
                            (rest names)))
            result  (if ops
                      (let [state (pages/build data ops)]
                        (save-changes! ctx file (:changes state) false (pages/result state)))
                      (p/resolved (assoc (pages/result (pages/initial data)) :revn (:revn file))))]
      (p/let [_ (when (.-shared opts) (rpc ctx :set-file-shared {:id (:id file) :is-shared true}))]
        (->js (-> result
                  (select-keys [:pages :revn])
                  (assoc :fileId (str (:id file))
                         :name (:name file)
                         :projectId (str project-id)
                         :shared (boolean (.-shared opts))))))))))

(defn edit-pages
  "JS entry. Adds, renames, deletes and moves pages of a file, saved in one
  update-file.

  opts: {baseUri, token, fileId, ops, dryRun?}
  Resolves to {pages: [{id, name}], created: {ref: id}, addedIds, deleted, revn, saved}."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))
          ops     (ops->clj (.-ops opts))]
    (->> (rpc ctx :get-file {:id file-id})
         (p/mcat (fn [file]
                   (let [state (pages/build (:data file) ops)]
                     (save-changes! ctx file (:changes state) (.-dryRun opts) (pages/result state)))))
         (p/fmap ->js)))))

;; --- fonts and media

(defn list-fonts
  "JS entry. Font families for texts, grouped by source.

  opts: {baseUri, token, fileId? | teamId?, search?, limit=30} — fileId or
  teamId adds the team's uploaded fonts.
  Resolves to {team, builtin, google, googleMatches}."
  [^js opts]
  (guard
   #(let [ctx    (->ctx opts)
          params (cond
                   (.-fileId opts) {:file-id (->uuid (.-fileId opts))}
                   (.-teamId opts) {:team-id (->uuid (.-teamId opts))})]
    (->> (if params (rpc ctx :get-font-variants params) (p/resolved []))
         (p/fmap (fn [variants]
                   (->js (fonts/search (fonts/team-fonts variants)
                                       (.-search opts)
                                       (or (.-limit opts) 30)))))))))

(defn upload-image
  "JS entry. Adds an image to a file's media, for image fills.

  opts: {baseUri, token, fileId, name, url} — the Penpot backend downloads it —
  or {baseUri, token, fileId, name, data (bytes), mimeType}.
  Resolves to {id, name, width, height, mtype}."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))
          name    (.-name opts)]
    (->> (if-let [url (.-url opts)]
           (rpc ctx :create-file-media-object-from-url
                {:file-id file-id :url url :name name :is-local true})
           (rpc-multipart ctx :upload-file-media-object
                          {"file-id" file-id "name" name "is-local" "true"}
                          {:field "content" :data (.-data opts) :mtype (.-mimeType opts) :filename name}))
         (p/fmap (fn [mobj]
                   (->js (select-keys mobj [:id :name :width :height :mtype]))))))))

;; --- team fonts

(defn upload-font
  "JS entry. Adds a font variant to a team's fonts, like the font upload of
  the dashboard (chunked upload + create-font-variant). A family that
  already exists in the team (same name) gets the new variant.

  opts: {baseUri, token, teamId? | fileId?, data, mimeType, family, weight, style}
  Resolves to {fontId, family, weight, style, variantId, teamId, newFamily}."
  [^js opts]
  (guard
   #(let [ctx    (->ctx opts)
          family (str/trim (str (.-family opts)))
          weight (.-weight opts)
          style  (str (.-style opts))
          mtype  (.-mimeType opts)]
      (p/let [team-id  (if-let [team-id (.-teamId opts)]
                         (->uuid team-id)
                         (p/fmap :id (rpc ctx :get-team {:file-id (->uuid (.-fileId opts))})))
              variants (rpc ctx :get-font-variants {:team-id team-id})
              same     (filterv (fn [v] (= (u/normalize (:font-family v)) (u/normalize family))) variants)
              _        (when (some (fn [v] (and (= weight (:font-weight v)) (= style (:font-style v)))) same)
                         (throw (ex-info (str "The team already has " (:font-family (first same)) " " weight
                                              (when (= "italic" style) " italic")
                                              ": nothing uploaded. Use it with fontFamily in edit_page.")
                                         {:type :invalid-op})))
              font-id  (or (:font-id (first same)) (uuid/next))
              family   (or (:font-family (first same)) family)
              session  (upload-session! ctx (.-data opts) mtype (str family "." (last (str/split mtype "/"))))
              _        (rpc ctx :create-font-variant {:team-id team-id
                                                     :font-id font-id
                                                     :font-family family
                                                     :font-weight weight
                                                     :font-style style
                                                     :uploads {mtype session}})]
        (->js {:fontId (str "custom-" font-id)
               :family family
               :weight weight
               :style style
               :variantId (str style "-" weight)
               :teamId team-id
               :newFamily (empty? same)})))))

;; --- libraries

(defn- sse-error
  [command data]
  (ex-info (str "Penpot " (name command) " failed: "
                (or (:hint data) (some-> (:code data) name) (pr-str data)))
           {:type (:type data) :code (:code data)}))

(defn import-library
  "JS entry. Imports a .penpot package into a project (chunked upload +
  import-binfile, Penpot 2.15+), optionally shares the imported files and
  links them to other files.

  opts: {baseUri, token, projectId, name, data, shared=true, linkTo?: [fileId]}
  Resolves to {files: [{fileId, name, shared}], linked: [{fileId, libraryId}]}."
  [^js opts]
  (guard
   #(let [ctx        (->ctx opts)
          project-id (->uuid (.-projectId opts))
          name       (str (.-name opts))
          shared?    (not (false? (.-shared opts)))
          link-to    (mapv ->uuid (or (some-> (.-linkTo opts) js->clj) []))]
      (p/let [session (upload-session! ctx (.-data opts) "application/zip" (str name ".penpot"))
              events  (rpc-sse ctx :import-binfile {:project-id project-id :name name :upload-id session})
              _       (when-let [[_ data] (d/seek (fn [[event]] (= "error" event)) events)]
                        (throw (sse-error :import-binfile data)))
              result  (second (d/seek (fn [[event]] (= "end" event)) events))
              ids     (let [ids (if (map? result) (:file-ids result) result)]
                        (if (seq ids)
                          (vec ids)
                          (throw (ex-info "Penpot import-binfile ended without imported files" {}))))
              files   (rpc ctx :get-project-files {:project-id project-id})
              files   (filterv (fn [f] (some #{(:id f)} ids)) files)
              _       (p/all (for [f files :when (and shared? (not (:is-shared f)))]
                               (rpc ctx :set-file-shared {:id (:id f) :is-shared true})))
              libs    (filterv (fn [f] (or shared? (:is-shared f))) files)
              _       (reduce (fn [prev [file-id lib-id]]
                                (p/mcat (fn [_] (rpc ctx :link-file-to-library {:file-id file-id :library-id lib-id})) prev))
                              (p/resolved nil)
                              (for [file-id link-to lib libs] [file-id (:id lib)]))]
        (->js {:files (mapv (fn [f] {:fileId (:id f) :name (:name f) :shared (or shared? (boolean (:is-shared f)))})
                            files)
               :linked (vec (for [file-id link-to lib libs] {:fileId file-id :libraryId (:id lib)}))})))))

(defn- sync-changes
  "Redo changes of Penpot's library sync (`generate-sync-file-changes`, as
  the editor does after a library change or when accepting library updates)
  of `file-id` from `library-id`, for `asset-type` (nil: all) and
  `asset-id` (nil: all of that type)."
  [libraries file-id library-id asset-type asset-id]
  (vec (:redo-changes
        (cll/generate-sync-file-changes (pcb/empty-changes nil) nil asset-type file-id asset-id
                                        library-id libraries file-id))))

(defn- relayout!
  "Measures texts and reflows layouts on the pages `changes` touched, with
  the file `data` they produce. Pages run one after another (one WASM
  scene). Resolves to the extra redo changes."
  [ctx data changes variants libraries file-id]
  (let [by-page (->> changes
                     (filter #(and (:page-id %) (:id %)))
                     (group-by :page-id))]
    (reduce (fn [prev [page-id page-changes]]
              (p/mcat (fn [acc]
                        (let [objects (get-in data [:pages-index page-id :objects])
                              state   (-> (edit/start page-id objects {:fonts (fonts/team-fonts variants)
                                                                       :libraries libraries
                                                                       :file-id file-id})
                                          (edit/touch (distinct (map :id page-changes))))]
                          (p/fmap (fn [state] (into acc (:redo-changes (:changes state))))
                                  (layout! ctx state variants))))
                      prev))
            (p/resolved [])
            by-page)))

(defn- sync-file!
  "Syncs `file` (with its current `:data`) from `library-id` for each of
  `assets` ([asset-type asset-id], nil for all) and lays out what changed.
  Resolves to {:changes [...] :data data'}."
  [ctx file libraries library-id assets variants]
  (let [file-id (:id file)
        [data changes]
        (reduce (fn [[data acc] [asset-type asset-id]]
                  (let [libs    (assoc (into {} (map (juxt :id identity)) libraries)
                                       file-id (assoc file :data data))
                        changes (sync-changes libs file-id library-id asset-type asset-id)]
                    [(cfc/process-changes data changes false) (into acc changes)]))
                [(:data file) []]
                assets)]
    (if (empty? changes)
      (p/resolved {:changes [] :data data})
      (->> (relayout! ctx data changes variants
                      (into [(assoc (own-library file) :data data)] (remove #(= file-id (:id %)) libraries))
                      file-id)
           (p/fmap (fn [extra] {:changes (into changes extra)
                                :data (cfc/process-changes data extra false)}))))))

(defn link-library
  "JS entry. Links a shared library to a file (or unlinks it). With `sync`,
  also applies the library's current components, colors and typographies to
  the file, like accepting \"library updates\" in the editor.

  opts: {baseUri, token, fileId, libraryId, unlink?, sync?, dryRun?}
  Resolves to {linked, synced, changes, revn?}."
  [^js opts]
  (guard
   #(let [ctx        (->ctx opts)
          file-id    (->uuid (.-fileId opts))
          library-id (->uuid (.-libraryId opts))]
      (if (.-unlink opts)
        (->> (rpc ctx :unlink-file-from-library {:file-id file-id :library-id library-id})
             (p/fmap (fn [_] (->js {:linked false}))))
        (p/let [_       (when-not (.-dryRun opts)
                          (rpc ctx :link-file-to-library {:file-id file-id :library-id library-id}))
                result  (if-not (.-sync opts)
                          {:linked true :synced false}
                          (reset-on-error
                           (p/let [_        (ensure-module!)
                                   [file variants] (p/all [(rpc ctx :get-file {:id file-id})
                                                           (font-variants! ctx file-id)])
                                   linked   (linked-libraries! ctx file-id)
                                   _        (when-not (some (fn [lib] (= library-id (:id lib))) linked)
                                              (throw (ex-info "The library is not linked to the file" {})))
                                   {:keys [changes]} (sync-file! ctx file (into [(own-library file)] linked)
                                                                 library-id [[nil nil]] variants)
                                   saved    (save-changes! ctx file changes (.-dryRun opts)
                                                           {:linked true :synced true :changes (count changes)})
                                   ;; like the editor after accepting updates: no more
                                                   ;; "library updates" notice for this file
                                   _        (when-not (.-dryRun opts)
                                              (rpc ctx :update-file-library-sync-status
                                                   {:file-id file-id :library-id library-id}))]
                             saved)))]
          (->js result))))))

(defn library-assets
  "JS entry. The components, colors and typographies a file can use: its own
  library and its linked libraries.

  opts: {baseUri, token, fileId, search?, limit=50}
  Resolves to [{fileId, name, local?, components, colors, typographies, totals}]."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))]
      (p/let [file   (rpc ctx :get-file {:id file-id})
              linked (linked-libraries! ctx file-id)]
        (->js (library/describe (into [(own-library file)] linked)
                                (.-search opts)
                                (or (.-limit opts) 50)))))))

(defn- edit-library*
  "Applies library ops to `file` and syncs the shapes that use updated
  assets. Resolves to {:changes [...] :data data' :touched [ids]}."
  [ctx file ops variants]
  (let [lib   (library/build-ops (:data file) (fonts/team-fonts variants) (ops->clj ops))
        kinds (vec (distinct (map first (:updated lib))))
        file' (assoc file :data (:data lib))]
    (->> (if (empty? kinds)
           (p/resolved {:changes [] :data (:data lib)})
           (sync-file! ctx file' [(own-library file')] (:id file) (mapv (fn [k] [k nil]) kinds) variants))
         (p/fmap (fn [{:keys [changes data]}]
                   {:changes (into (:changes lib) changes)
                    :data data
                    :touched (:touched lib)})))))

(defn edit-library
  "JS entry. Adds, updates and deletes the colors and typographies of a
  file's own library; shapes of the file that use an updated asset are
  updated too (texts re-measured, layouts reflowed). Files that link this
  library get it with link_library {sync: true} (or in the editor).

  opts: {baseUri, token, fileId, ops, dryRun?}
  Resolves to {changes, assets, revn, saved}."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))]
      (reset-on-error
       (p/let [_ (ensure-module!)
               [file variants] (p/all [(rpc ctx :get-file {:id file-id})
                                       (font-variants! ctx file-id)])
               {:keys [changes data]} (edit-library* ctx file (.-ops opts) variants)
               result (save-changes! ctx file changes (.-dryRun opts)
                                     {:changes (count changes)
                                      :library (first (library/describe [(assoc (own-library file) :data data)] nil 200))})]
         (->js result))))))

;; --- moving and copying shapes between pages and files

(defn- file-lib [file] {:id (:id file) :name (:name file) :data (:data file)})

(defn- by-id [files] (into {} (map (juxt :id identity)) files))

(defn- shape-summaries
  [objects ids names]
  (into [] (keep (fn [id] (some-> (get objects id) (as-> s (edit/shape-summary objects s names))))) ids))

(defn- move-shapes*
  "move_shapes on loaded files. `env`: {:ctx, :source and :target files
  (:id :name :data), :linked (the target's linked libraries), :src-linked
  (the source's), :variants (team fonts), :clone (media id -> promise of
  the id of its copy in the target file), :dry-run?}. `req`: {:page-id
  :to-page :ids :mode :x :y}. Resolves to {:file (the file to save)
  :changes :result}."
  [{:keys [ctx source target linked src-linked variants clone dry-run?]} req]
  (let [plan    (move/plan (assoc req :source (file-lib source) :target (file-lib target)))
        src     (:src-page plan)
        dst     (:dst-page plan)
        base    {:mode (:mode plan)
                 :fileId (str (:id source))
                 :fromPageId (str (:id src))
                 :toFileId (str (:id target))
                 :toPageId (str (:id dst))
                 :toPageName (:name dst)}
        fonts   (fonts/team-fonts variants)]
    (if (= "move" (:mode plan))
      (let [libs  (into [(own-library source)] src-linked)
            state (edit/start (:id src) (:objects src) {:fonts fonts :libraries libs :file-id (:id source)})
            {:keys [changes src-state objects report]} (move/build-move (:id source) (:data source) plan state)]
        (p/let [src-state (if (seq (:reflow src-state)) (layout! ctx src-state variants) (p/resolved src-state))]
          {:file source
           :changes (into (vec changes) (:redo-changes (:changes src-state)))
           :result (merge base report
                          {:ids (into {} (map (fn [id] [(str id) (str id)])) (:ids plan))
                           :shapes (shape-summaries objects (:ids plan)
                                                    (library/asset-names (library/index libs)))})}))
      (let [objects (:objects src)
            needed  (if (:same-file? plan) #{} (move/media-ids objects (:ids plan)))
            present (if (:same-file? plan) #{} (move/used-media (:data target)))
            missing (vec (remove present needed))]
        (p/let [pairs (if dry-run?
                        (p/resolved [])
                        (p/all (map (fn [mid] (p/fmap (fn [new-id] [mid new-id]) (clone mid))) missing)))
                libs  (into [(own-library target)] linked)
                state (p/do (edit/start (:id dst) (:objects dst) {:fonts fonts :libraries libs :file-id (:id target)}))
                built (p/do (move/build-copy plan {:source (file-lib source)
                                                   :target (file-lib target)
                                                   :libraries (by-id libs)
                                                   :src-libs (by-id (into [(own-library source)] src-linked))
                                                   :media (into {} pairs)}
                                             state))
                state (layout! ctx (:state built) variants)]
          (let [objects' (edit/objects state)]
            {:file target
             :changes (:redo-changes (:changes state))
             :result (merge base (:report built)
                            {:ids (into {} (map (fn [[o n]] [(str o) (str n)])) (:ids-map built))
                             :shapes (shape-summaries objects' (:top-ids built)
                                                      (library/asset-names (library/index libs)))}
                            (when (seq needed)
                              {:media (d/without-nils
                                       {:cloned (when-not dry-run? (count missing))
                                        :toClone (when dry-run? (count missing))
                                        :reused (count (filter present needed))})}))}))))))

(defn- same-team!
  [ctx file-id to-file-id]
  (p/let [[a b] (p/all [(rpc ctx :get-team {:file-id file-id}) (rpc ctx :get-team {:file-id to-file-id})])]
    (when (not= (:id a) (:id b))
      (throw (ex-info "toFileId must be a file of the same team (fonts, images and libraries belong to a team)"
                      {:type :invalid-op})))))

(defn move-shapes
  "JS entry. Moves shapes (usually boards, with their children) to another
  page of the same file, or copies them to a page of the same or another
  file of the team, in one update-file (see `penpot-mcp.move`).

  opts: {baseUri, token, fileId, pageId?, ids, toPageId (id or name),
         toFileId?, mode: 'move'|'copy', x?, y?, dryRun?}
  Resolves to {mode, ids: {old: new}, shapes, …report, changes, revn, saved}."
  [^js opts]
  (guard
   #(let [ctx        (->ctx opts)
          file-id    (->uuid (.-fileId opts))
          to-file-id (or (some-> (.-toFileId opts) ->uuid) file-id)
          same-file? (= file-id to-file-id)
          dry-run?   (boolean (.-dryRun opts))]
      (reset-on-error
       (p/let [_          (ensure-module!)
               [file variants] (p/all [(rpc ctx :get-file {:id file-id}) (font-variants! ctx to-file-id)])
               target     (if same-file? file (rpc ctx :get-file {:id to-file-id}))
               _          (when-not same-file? (same-team! ctx file-id to-file-id))
               linked     (linked-libraries! ctx to-file-id)
               src-linked (if same-file? linked (linked-libraries! ctx file-id))
               {:keys [file changes result]}
               (move-shapes* {:ctx ctx :source file :target target :linked linked :src-linked src-linked
                              :variants variants :dry-run? dry-run?
                              :clone (fn [mid]
                                       (->> (rpc ctx :clone-file-media-object {:file-id to-file-id :id mid :is-local true})
                                            (p/fmap :id)
                                            (p/merr (fn [cause]
                                                      (p/rejected (ex-info (str "Could not copy image " mid " to the target file: "
                                                                                (ex-message cause))
                                                                           {:type :invalid-op}))))))}
                             {:page-id (.-pageId opts)
                              :to-page (.-toPageId opts)
                              :ids (js->clj (.-ids opts))
                              :mode (.-mode opts)
                              :x (.-x opts)
                              :y (.-y opts)})
               saved      (save-changes! ctx file changes dry-run? (assoc result :changes (count changes)))]
         (->js saved))))))

;; --- design tokens

(defn- tokens-source-data!
  "Data of the file the tokens of `file` come from: itself, or the library
  set as its tokens source."
  [ctx file]
  (let [src (cfo/get-tokens-source (:data file))]
    (if (or (nil? src) (= src (:id file)))
      (p/resolved (:data file))
      (p/fmap :data (rpc ctx :get-file {:id src})))))

(defn get-tokens
  "JS entry. Themes, sets and tokens of a file (with what each token value
  resolves to).

  opts: {baseUri, token, fileId, set?, type?, search?, limit=200}"
  [^js opts]
  (guard
   #(let [ctx (->ctx opts)]
      (p/let [file   (rpc ctx :get-file {:id (->uuid (.-fileId opts))})
              source (tokens-source-data! ctx file)]
        (let [src (cfo/get-tokens-source (:data file))]
          (->js (cond-> (if (= "dtcg" (some-> (.-format opts) str str/lower))
                          {:format "dtcg" :json (or (tokens/export-dtcg (:data file) source) {})}
                          (tokens/describe (:data file) source {:set (.-set opts)
                                                                :type (.-type opts)
                                                                :search (.-search opts)
                                                                :limit (.-limit opts)}))
                  (and src (not= src (:id file)))
                  (assoc :tokensSource {:fileId (str src) :editable false}))))))))

(defn- propagate-tokens!
  "Changes that apply again the tokens whose value changed (an edit, a theme
  or set switch) to the shapes of `file` using them, page by page, with the
  layout pass — like the Tokens panel does. `before`/`after`: the file data
  around the token changes. Resolves to {:changes [...] :shapes n}."
  [ctx file before after variants]
  (let [old     (tokens/active-tokens before before)
        new     (tokens/active-tokens after after)
        changed (tokens/changed-tokens old new)]
    (if (empty? changed)
      (p/resolved {:changes [] :shapes 0})
      (reduce (fn [prev page-id]
                (p/mcat (fn [acc]
                          (let [objects (get-in after [:pages-index page-id :objects])
                                ops     (tokens/propagation-ops objects new changed)]
                            (if (empty? ops)
                              (p/resolved acc)
                              (p/fmap (fn [state]
                                        (-> acc
                                            (update :changes into (:redo-changes (:changes state)))
                                            (update :shapes + (count ops))))
                                      (edit-objects! ctx page-id objects ops variants
                                                     {:libraries [(assoc (own-library file) :data after)]
                                                      :file-id (:id file)
                                                      :tokens new})))))
                        prev))
              (p/resolved {:changes [] :shapes 0})
              (:pages after)))))

(defn edit-tokens
  "JS entry. Adds, updates and deletes token sets, themes and tokens of a
  file, and activates themes and sets, saved in one update-file. Shapes
  using a token whose value changed are updated too.

  opts: {baseUri, token, fileId, ops, dryRun?}
  Resolves to {changes, created, shapesUpdated, themes, sets, revn, saved}."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))]
      (reset-on-error
       (p/let [[file variants] (p/all [(rpc ctx :get-file {:id file-id}) (font-variants! ctx file-id)])
               {:keys [changes data created imported]} (p/do (tokens/build-ops (:data file) (ops->clj (.-ops opts))))
               spread (propagate-tokens! ctx file (:data file) data variants)
               all    (into (vec changes) (:changes spread))
               saved  (save-changes! ctx file all (.-dryRun opts)
                                     (merge (d/without-nils
                                             {:changes (count all) :created created :shapesUpdated (:shapes spread)
                                              :imported imported})
                                            (select-keys (tokens/describe data data {:limit 0}) [:themes :sets])))]
         (->js saved))))))

;; --- previews with another token theme

(defn- themed-objects!
  "The objects of page `page-id` of `file` with `themes` active: shapes
  using tokens get the theme's values, laid out again (copies included).
  Nothing is saved."
  [ctx file page-id themes variants]
  (p/let [source (tokens-source-data! ctx file)
          data   (:data file)
          page   (or (get-in data [:pages-index page-id])
                     (throw (ex-info (str "Page " page-id " not found") {:type :invalid-op})))
          old    (p/do (tokens/active-tokens data source))
          new    (p/do (tokens/with-themes data source themes))
          ops    (p/do (tokens/propagation-ops (:objects page) new (tokens/changed-tokens old new) true))]
    (if (empty? ops)
      (:objects page)
      (p/fmap edit/objects
              (edit-objects! ctx page-id (:objects page) ops variants
                             {:libraries [(own-library file)] :file-id (:id file) :tokens new})))))

;; --- design audit

(defn- audit*
  [file linked source {:keys [page-id checks limit]}]
  (let [data   (:data file)
        pages  (if page-id
                 [(move/resolve-page data page-id "pageId" "the file")]
                 (keep #(get-in data [:pages-index %]) (:pages data)))
        assets (library/index (into [(own-library file)] linked))
        toks   (tokens/active-tokens data source)
        only   (some->> checks (map (comp str/lower str)) set)]
    (when-let [bad (seq (remove audit/checks only))]
      (throw (ex-info (str "Unknown checks: " (str/join ", " bad) ". Checks: " (str/join ", " (sort audit/checks)))
                      {:type :invalid-op})))
    (audit/run data pages assets toks {:only only :limit limit})))

(defn audit-design
  "JS entry. Design system checks over a file (all pages, or one): raw
  colors and text styles not linked to the library or tokens, spacing and
  radius off the token scale, text contrast (WCAG AA), components, layer
  names and structure, with the shapes concerned and how to fix them.

  opts: {baseUri, token, fileId, pageId?, checks?: [names], limit=60}"
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))]
      (p/let [file   (rpc ctx :get-file {:id file-id})
              linked (linked-libraries! ctx file-id)
              source (tokens-source-data! ctx file)]
        (->js (audit* file linked source {:page-id (.-pageId opts)
                                          :checks (some-> (.-checks opts) js->clj)
                                          :limit (.-limit opts)}))))))

(defn audit-offline
  "JS entry (tests). audit_design on an in-memory file. opts: {pageId?,
  checks?, libraries?}"
  [^js file ^js opts]
  (guard
   #(->js (audit* file (vec (some-> opts .-libraries)) (:data file)
                  {:page-id (some-> opts .-pageId)
                   :checks (some-> opts .-checks js->clj)
                   :limit (some-> opts .-limit)}))))

;; --- file versions (snapshots)

(defn- iso
  [v]
  (cond
    (instance? js/Date v) (.toISOString v)
    (some? v) (str v)))

(defn- snapshot-js
  [s]
  (d/without-nils
   {:id (str (:id s))
    :label (:label s)
    :createdAt (iso (:created-at s))
    :createdBy (:created-by s)
    :profileId (some-> (:profile-id s) str)
    :lockedBy (some-> (:locked-by s) str)
    :revn (:revn s)}))

(defn file-versions
  "JS entry. The versions (snapshots) of a file, as in the History panel:
  list them, save one, rename, delete, lock/unlock, or restore one (Penpot
  saves the current state as a version first).

  opts: {baseUri, token, fileId, action: list|create|rename|delete|restore|lock|unlock,
  id?, label?}"
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))
          action  (str/lower (str (or (.-action opts) "list")))
          id      (some-> (.-id opts) ->uuid)
          label   (.-label opts)
          need-id (fn [] (or id (throw (ex-info (str action " needs the version id (see action list)") {:type :invalid-op}))))]
      (p/let [result
              (case action
                "list"    (p/resolved nil)
                "create"  (rpc ctx :create-file-snapshot (cond-> {:file-id file-id} (some? label) (assoc :label (str label))))
                "rename"  (do (when (str/blank? label)
                                (throw (ex-info "rename needs label" {:type :invalid-op})))
                              (rpc ctx :update-file-snapshot {:id (need-id) :label (str label)}))
                "delete"  (rpc ctx :delete-file-snapshot {:id (need-id)})
                "restore" (rpc ctx :restore-file-snapshot {:file-id file-id :id (need-id)})
                "lock"    (rpc ctx :lock-file-snapshot {:id (need-id)})
                "unlock"  (rpc ctx :unlock-file-snapshot {:id (need-id)})
                (throw (ex-info (str "Unknown action " (pr-str action)
                                     ". Use list, create, rename, delete, restore, lock or unlock")
                                {:type :invalid-op})))
              versions (rpc ctx :get-file-snapshots {:file-id file-id})]
        (->js (d/without-nils
               {:action action
                :created (when (and (= action "create") (map? result)) (snapshot-js result))
                :versions (mapv snapshot-js versions)}))))))

;; --- comments

(defn- comment-js
  [c]
  (d/without-nils
   {:id (str (:id c))
    :owner (or (:owner-fullname c) (some-> (:owner-id c) str))
    :content (:content c)
    :createdAt (iso (:created-at c))}))

(defn comments
  "JS entry. Comment threads of a file: list them (with their comments),
  start one on a shape or at a position, reply, resolve, reopen, delete.

  opts: {baseUri, token, fileId, action: list|create|reply|resolve|reopen|delete,
  pageId?, shapeId?, x?, y?, content?, threadId?, includeResolved?, limit=30}"
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))
          action  (str/lower (str (or (.-action opts) "list")))
          thread  (some-> (.-threadId opts) ->uuid)
          content (.-content opts)
          need    (fn [v what] (or v (throw (ex-info (str action " needs " what) {:type :invalid-op}))))]
      (p/let [file   (rpc ctx :get-file {:id file-id})
              data   (:data file)
              _      (case action
                       "create"
                       (let [page-id (or (some-> (.-pageId opts) ->uuid) (first (:pages data)))
                             objects (get-in data [:pages-index page-id :objects])
                             shape   (some->> (.-shapeId opts) ->uuid (get objects))
                             _       (when (and (.-shapeId opts) (nil? shape))
                                       (throw (ex-info "shapeId not found on the page" {:type :invalid-op})))
                             frame   (cond
                                       (nil? shape) uuid/zero
                                       (cfh/root-frame? shape) (:id shape)
                                       :else (or (:id (cfh/get-root-frame objects (:id shape))) uuid/zero))
                             {:keys [x y width]} (:selrect shape)
                             pos     (if shape
                                       (gpt/point (+ x width) y)
                                       (gpt/point (or (.-x opts) 0) (or (.-y opts) 0)))]
                         (rpc ctx :create-comment-thread {:file-id file-id :page-id page-id :frame-id frame
                                                          :position pos :content (str (need content "content"))}))
                       "reply"   (rpc ctx :create-comment {:thread-id (need thread "threadId") :content (str (need content "content"))})
                       "resolve" (rpc ctx :update-comment-thread {:id (need thread "threadId") :is-resolved true})
                       "reopen"  (rpc ctx :update-comment-thread {:id (need thread "threadId") :is-resolved false})
                       "delete"  (rpc ctx :delete-comment-thread {:id (need thread "threadId")})
                       "list"    nil
                       (throw (ex-info (str "Unknown action " (pr-str action)
                                            ". Use list, create, reply, resolve, reopen or delete")
                                       {:type :invalid-op})))
              threads (rpc ctx :get-comment-threads {:file-id file-id})
              page-id (some-> (.-pageId opts) ->uuid)
              shown   (->> threads
                           (filter (fn [t] (or (nil? page-id) (= page-id (:page-id t)))))
                           (filter (fn [t] (or (.-includeResolved opts) (not (:is-resolved t)))))
                           (sort-by (comp - (fnil identity 0) :seqn))
                           (take (or (.-limit opts) 30))
                           (vec))
              all     (p/all (map (fn [t] (->> (rpc ctx :get-comments {:thread-id (:id t)})
                                               (p/merr (fn [_] (p/resolved [])))))
                                  shown))]
        (->js {:action action
               :threads (mapv (fn [t cs]
                                (let [objects (get-in data [:pages-index (:page-id t) :objects])]
                                  (d/without-nils
                                   {:id (str (:id t))
                                    :number (:seqn t)
                                    :pageId (str (:page-id t))
                                    :pageName (get-in data [:pages-index (:page-id t) :name])
                                    :board (some-> (get objects (:frame-id t)) :name)
                                    :position (some-> (:position t) (select-keys [:x :y]))
                                    :resolved (boolean (:is-resolved t))
                                    :comments (mapv comment-js cs)})))
                              shown all)
               :total (count threads)})))))

;; --- offline helpers for tests

(defn- file-pages-js
  "Pages of in-memory file `data` for tests: [{id, name, objects}]."
  [data]
  (->js (vec (for [page-id (:pages data)
                   :let [page (get-in data [:pages-index page-id])]]
               {:id page-id
                :name (:name page)
                :flows (vec (vals (:flows page)))
                :objects (into {}
                               (map (fn [[id shape]]
                                      [(str id) (dissoc shape :points :transform :transform-inverse :position-data
                                                        :svg-defs :svg-attrs)]))
                               (:objects page))}))))

(defn- components-js
  [data]
  (->js (into {}
              (map (fn [[id c]]
                     [(str id) (d/without-nils
                                {:id id
                                 :name (library/full-name c)
                                 :mainInstanceId (:main-instance-id c)
                                 :mainInstancePage (:main-instance-page c)
                                 :variantId (:variant-id c)
                                 :variantProperties (:variant-properties c)
                                 :annotation (:annotation c)
                                 :deleted (:deleted c)})]))
              (:components data))))

(defn edit-file-offline
  "JS entry (tests). edit_page on an in-memory file (from libraryOffline or
  an earlier call), with the update of the copies of changed mains.

  opts: {pageId? (id or name; default the first page), images?, libraries?
  (other in-memory files, linked), dryRun?}
  Resolves to {file, result, changes, pages, components}."
  [^js file ^js ops ^js opts]
  (guard
   #(let [ctx     {:images (js-images (some-> opts .-images))}
          data    (:data file)
          page    (some-> opts .-pageId)
          page-id (if page
                    (:id (move/resolve-page data page "pageId" "Page"))
                    (first (:pages data)))
          libs    (vec (some-> opts .-libraries))]
      (p/let [_ (ensure-module!)
              {:keys [changes result]} (edit-file! ctx file page-id (ops->clj ops) [] libs
                                                   (tokens/active-tokens data data))]
        ;; verify? true: the same schema checks as the backend
        (let [data (cfc/process-changes data (vec changes) true)]
          #js {:file (assoc file :data data)
               :result (->js result)
               :changes (->js (mapv (fn [c] (select-keys c [:type :id :page-id])) changes))
               :pages (file-pages-js data)
               :components (components-js data)})))))

(defn move-shapes-offline
  "JS entry (tests). move_shapes on in-memory files from libraryOffline.

  opts: {ids, toPage, pageId?, mode, x?, y?, target? (another in-memory
  file), media? ({old media id: new id}, for copies to another file),
  images? (bytes by media id, to measure and render)}
  Resolves to {file (the changed file), result, changes, valid, pages,
  components}."
  [^js source ^js opts]
  (guard
   #(let [target  (or (some-> opts .-target) source)
          ctx     {:images (js-images (some-> opts .-images))}
          mapping (into {} (map (fn [[k v]] [(uuid/parse k) (uuid/parse v)]))
                        (js->clj (or (some-> opts .-media) #js {})))]
      (->> (ensure-module!)
           (p/mcat (fn [_]
                     (move-shapes* {:ctx ctx :source source :target target :linked [] :src-linked []
                                    :variants [] :dry-run? false
                                    :clone (fn [mid] (p/resolved (get mapping mid (uuid/next))))}
                                   {:page-id (some-> opts .-pageId)
                                    :to-page (.-toPage opts)
                                    :ids (js->clj (.-ids opts))
                                    :mode (.-mode opts)
                                    :x (.-x opts)
                                    :y (.-y opts)})))
           (p/fmap (fn [{:keys [file changes result]}]
                     ;; verify? true: the same schema checks as the backend
                     (let [data (cfc/process-changes (:data file) (vec changes) true)]
                       #js {:file (assoc file :data data)
                            :result (->js result)
                            :changes (->js (mapv (fn [c] (select-keys c [:type :id :page-id])) changes))
                            :valid true
                            :pages (file-pages-js data)
                            :components (components-js data)})))))))

(defn render-file-offline
  "JS entry (tests). Renders shape `objectId` of an in-memory file (any page)
  to PNG bytes. opts: {images?, scale?}"
  [^js file object-id ^js opts]
  (guard
   #(let [id   (uuid/parse object-id)
          data (:data file)
          page (d/seek (fn [page] (contains? (:objects page) id))
                       (map (fn [pid] (get-in data [:pages-index pid])) (:pages data)))
          ctx  {:images (js-images (some-> opts .-images))}]
      (->> (ensure-module!)
           (p/mcat (fn [_] (render-scene! ctx (:objects page) [id] {:scale (or (some-> opts .-scale) 1) :format :png :variants []})))
           (p/fmap first)))))

(defn tokens-offline
  "JS entry (tests). edit_tokens `ops` on an in-memory file (with the
  propagation to shapes), then `pageOps` (edit ops, may apply tokens) on its
  first page, then get_tokens with `query` ({set, type, search}). Resolves to
  {file, changes, created, shapesUpdated, tokens, pages}."
  ([file ops] (tokens-offline file ops nil nil))
  ([file ops query] (tokens-offline file ops query nil))
  ([^js file ^js ops ^js query ^js page-ops]
   (guard
    #(p/let [_      (ensure-module!)
             {:keys [changes data created imported]} (p/do (tokens/build-ops (:data file) (ops->clj ops)))
             spread (propagate-tokens! {} file (:data file) data [])
             all    (into (vec changes) (:changes spread))
             ;; verify? true: the same schema checks as the backend
             data   (p/do (cfc/process-changes (:data file) all true))
             page-id (first (:pages data))
             state  (if (seq page-ops)
                      (edit-objects! {} page-id (get-in data [:pages-index page-id :objects]) (ops->clj page-ops) []
                                     {:libraries [(assoc (own-library file) :data data)]
                                      :file-id (:id file)
                                      :tokens (tokens/active-tokens data data)})
                      (p/resolved nil))
             data   (p/do (if state (cfc/process-changes data (vec (:redo-changes (:changes state))) true) data))]
       (let [q (js->clj (or query #js {}) :keywordize-keys true)]
         #js {:file (assoc file :data data)
              :changes (->js (mapv (fn [c] (select-keys c [:type])) all))
              :created (->js created)
              :imported (->js imported)
              :dtcg (->js (tokens/export-dtcg data data))
              :shapesUpdated (:shapes spread)
              :pageCreated (->js (some->> state :refs (into {} (map (fn [[ref id]] [ref (str id)])))))
              :tokens (->js (tokens/describe data data q))
              :pages (file-pages-js data)})))))

(defn pages-offline
  "JS entry (tests). Applies page `ops` to an in-memory file with `pages`
  ([{id, name, mainComponent?}]). Resolves to the edit-pages result plus the
  raw changes."
  [^js pages ^js ops]
  (guard
   #(let [pages (js->clj pages :keywordize-keys true)
          ids   (mapv (comp uuid/parse :id) pages)
          data  {:pages ids
                 :pages-index (into {}
                                    (map (fn [id {:keys [name mainComponent]}]
                                           [id {:id id :name name
                                                :objects (if mainComponent
                                                           {id {:id id :main-instance true}}
                                                           {})}])
                                         ids pages))}
          state (pages/build data (ops->clj ops))]
      (->js (assoc (pages/result state) :changes (:changes state))))))

(defn library-offline
  "JS entry (tests). Builds an in-memory library file: `libraryOps` (colors,
  typographies) first, then `ops` (edit ops on its page, e.g. boards using
  those assets, then makeComponent). Resolves to {library (opaque, for
  applyOpsOffline `libraries` and editLibraryOffline), created, summary}.
  opts: {name?, pages?: names of more (empty) pages, images?: bytes by media
  id for image fills, linkAssets?: the result of linkAssets ops}."
  ([ops library-ops] (library-offline ops library-ops nil))
  ([^js ops ^js library-ops ^js opts]
   (guard
    #(let [file-id (uuid/next)
           fname   (or (some-> opts .-name) "Library")
           page    (ctp/make-empty-page {:id (uuid/next) :name "Components"})
           extra   (mapv (fn [n] (ctp/make-empty-page {:id (uuid/next) :name n}))
                         (js->clj (or (some-> opts .-pages) #js [])))
           data0   {:id file-id
                    :pages (into [(:id page)] (map :id) extra)
                    :pages-index (into {(:id page) page} (map (juxt :id identity)) extra)
                    :components {} :colors {} :typographies {}}
           lib     (if (seq library-ops)
                     (library/build-ops data0 [] (ops->clj library-ops))
                     {:changes []})
           ;; verify? true: the same schema checks as the backend
           data1   (cfc/process-changes data0 (vec (:changes lib)) true)]
       (p/let [state (edit-objects! {:images (js-images (some-> opts .-images))}
                                    (:id page) (get-in data1 [:pages-index (:id page) :objects]) (ops->clj ops) []
                                    {:libraries [{:id file-id :name fname :data data1 :local? true}]
                                     :file-id file-id})
               data2 (cfc/process-changes data1 (vec (:redo-changes (:changes state))) true)
               file  {:id file-id :name fname :data data2}]
         #js {:library file
              :created (->js (into {} (map (fn [[ref id]] [ref (str id)])) (:refs state)))
              :linkAssets (->js (:link-reports state))
              :pages (file-pages-js data2)
              :components (components-js data2)
              :summary (->js (first (library/describe [file] nil 100)))})))))

(defn edit-library-offline
  "JS entry (tests). edit_library on an in-memory file from libraryOffline or
  applyOpsOffline: resolves to {library (updated), changes, objects}."
  [^js library ^js ops]
  (guard
   #(->> (ensure-module!)
         (p/mcat (fn [_] (edit-library* {} library ops [])))
         (p/fmap (fn [{:keys [changes data]}]
                   (let [file (assoc library :data data)]
                     #js {:library file
                          :changes (->js (mapv (fn [c] (select-keys c [:type :id :page-id])) changes))
                          :valid (do (cfc/process-changes (:data library) changes true) true)
                          :summary (->js (first (library/describe [file] nil 100)))
                          :objects (->js (into {}
                                               (for [[_ page] (:pages-index data)
                                                     [id shape] (:objects page)]
                                                 [(str id) (select-keys shape [:name :type :fills :content :selrect])])))}))))))

(defn file-transit
  "JS entry (tests). A get-file response, transit encoded like the backend's,
  for an in-memory file from libraryOffline: lets a fake backend serve it."
  [^js file ^js opts]
  (t/encode-str (cond-> {:id (:id file)
                         :name (:name file)
                         :revn (or (some-> opts .-revn) 0)
                         :vern 0
                         :data (:data file)}
                  (some-> opts .-projectId) (assoc :project-id (uuid/parse (.-projectId opts))))))

(defn image-info
  "JS entry (tests). {mtype, width, height} of encoded image bytes, or null."
  [bytes]
  (->js (media/info bytes)))

;; --- offline smoke test: no backend needed

(defn- demo-scene
  []
  (let [frame-id (uuid/next)
        rect-id  (uuid/next)
        ell-id   (uuid/next)
        frame    (cts/setup-shape {:id frame-id :type :frame :name "Demo"
                                   :x 0 :y 0 :width 320 :height 200
                                   :parent-id uuid/zero :frame-id uuid/zero
                                   :fills [{:fill-color "#F4F1EA" :fill-opacity 1}]
                                   :shapes [rect-id ell-id]})
        rect     (cts/setup-shape {:id rect-id :type :rect :name "Rect"
                                   :x 24 :y 24 :width 160 :height 96 :r1 12 :r2 12 :r3 12 :r4 12
                                   :parent-id frame-id :frame-id frame-id
                                   :fills [{:fill-color "#3366FF" :fill-opacity 1}]})
        ellipse  (cts/setup-shape {:id ell-id :type :circle :name "Circle"
                                   :x 200 :y 80 :width 96 :height 96
                                   :parent-id frame-id :frame-id frame-id
                                   :fills [{:fill-color "#E8593C" :fill-opacity 1}]})]
    {:frame-id frame-id
     :objects  {frame-id frame rect-id rect ell-id ellipse}}))

(defn render-demo
  "JS entry. Renders a synthetic frame (rect + circle) to PNG bytes without
  any Penpot backend. Used by the CI smoke test."
  []
  (->> (ensure-module!)
       (p/mcat (fn [_]
                 (let [{:keys [frame-id objects]} (demo-scene)]
                   (render-scene! {} objects [frame-id] {:scale 2 :format :png}))))
       (p/fmap first)))

;; --- JS-friendly RPC (decoded result converted to plain JS)

(defn rpc-js
  "JS entry. `rpc({baseUri, token}, 'get-profile', {...})` -> plain JS value."
  [^js ctx command ^js params]
  (guard
   #(->> (rpc (->ctx ctx)
              (keyword command)
              (js->clj (or params #js {}) :keywordize-keys true))
         (p/fmap ->js))))
