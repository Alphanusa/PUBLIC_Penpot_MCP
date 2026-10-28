;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns penpot-mcp.media
  "Type and natural size of an encoded image (PNG, JPEG, GIF, WebP, SVG),
  read from its header.

  Image fills need the media object's width, height and mime type, and the
  backend has no RPC that returns them for an id; `upload_image` returns
  them, but `edit_page` only gets the id. The engine fetches the image anyway
  to render it, so it reads them from the bytes."
  (:require
   [cuerdas.core :as str]))

(defn- u8 [^js b i] (aget b i))
(defn- be16 [b i] (+ (* 256 (u8 b i)) (u8 b (inc i))))
(defn- le16 [b i] (+ (u8 b i) (* 256 (u8 b (inc i)))))
(defn- le24 [b i] (+ (u8 b i) (* 256 (u8 b (inc i))) (* 65536 (u8 b (+ i 2)))))
(defn- be32 [b i] (+ (* 16777216 (u8 b i)) (* 65536 (u8 b (+ i 1))) (* 256 (u8 b (+ i 2))) (u8 b (+ i 3))))

(defn- ascii
  [^js b i n]
  (when (<= (+ i n) (.-length b))
    (apply str (map #(js/String.fromCharCode (u8 b (+ i %))) (range n)))))

(defn- png
  [^js b]
  (when (and (>= (.-length b) 24)
             (= 0x89 (u8 b 0))
             (= "PNG" (ascii b 1 3))
             (= "IHDR" (ascii b 12 4)))
    {:mtype "image/png" :width (be32 b 16) :height (be32 b 20)}))

(defn- gif
  [^js b]
  (when (and (>= (.-length b) 10) (= "GIF8" (ascii b 0 4)))
    {:mtype "image/gif" :width (le16 b 6) :height (le16 b 8)}))

(def ^:private jpeg-sof-markers
  ;; SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC)
  (disj (set (range 0xC0 0xD0)) 0xC4 0xC8 0xCC))

(defn- jpeg
  [^js b]
  (let [n (.-length b)]
    (when (and (>= n 4) (= 0xFF (u8 b 0)) (= 0xD8 (u8 b 1)))
      (loop [i 2]
        (when (< (+ i 8) n)
          (if (not= 0xFF (u8 b i))
            (recur (inc i))
            (let [m (u8 b (inc i))]
              (cond
                ;; fill byte
                (= 0xFF m) (recur (inc i))
                ;; markers without a length
                (or (= 0x01 m) (= 0xD8 m) (<= 0xD0 m 0xD7)) (recur (+ i 2))
                (contains? jpeg-sof-markers m)
                {:mtype "image/jpeg" :height (be16 b (+ i 5)) :width (be16 b (+ i 7))}
                :else (recur (+ i 2 (be16 b (+ i 2))))))))))))

(defn- webp
  [^js b]
  (when (and (>= (.-length b) 30)
             (= "RIFF" (ascii b 0 4))
             (= "WEBP" (ascii b 8 4)))
    (case (ascii b 12 4)
      "VP8 " {:mtype "image/webp"
              :width (bit-and (le16 b 26) 0x3fff)
              :height (bit-and (le16 b 28) 0x3fff)}
      "VP8L" (let [b0 (u8 b 21) b1 (u8 b 22) b2 (u8 b 23) b3 (u8 b 24)]
               {:mtype "image/webp"
                :width (inc (bit-or (bit-shift-left (bit-and b1 0x3f) 8) b0))
                :height (inc (bit-or (bit-shift-left (bit-and b3 0x0f) 10)
                                     (bit-shift-left b2 2)
                                     (bit-shift-right (bit-and b1 0xc0) 6)))})
      "VP8X" {:mtype "image/webp" :width (inc (le24 b 24)) :height (inc (le24 b 27))}
      nil)))

(defn- svg-length
  "px length of an SVG width/height attribute, nil for % or other units."
  [s]
  (when-let [[_ n] (some->> s (re-matches #"^\s*([0-9]*\.?[0-9]+)\s*(px)?\s*$"))]
    (js/parseFloat n)))

(defn- svg
  [^js b]
  (let [text (.toString (js/Buffer.from (.subarray b 0 (min 8192 (.-length b)))) "utf8")]
    (when-let [tag (re-find #"<svg[\s>][^>]*>" text)]
      (let [attr    (fn [a]
                      (second (re-find (re-pattern (str "\\s" a "\\s*=\\s*[\"']([^\"']*)[\"']")) tag)))
            [_ _ vw vh] (some->> (attr "viewBox") str/trim (#(str/split % #"[\s,]+")) (mapv js/parseFloat))
            w       (or (svg-length (attr "width")) vw)
            h       (or (svg-length (attr "height")) vh)
            ok?     #(and (number? %) (js/isFinite %) (pos? %))]
        {:mtype "image/svg+xml"
         :width (js/Math.round (if (ok? w) w 100))
         :height (js/Math.round (if (ok? h) h 100))}))))

(defn info
  "{:mtype :width :height} of encoded image `bytes` (ArrayBuffer, Uint8Array
  or Buffer), or nil when the format is not recognized."
  [bytes]
  (when bytes
    (let [b (if (instance? js/Uint8Array bytes) bytes (js/Uint8Array. bytes))]
      (or (png b) (jpeg b) (gif b) (webp b) (svg b)))))
