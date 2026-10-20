;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns penpot-mcp.engine
  "Browser-free Penpot engine for the MCP server.

  Runs Penpot's own code under Node — `common` for the data model and the
  render-wasm (Skia) module for text layout and rendering — reusing the
  headless pieces of Penpot's exporter (`app.wasm`, `app.wasm.serialize`)
  unchanged. Only what differs from the exporter lives here:

  - auth: the user's Penpot access token (`Authorization: Token ...`) instead of
    the exporter's internal management key + session token;
  - transport: base URI passed per call instead of process config;
  - output: bytes are returned instead of written to temp files.

  The JS API (see `shadow-cljs.edn` exports) is promise based and takes and
  returns plain JS values, so the TypeScript MCP server can call it directly."
  (:require
   ["undici" :as http]
   [app.common.data :as d]
   [app.common.fonts :as cfnt]
   ;; Required for side effects: transit read handlers and deftype impls the
   ;; RPC responses are decoded into.
   [app.common.geom.matrix]
   [app.common.geom.point]
   [app.common.geom.rect]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.types.fills.impl]
   [app.common.types.objects-map]
   [app.common.types.path.impl]
   [app.common.types.shape :as cts]
   [app.common.types.shape.images :as images]
   [app.common.uuid :as uuid]
   [app.wasm :as wasm]
   [app.wasm.serialize :as serialize]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(l/set-level! :warn)

;; --- module lifecycle

(defonce ^:private module* (atom nil))

(defn- ensure-module!
  []
  (or @module*
      (reset! module* (wasm/init!))))

(defn init
  "Loads the render-wasm module from `artifact-dir` (the folder holding
  render-wasm.js/.wasm). Safe to call more than once."
  [artifact-dir]
  (when artifact-dir
    (set! wasm/artifact-dir artifact-dir))
  (->> (ensure-module!)
       (p/fmap (constantly true))))

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
                 (p/resolved nil)))))

(defn rpc
  "Calls a backend RPC command and resolves to the decoded response. Rejects
  with `{:status :body}` on a non-200 answer."
  [{:keys [base-uri token]} command params]
  (let [uri (join-uri base-uri (str "api/rpc/command/" (name command)))]
    (->> (p/do (http/fetch uri #js {:method "POST"
                                   :headers (auth-headers token)
                                   :body (t/encode-str params)}))
         (p/mcat (fn [^js resp]
                   (->> (.text resp)
                        (p/mcat (fn [body]
                                  (if (= 200 (.-status resp))
                                    (p/resolved (t/decode-str body))
                                    (p/rejected (ex-info (str "rpc " (name command) " failed: HTTP " (.-status resp))
                                                         {:status (.-status resp)
                                                          :command command
                                                          :body body})))))))))))

;; --- fonts (same sources as the exporter, addressed via the public URI)

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

(defn- gfont-bytes
  [{:keys [base-uri]} ttf-url]
  (let [uri (cfnt/gstatic->proxy-url ttf-url (join-uri base-uri "internal/gfonts/font"))]
    (cached-bytes uri #(fetch-bytes uri nil))))

(defn- make-resolve-font
  [variants {:keys [base-uri token] :as ctx}]
  (fn [{:keys [id weight style]}]
    (let [font-uuid (family-uuid id)
          variant   (find-variant variants font-uuid weight style)]
      (cond
        (:ttf-file-id variant)
        (fetch-bytes (join-uri base-uri (str "assets/by-id/" (:ttf-file-id variant))) token)

        (= uuid/zero font-uuid)
        (let [uri (join-uri base-uri (str "fonts/" (cfnt/resolve-ttf-file weight style)))]
          (cached-bytes uri #(fetch-bytes uri nil)))

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

(defn- provision-images!
  [{:keys [base-uri token]} scene]
  (let [ids (remove wasm/image-cached? (images/scene-image-ids scene))]
    (->> ids
         (map (fn [image-id]
                (->> (fetch-bytes (join-uri base-uri (str "assets/by-file-media-id/" image-id)) token)
                     (p/fmap (fn [buf]
                               (if buf
                                 (wasm/store-image! image-id buf)
                                 (l/warn :hint "image unavailable" :media-id (str image-id))))))))
         (p/all))))

;; --- render

(defn- render-scene!
  "Loads `scene` (an objects map) into the WASM state, provisions fonts and
  images, and renders each id in `object-ids`. Resolves to a vector of bytes."
  [ctx scene object-ids {:keys [scale format variants]}]
  (serialize/serialize-scene! scene)
  (wasm/clear-fonts!)
  (->> (p/all [(provision-images! ctx scene)
               (provision-fallback-fonts! ctx scene)])
       (p/mcat (fn [_]
                 (wasm/provision-fonts! object-ids (make-resolve-font (or variants []) ctx))))
       (p/fmap (fn [_]
                 (doseq [shape (vals scene)
                         :when (= :text (:type shape))]
                   (wasm/update-text-layout! (:id shape)))
                 (mapv (fn [id]
                         (case format
                           :svg (wasm/render-shape-svg id scale)
                           :pdf (wasm/render-shape-pdf id)
                           (wasm/render-shape-raster id scale format)))
                       object-ids)))
       (p/merr (fn [cause]
                 ;; A panic can leave the instance aborted: rebuild next time.
                 (reset! module* nil)
                 (p/rejected cause)))))

(defn- ->uuid [v] (if (uuid? v) v (uuid/uuid v)))

(defn render-shape
  "JS entry. Renders one shape of a file to image bytes.

  opts: {baseUri, token, fileId, pageId, objectId, scale=1, format='png'}
  Resolves to a Uint8Array."
  [^js opts]
  (let [ctx       {:base-uri (.-baseUri opts) :token (.-token opts)}
        file-id   (->uuid (.-fileId opts))
        page-id   (->uuid (.-pageId opts))
        object-id (->uuid (.-objectId opts))
        scale     (or (.-scale opts) 1)
        format    (keyword (or (.-format opts) "png"))]
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
         (p/fmap first))))

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
  (->> (rpc {:base-uri (.-baseUri ctx) :token (.-token ctx)}
            (keyword command)
            (js->clj (or params #js {}) :keywordize-keys true))
       (p/fmap clj->js)))
