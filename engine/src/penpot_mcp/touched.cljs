;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Mirrors Penpot's frontend (MPL-2.0, Copyright (c) KALEIDOS SUBSIDIARY SL):
;; `check-delta` and `calculate-ignore-tree` from
;; app.main.data.workspace.modifiers.

(ns penpot-mcp.touched
  "Which geometry changes of component copies are free placement and must not
  mark the copy as touched (overridden).

  When a layout moves a component instance, every shape of the copy moves
  with its root: relative to the root nothing changed, so the workspace saves
  those geometry changes with `ignore-geometry` and the copy keeps following
  its main component. Without this, a copy dropped into a flex board would be
  saved as overridden everywhere."
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.uuid :as uuid]))

(defn- check-delta
  "True when the transformation of `shape` (a copy root or a shape in a copy)
  is free placement: for the root a move; for descendants nothing changed
  relative to the root."
  [shape root transformed-shape transformed-root]
  (let [is-root?    (and (some? root) (= (:id shape) (:id root)))
        center      (fn [shape] (grc/rect->center (:selrect shape)))
        rel-pos     (fn [shape root]
                      (when root
                        (-> (gpt/subtract (center shape) (center root))
                            (gpt/transform (:transform-inverse root (gmt/matrix))))))
        orientation (fn [shape root]
                      (let [t (:transform shape (gmt/matrix))]
                        (if is-root?
                          t
                          (gmt/multiply (:transform-inverse root (gmt/matrix)) t))))
        pos-before  (rel-pos shape root)
        pos-after   (rel-pos transformed-shape transformed-root)
        distance    (if (and pos-before pos-after)
                      (gpt/distance-vector pos-before pos-after)
                      (gpt/point 0 0))
        selrect     (:selrect shape)
        selrect'    (:selrect transformed-shape)]
    (and (< (:x distance) 1)
         (< (:y distance) 1)
         (mth/close? (:width selrect) (:width selrect'))
         (mth/close? (:height selrect) (:height selrect'))
         (gmt/close? (orientation shape root) (orientation transformed-shape transformed-root)))))

(defn ignore-tree
  "Shape id -> ignore-geometry? for the shapes of component copies affected
  by `modif-tree` (id -> {:modifiers}), as `pcb/update-shapes` takes it."
  [modif-tree objects]
  (letfn [(modifiers [id] (get-in modif-tree [id :modifiers]))
          (walk
            ([tree shape]
             (let [transformed (gsh/transform-shape shape (modifiers (:id shape)))
                   root        (if (:component-root shape)
                                 shape
                                 (ctn/get-component-shape objects shape {:allow-main? true}))
                   transformed-root (if (:component-root shape)
                                      transformed
                                      (gsh/transform-shape root (modifiers (:id root))))]
               (walk tree shape transformed root transformed-root)))
            ([tree shape root transformed-root]
             (walk tree shape (gsh/transform-shape shape (modifiers (:id shape))) root transformed-root))
            ([tree shape transformed root transformed-root]
             (let [tree (cond-> tree
                          (and (some? root) (ctk/in-component-copy? shape))
                          (assoc (:id shape) (check-delta shape root transformed transformed-root)))]
               (reduce (fn [tree child] (walk tree child root transformed-root))
                       tree
                       (keep (d/getf objects) (:shapes shape))))))]
    (reduce (fn [tree id]
              (if-let [shape (and (not= id uuid/zero) (get objects id))]
                (walk tree shape)
                tree))
            {}
            (keys modif-tree))))
