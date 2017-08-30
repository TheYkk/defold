(ns editor.code.data
  (:require [clojure.string :as string]
            [editor.code.syntax :as syntax]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:private clipboard-mime-type-plain-text "text/plain")
(def ^:private clipboard-mime-type-multi-selection "application/x-string-vector")

(defprotocol Clipboard
  (has-content? [this mime-type])
  (get-content [this mime-type])
  (set-content! [this representation-by-mime-type]))

(defprotocol GlyphMetrics
  (ascent [this])
  (line-height [this])
  (string-width [this ^String text]))

(defn- identifier-character-at-index? [line ^long index]
  (if-some [^char character (get line index)]
    (case character
      (\- \_) true
      (Character/isJavaLetterOrDigit character))
    false))

(defn- whitespace-character-at-index? [line ^long index]
  (if-some [^char character (get line index)]
    (Character/isWhitespace character)
    false))

(defrecord Cursor [^long row ^long col]
  Comparable
  (compareTo [_this other]
    (let [row-comparison (compare row (.row ^Cursor other))]
      (if (zero? row-comparison)
        (compare col (.col ^Cursor other))
        row-comparison))))

(defmethod print-method Cursor [^Cursor c, ^java.io.Writer w]
  (.write w (pr-str {:Cursor [(.row c) (.col c)]})))

(defn- cursor-in-leading-whitespace? [lines ^Cursor cursor]
  (let [line (lines (.row cursor))]
    (every? (partial whitespace-character-at-index? line)
            (range 0 (.col cursor)))))

(declare cursor-range-start cursor-range-end)

(defrecord CursorRange [^Cursor from ^Cursor to]
  Comparable
  (compareTo [this other]
    (let [start-comparison (compare (cursor-range-start this) (cursor-range-start other))]
      (if (zero? start-comparison)
        (let [end-comparison (compare (cursor-range-end this) (cursor-range-end other))]
          (if (zero? end-comparison)
            (let [from-comparison (compare from (.from ^CursorRange other))]
              (if (zero? from-comparison)
                (compare to (.to ^CursorRange other))
                from-comparison))
            end-comparison))
        start-comparison))))

(defmethod print-method CursorRange [^CursorRange cr, ^java.io.Writer w]
  (let [^Cursor from (.from cr)
        ^Cursor to (.to cr)]
    (.write w (pr-str {:CursorRange [[(.row from) (.col from)]
                                     [(.row to) (.col to)]]}))))

(def default-cursor (->Cursor 0 0))
(def default-cursor-range (->CursorRange default-cursor default-cursor))

(defn document-end-cursor
  ^Cursor [lines]
  (let [row (max 0 (dec (count lines)))
        col (count (get lines row))]
    (->Cursor row col)))

(defn Cursor->CursorRange
  ^CursorRange [^Cursor cursor]
  (->CursorRange cursor cursor))

(defn CursorRange->Cursor
  ^Cursor [^CursorRange cursor-range]
  (.to cursor-range))

(defn- min-cursor
  ^Cursor [^Cursor a ^Cursor b]
  (if (neg? (compare a b)) a b))

(defn- max-cursor
  ^Cursor [^Cursor a ^Cursor b]
  (if (pos? (compare a b)) a b))

(defn cursor-range-start
  ^Cursor [^CursorRange cursor-range]
  (min-cursor (.from cursor-range) (.to cursor-range)))

(defn cursor-range-end
  ^Cursor [^CursorRange cursor-range]
  (max-cursor (.from cursor-range) (.to cursor-range)))

(defn cursor-range-start-range
  ^CursorRange [^CursorRange cursor-range]
  (let [start (cursor-range-start cursor-range)]
    (->CursorRange start start)))

(defn cursor-range-end-range
  ^CursorRange [^CursorRange cursor-range]
  (let [end (cursor-range-end cursor-range)]
    (->CursorRange end end)))

(defn cursor-range-equals? [^CursorRange a ^CursorRange b]
  (and (= (cursor-range-start a) (cursor-range-start b))
       (= (cursor-range-end a) (cursor-range-end b))))

(defn cursor-range-empty? [^CursorRange cursor-range]
  (= (.from cursor-range) (.to cursor-range)))

(defn cursor-range-multi-line? [^CursorRange cursor-range]
  (not (zero? (- (.row ^Cursor (.from cursor-range))
                 (.row ^Cursor (.to cursor-range))))))

(defn cursor-range-ends-before-row? [^long row ^CursorRange cursor-range]
  (> row (.row (cursor-range-end cursor-range))))

(defn cursor-range-starts-before-row? [^long row ^CursorRange cursor-range]
  (> row (.row (cursor-range-start cursor-range))))

(defn- cursor-range-midpoint-follows? [^CursorRange cursor-range ^Cursor cursor]
  (let [start (cursor-range-start cursor-range)
        end (cursor-range-end cursor-range)
        mid-row (Math/floor (/ (+ (.row start) (.row end)) 2.0))]
    (cond (< (.row cursor) mid-row)
          true

          (> (.row cursor) mid-row)
          false

          (= (.row start) (.row end) (.row cursor))
          (< (.col cursor) (Math/floor (/ (+ (.col start) (.col end)) 2.0)))

          :else
          false)))

(defrecord Rect [^double x ^double y ^double w ^double h])

(defn expand-rect [^Rect r ^double x ^double y]
  (->Rect (- (.x r) x)
          (- (.y r) y)
          (+ (.w r) (* 2.0 x))
          (+ (.h r) (* 2.0 y))))

(defn- outer-bounds
  ^Rect [^Rect a ^Rect b]
  (let [left   (min (.x a) (.x b))
        top    (min (.y a) (.y b))
        right  (max (+ (.x a) (.w a))
                    (+ (.x b) (.w b)))
        bottom (max (+ (.y a) (.h a))
                    (+ (.y b) (.h b)))
        width  (- right left)
        height (- bottom top)]
    (->Rect left top width height)))

(defrecord LayoutInfo [canvas
                       glyph
                       scroll-tab-y
                       ^double scroll-x
                       ^double scroll-y
                       ^double scroll-y-remainder
                       ^long drawn-line-count
                       ^long dropped-line-count])

(defn- scroll-tab-y-rect
  ^Rect [^Rect canvas-rect line-height source-line-count dropped-line-count scroll-y-remainder]
  (let [document-height (* ^double source-line-count ^double line-height)
        visible-height (.h canvas-rect)
        visible-ratio (/ visible-height document-height)]
    (when (< visible-ratio 1.0)
      (let [visible-top (- (* ^double dropped-line-count ^double line-height) ^double scroll-y-remainder)
            scroll-bar-width 14.0
            scroll-bar-height (- (.h canvas-rect) (.y canvas-rect))
            scroll-bar-left (- (+ (.x canvas-rect) (.w canvas-rect)) scroll-bar-width)
            scroll-bar-top (.y canvas-rect)
            scroll-tab-width 7.0
            scroll-tab-margin (- (/ scroll-bar-width 2.0) (/ scroll-tab-width 2.0))
            scroll-tab-max-height (- scroll-bar-height (* scroll-tab-margin 2.0))
            scroll-tab-height (* scroll-tab-max-height (min 1.0 visible-ratio))
            scroll-tab-left (+ scroll-bar-left scroll-tab-margin)
            scroll-tab-top (+ scroll-bar-top scroll-tab-margin (* (/ visible-top document-height) scroll-tab-max-height))]
        (->Rect scroll-tab-left scroll-tab-top scroll-tab-width scroll-tab-height)))))

(defn layout-info
  ^LayoutInfo [canvas-width canvas-height scroll-x scroll-y source-line-count glyph-metrics]
  (let [^double line-height (line-height glyph-metrics)
        drawn-line-count (long (Math/ceil (/ ^double canvas-height line-height)))
        dropped-line-count (long (/ ^double scroll-y (- line-height)))
        scroll-y-remainder (double (mod ^double scroll-y (- line-height)))
        ^double max-line-number-width (string-width glyph-metrics (str source-line-count))
        gutter-width (Math/ceil (+ (* 2.0 line-height) max-line-number-width))
        canvas-rect (->Rect gutter-width 0.0 (- ^double canvas-width gutter-width) canvas-height)
        scroll-tab-y-rect (scroll-tab-y-rect canvas-rect line-height source-line-count dropped-line-count scroll-y-remainder)]
    (->LayoutInfo canvas-rect
                  glyph-metrics
                  scroll-tab-y-rect
                  scroll-x
                  scroll-y
                  scroll-y-remainder
                  drawn-line-count
                  dropped-line-count)))

(defn row->y
  ^double [^LayoutInfo layout ^long row]
  (Math/rint (+ (.scroll-y-remainder layout)
                (* ^double (line-height (.glyph layout))
                   (- row (.dropped-line-count layout))))))

(defn y->row
  ^long [^LayoutInfo layout ^double y]
  (+ (.dropped-line-count layout)
     (long (/ (- y (.scroll-y-remainder layout))
              ^double (line-height (.glyph layout))))))

(defn col->x
  ^double [^LayoutInfo layout ^long col ^String line]
  (Math/rint (+ (.x ^Rect (.canvas layout))
                (.scroll-x layout)
                ^double (string-width (.glyph layout) (subs line 0 col)))))

(defn x->col
  ^long [^LayoutInfo layout ^double x ^String line]
  (let [line-x (- x
                  (.x ^Rect (.canvas layout))
                  (.scroll-x layout))
        line-length (count line)
        glyph-metrics (.glyph layout)]
    (loop [col 0
           start-x 0.0]
      (if (<= line-length col)
        col
        (let [next-col (inc col)
              glyph (subs line col next-col)
              ^double width (string-width glyph-metrics glyph)
              end-x (+ start-x width)]
          (if (or (<= end-x line-x) (zero? width))
            (recur next-col end-x)
            (max 0 (+ col (long (+ 0.5 (/ (- line-x start-x) (- end-x start-x))))))))))))

(defn adjust-row
  ^long [lines ^long row]
  (max 0 (min row (dec (count lines)))))

(defn adjust-col
  ^long [lines ^long adjusted-row ^long col]
  (max 0 (min col (count (lines adjusted-row)))))

(defn adjust-cursor
  ^Cursor [lines ^Cursor cursor]
  (let [adjusted-row (adjust-row lines (.row cursor))
        adjusted-col (cond
                       (neg? (.row cursor))
                       0

                       (>= (.row cursor) (count lines))
                       (count (lines (dec (count lines))))

                       :else
                       (adjust-col lines adjusted-row (.col cursor)))]
    (->Cursor adjusted-row adjusted-col)))

(defn adjust-cursor-range
  ^CursorRange [lines ^CursorRange cursor-range]
  (->CursorRange (adjust-cursor lines (.from cursor-range))
                 (adjust-cursor lines (.to cursor-range))))

(defn canvas->cursor
  ^Cursor [^LayoutInfo layout lines x y]
  (let [row (y->row layout y)
        line (get lines (adjust-row lines row))
        col (x->col layout x line)]
    (->Cursor row col)))

(defn- peek! [coll]
  (get coll (dec (count coll))))

(defrecord Subsequence [^String first-line middle-lines ^String last-line])

(defn- empty-subsequence? [subsequence]
  (and (empty? (:first-line subsequence))
       (empty? (:middle-lines subsequence))
       (empty? (:last-line subsequence))))

(defn lines->subsequence
  ^Subsequence [lines]
  (let [line-count (count lines)
        first-line (first lines)
        middle-lines (when (< 2 line-count) (subvec lines 1 (dec line-count)))
        last-line (when (< 1 line-count) (peek lines))]
    (->Subsequence first-line middle-lines last-line)))

(defn subsequence->lines
  [subsequence]
  (let [last-line (:last-line subsequence)]
    (cond-> (into [(:first-line subsequence)]
                  (:middle-lines subsequence))
            (some? last-line) (conj last-line))))

(defn cursor-range-subsequence
  ^Subsequence [lines cursor-range]
  (let [start (adjust-cursor lines (cursor-range-start cursor-range))
        end (adjust-cursor lines (cursor-range-end cursor-range))]
    (if (= (.row start) (.row end))
      (->Subsequence (subs (lines (.row start)) (.col start) (.col end)) nil nil)
      (let [line-count (inc (- (.row end) (.row start)))
            start-line (lines (.row start))
            end-line (lines (.row end))]
        (->Subsequence (subs start-line (.col start) (count start-line))
                       (when (< 2 line-count) (subvec lines (inc (.row start)) (.row end)))
                       (when (< 1 line-count) (subs end-line 0 (.col end))))))))

(defn- append-subsequence! [runs subsequence]
  (let [runs' (if-some [joined-with-prev-line (not-empty (:first-line subsequence))]
                (let [prev-run (peek! runs)
                      prev-line (peek prev-run)]
                  (assert (= 1 (count prev-run)))
                  (assert (string? prev-line))
                  (conj! (pop! runs) (vector (str prev-line joined-with-prev-line))))
                runs)
        runs'' (if-some [middle-lines (not-empty (:middle-lines subsequence))]
                 (conj! runs' middle-lines)
                 runs')
        runs''' (if-some [last-line (:last-line subsequence)]
                  (conj! runs'' (vector last-line))
                  runs'')]
    runs'''))

(defn- prev-occurrence-search-cursor
  ^Cursor [cursor-ranges]
  (or (::prev-occurrence-search-cursor (meta cursor-ranges))
      (cursor-range-start (first cursor-ranges))))

(defn- with-prev-occurrence-search-cursor [cursor-ranges cursor]
  (assert (instance? Cursor cursor))
  (with-meta cursor-ranges {::prev-occurrence-search-cursor cursor}))

(defn- next-occurrence-search-cursor
  ^Cursor [cursor-ranges]
  (or (::next-occurrence-search-cursor (meta cursor-ranges))
      (cursor-range-end (peek cursor-ranges))))

(defn- with-next-occurrence-search-cursor [cursor-ranges cursor]
  (assert (instance? Cursor cursor))
  (with-meta cursor-ranges {::next-occurrence-search-cursor cursor}))

(defn find-prev-occurrence
  ^CursorRange [haystack-lines needle-lines ^Cursor from-cursor]
  (loop [cursor from-cursor
         needles needle-lines
         ^CursorRange matched-range nil]
    (if-some [needle (peek needles)]
      (if-some [haystack (get haystack-lines (.row cursor))]
        (let [more-needles? (some? (next needles))
              must-match-start? more-needles?
              must-match-end? (and (not more-needles?) (some? (next needle-lines)))
              needle-length (count needle)
              matched-range' (when-some [matched-col (cond
                                                       must-match-start?
                                                       (when (string/starts-with? haystack needle)
                                                         0)

                                                       must-match-end?
                                                       (when (string/ends-with? haystack needle)
                                                         (- (count haystack) needle-length))

                                                       :else
                                                       (string/last-index-of haystack needle (- (.col cursor) needle-length)))]
                               (let [matched-from (->Cursor (.row cursor) matched-col)
                                     matched-to (if (some? matched-range) (.to matched-range) (->Cursor (.row cursor) (+ ^long matched-col needle-length)))]
                                 (->CursorRange matched-from matched-to)))
              needles' (if (some? matched-range')
                         (pop needles)
                         needle-lines)
              cursor' (if (or (some? matched-range') (nil? matched-range))
                        (let [row (dec (.row cursor))
                              col (count (get haystack-lines row))]
                          (->Cursor row col))
                        cursor)]
          (recur cursor' needles' matched-range'))
        nil)
      matched-range)))

(defn find-next-occurrence
  ^CursorRange [haystack-lines needle-lines ^Cursor from-cursor]
  (loop [cursor from-cursor
         needles needle-lines
         ^CursorRange matched-range nil]
    (if-some [needle (first needles)]
      (if-some [haystack (get haystack-lines (.row cursor))]
        (let [more-needles? (some? (next needles))
              must-match-start? (and (not more-needles?) (some? (next needle-lines)))
              must-match-end? more-needles?
              matched-range' (when-some [matched-col (cond
                                                       must-match-start?
                                                       (when (string/starts-with? haystack needle)
                                                         0)

                                                       must-match-end?
                                                       (when (string/ends-with? haystack needle)
                                                         (- (count haystack) (count needle)))

                                                       :else
                                                       (string/index-of haystack needle (.col cursor)))]
                               (let [matched-from (if (some? matched-range) (.from matched-range) (->Cursor (.row cursor) matched-col))
                                     matched-to (->Cursor (.row cursor) (+ ^int matched-col (count needle)))]
                                 (->CursorRange matched-from matched-to)))
              needles' (if (some? matched-range')
                         (next needles)
                         needle-lines)
              cursor' (if (or (some? matched-range') (nil? matched-range))
                        (->Cursor (inc (.row cursor)) 0)
                        cursor)]
          (recur cursor' needles' matched-range'))
        nil)
      matched-range)))

(defn- word-cursor-range-at-cursor
  ^CursorRange [lines ^Cursor cursor]
  (let [line (lines (.row cursor))
        line-length (count line)
        on-whitespace? (and (whitespace-character-at-index? line (.col cursor))
                            (or (zero? (.col cursor))
                                (whitespace-character-at-index? line (dec (.col cursor)))))
        same-word? (if on-whitespace?
                     (partial whitespace-character-at-index? line)
                     (partial identifier-character-at-index? line))
        col (if (or (= line-length (.col cursor))
                    (not (same-word? (.col cursor))))
              (max 0 (dec (.col cursor)))
              (.col cursor))
        start-col (inc (or (long (first (drop-while same-word? (iterate dec col)))) -1))
        end-col (or (first (drop-while same-word? (iterate inc col))) line-length)]
    (->CursorRange (->Cursor (.row cursor) start-col)
                   (->Cursor (.row cursor) end-col))))

(defn word-cursor-range? [lines ^CursorRange adjusted-cursor-range]
  (let [cursor (cursor-range-start adjusted-cursor-range)
        word-cursor-range (word-cursor-range-at-cursor lines cursor)]
    (when (and (cursor-range-equals? word-cursor-range adjusted-cursor-range)
               (not (Character/isWhitespace (.charAt ^String (lines (.row cursor)) (.col cursor)))))
      adjusted-cursor-range)))

(defn selected-word-cursor-range
  ^CursorRange [lines cursor-ranges]
  (when-some [cursor-range (first cursor-ranges)]
    (let [word-cursor-range (adjust-cursor-range lines cursor-range)]
      (when (word-cursor-range? lines word-cursor-range)
        (let [word-subsequence (cursor-range-subsequence lines word-cursor-range)]
          (when (every? #(and (not (cursor-range-multi-line? %))
                              (= word-subsequence (cursor-range-subsequence lines %)))
                        (take 64 cursor-ranges))
            word-cursor-range))))))

(defn- cursor-framing-rect
  ^Rect [^LayoutInfo layout lines ^Cursor adjusted-cursor]
  (let [line (lines (.row adjusted-cursor))
        left (col->x layout (.col adjusted-cursor) line)
        top (row->y layout (.row adjusted-cursor))]
    (->Rect left top 1.0 (line-height (.glyph layout)))))

(defn cursor-rect
  ^Rect [^LayoutInfo layout lines ^Cursor adjusted-cursor]
  (let [line (lines (.row adjusted-cursor))
        left (- (col->x layout (.col adjusted-cursor) line) 0.5)
        top (- (row->y layout (.row adjusted-cursor)) 0.5)]
    (->Rect left top 1.0 (inc ^double (line-height (.glyph layout))))))

(defn cursor-range-rects
  [^LayoutInfo layout lines ^CursorRange adjusted-cursor-range]
  (let [canvas ^Rect (.canvas layout)
        ^double line-height (line-height (.glyph layout))
        col-to-col-rect (fn [^long row ^long start-col ^long end-col]
                          (let [line (lines row)
                                top (row->y layout row)
                                left (col->x layout start-col line)
                                right (col->x layout end-col line)
                                width (- right left)]
                            (->Rect left top width line-height)))
        col-to-edge-rect (fn [^long row ^long col]
                           (let [line (lines row)
                                 top (row->y layout row)
                                 left (col->x layout col line)
                                 width (- (+ (.x canvas) (.w canvas)) left)]
                             (->Rect left top width line-height)))
        edge-to-col-rect (fn [^long row ^long col]
                           (let [line (lines row)
                                 top (row->y layout row)
                                 left (col->x layout 0 line)
                                 right (col->x layout col line)
                                 width (- right left)]
                             (->Rect left top width line-height)))
        edge-to-edge-rect (fn [^long start-row ^long end-row]
                            (let [top (row->y layout start-row)
                                  left (col->x layout 0 "")
                                  width (- (+ (.x canvas) (.w canvas)) left)
                                  height (* line-height (inc (- end-row start-row)))]
                              (->Rect left top width height)))
        {start-row :row start-col :col} (cursor-range-start adjusted-cursor-range)
        {end-row :row end-col :col} (cursor-range-end adjusted-cursor-range)]
    (case (- ^long end-row ^long start-row)
      0 [(col-to-col-rect start-row start-col end-col)]
      1 [(col-to-edge-rect start-row start-col)
         (edge-to-col-rect end-row end-col)]
      [(col-to-edge-rect start-row start-col)
       (edge-to-edge-rect (inc ^long start-row) (dec ^long end-row))
       (edge-to-col-rect end-row end-col)])))

(defn limit-scroll-y
  ^double [^LayoutInfo layout lines ^double scroll-y]
  (let [line-count (count lines)
        ^double line-height (line-height (.glyph layout))
        document-height (* line-count line-height)
        canvas-height (.h ^Rect (.canvas layout))
        scroll-min (- canvas-height document-height)]
    (min 0.0 (max scroll-y scroll-min))))

(defn- scroll-center [_margin canvas-offset canvas-size target-offset target-size scroll-offset]
  (let [canvas-min (- ^double canvas-offset ^double scroll-offset)
        canvas-max (+ canvas-min ^double canvas-size)
        target-min (- ^double target-offset ^double scroll-offset)
        target-max (+ target-min ^double target-size)]
    (when (or (< target-min canvas-min) (> target-max canvas-max))
      (- ^double scroll-offset
         (- (/ ^double canvas-size 2.0))
         (+ ^double target-offset (/ ^double target-size 2.0))))))

(defn- scroll-shortest [margin canvas-offset canvas-size target-offset target-size scroll-offset]
  (let [canvas-min (- ^double canvas-offset ^double scroll-offset)
        canvas-max (+ canvas-min ^double canvas-size)
        target-min (- ^double target-offset ^double scroll-offset)
        target-max (+ target-min ^double target-size)]
    (when (or (< target-min canvas-min) (> target-max canvas-max))
      (let [scroll-min (+ ^double scroll-offset (- canvas-min target-min))
            scroll-max (+ ^double scroll-offset (- canvas-max target-max))]
        (if (< (Math/abs (- scroll-min ^double scroll-offset))
               (Math/abs (- scroll-max ^double scroll-offset)))
          (+ scroll-min ^double margin)
          (- scroll-max ^double margin))))))

(defn- scroll-to-rect [scroll-x-fn scroll-y-fn ^LayoutInfo layout lines ^Rect target-rect]
  (let [canvas-rect ^Rect (.canvas layout)
        margin-x (line-height (.glyph layout))
        margin-y 0.0
        scroll-x (or (scroll-x-fn margin-x (.x canvas-rect) (.w canvas-rect) (.x target-rect) (.w target-rect) (.scroll-x layout)) (.scroll-x layout))
        scroll-y (or (scroll-y-fn margin-y (.y canvas-rect) (.h canvas-rect) (.y target-rect) (.h target-rect) (.scroll-y layout)) (.scroll-y layout))
        scroll-y (limit-scroll-y layout lines scroll-y)]
    (cond-> nil
            (not= (.scroll-x layout) scroll-x) (assoc :scroll-x scroll-x)
            (not= (.scroll-y layout) scroll-y) (assoc :scroll-y scroll-y))))

(defn- scroll-to-cursor [scroll-x-fn scroll-y-fn ^LayoutInfo layout lines ^Cursor adjusted-cursor]
  (let [target-rect (cursor-framing-rect layout lines adjusted-cursor)]
    (scroll-to-rect scroll-x-fn scroll-y-fn layout lines target-rect)))

(defn- scroll-to-cursor-range [scroll-x-fn scroll-y-fn ^LayoutInfo layout lines ^CursorRange adjusted-cursor-range]
  (let [target-rect (reduce outer-bounds (cursor-range-rects layout lines adjusted-cursor-range))]
    (scroll-to-rect scroll-x-fn scroll-y-fn layout lines target-rect)))

(defn- scroll-distance
  ^double [^double from to]
  (if (some? to)
    (Math/abs (- ^double to from))
    0.0))

(defn- compare-scroll-severity
  ^long [^LayoutInfo layout a b]
  (let [y-comparison (compare (scroll-distance (.scroll-y layout) (:scroll-y a))
                              (scroll-distance (.scroll-y layout) (:scroll-y b)))]
    (if (zero? y-comparison)
      (compare (scroll-distance (.scroll-x layout) (:scroll-x a))
               (scroll-distance (.scroll-x layout) (:scroll-x b)))
      y-comparison)))

(defn- scroll-to-any-cursor [^LayoutInfo layout lines cursor-ranges]
  (reduce (fn [shortest-scroll scroll]
            (cond (nil? scroll) (reduced nil) ;; Early-out: No scroll required.
                  (neg? (compare-scroll-severity layout scroll shortest-scroll)) scroll
                  :else shortest-scroll))
          (sequence (comp (map CursorRange->Cursor)
                          (map (partial adjust-cursor lines))
                          (map (partial scroll-to-cursor scroll-shortest scroll-shortest layout lines)))
                    cursor-ranges)))

(defn- frame-cursor [{:keys [lines cursor-ranges] :as props} ^LayoutInfo layout]
  (assert (vector? lines))
  (assert (vector? cursor-ranges))
  (merge props (scroll-to-any-cursor layout lines cursor-ranges)))

(defn- ensure-syntax-info [syntax-info ^long end-row lines grammar]
  (let [valid-count (count syntax-info)]
    (if (<= end-row valid-count)
      syntax-info
      (loop [syntax-info' (transient syntax-info)
             lines (subvec lines valid-count end-row)
             contexts (or (first (peek syntax-info))
                          (list (syntax/make-context (:scope-name grammar) (:patterns grammar))))]
        (if-some [line (first lines)]
          (let [[contexts :as entry] (syntax/analyze contexts line)]
            (recur (conj! syntax-info' entry)
                   (next lines)
                   contexts))
          (persistent! syntax-info'))))))

(defn highlight-visible-syntax [lines syntax-info ^LayoutInfo layout grammar]
  (let [start-line (.dropped-line-count layout)
        end-line (min (count lines) (+ start-line (.drawn-line-count layout)))]
    (ensure-syntax-info syntax-info end-line lines grammar)))

(defn invalidate-syntax-info [syntax-info ^long invalidated-row ^long line-count]
  (into [] (subvec syntax-info 0 (min invalidated-row line-count (count syntax-info)))))

(defn- cursor-offset-unadjusted
  ^Cursor [^long row-count ^long col-count ^Cursor cursor]
  (if (and (zero? row-count)
           (zero? col-count))
    cursor
    (->Cursor (+ (.row cursor) row-count)
              (+ (.col cursor) col-count))))

(defn- cursor-offset-up
  ^Cursor [^long row-count lines ^Cursor cursor]
  (if (zero? row-count)
    cursor
    (let [row (adjust-row lines (.row cursor))
          col (.col cursor)
          new-row (adjust-row lines (- row row-count))
          new-col (if (zero? row) 0 col)]
      (->Cursor new-row new-col))))

(defn- cursor-offset-down
  ^Cursor [^long row-count lines ^Cursor cursor]
  (if (zero? row-count)
    cursor
    (let [row (adjust-row lines (.row cursor))
          col (.col cursor)
          last-row (dec (count lines))
          new-row (adjust-row lines (+ row row-count))
          new-col (if (= last-row row) (count (lines last-row)) col)]
      (->Cursor new-row new-col))))

(defn- text-start
  ^long [lines ^long row]
  (count (take-while #(Character/isWhitespace ^char %) (lines row))))

(defn- cursor-home
  ^Cursor [at-col-zero? lines ^Cursor cursor]
  (let [row (adjust-row lines (.row cursor))
        col (if at-col-zero? (text-start lines row) 0)]
    (->Cursor row col)))

(defn- cursor-start
  ^Cursor [lines ^Cursor cursor]
  (let [row (adjust-row lines (.row cursor))]
    (->Cursor row 0)))

(defn- cursor-end
  ^Cursor [lines ^Cursor cursor]
  (let [row (adjust-row lines (.row cursor))
        col (count (lines row))]
    (->Cursor row col)))

(defn- cursor-up
  ^Cursor [lines ^Cursor cursor]
  (cursor-offset-up 1 lines cursor))

(defn- cursor-down
  ^Cursor [lines ^Cursor cursor]
  (cursor-offset-down 1 lines cursor))

(defn- cursor-left
  ^Cursor [lines ^Cursor cursor]
  (let [adjusted (adjust-cursor lines cursor)
        row (.row adjusted)
        col (.col adjusted)
        new-col (dec col)]
    (if (neg? new-col)
      (let [new-row (max 0 (dec row))
            new-col (if (zero? row) 0 (count (lines new-row)))]
        (->Cursor new-row new-col))
      (->Cursor row new-col))))

(defn- cursor-right
  ^Cursor [lines ^Cursor cursor]
  (let [adjusted (adjust-cursor lines cursor)
        row (.row adjusted)
        col (.col adjusted)
        new-col (inc col)]
    (if (> new-col (count (lines row)))
      (let [last-row (dec (count lines))
            new-row (min (inc row) last-row)
            new-col (if (= last-row row) (count (lines last-row)) 0)]
        (->Cursor new-row new-col))
      (->Cursor row new-col))))

(defn- cursor-prev-word
  ^Cursor [lines ^Cursor cursor]
  (let [left-adjusted (cursor-left lines cursor)]
    (if (not= (.row cursor) (.row left-adjusted))
      left-adjusted
      (cursor-range-start (word-cursor-range-at-cursor lines left-adjusted)))))

(defn- cursor-next-word
  ^Cursor [lines ^Cursor cursor]
  (let [right-adjusted (cursor-right lines cursor)]
    (if (not= (.row cursor) (.row right-adjusted))
      right-adjusted
      (cursor-range-end (word-cursor-range-at-cursor lines right-adjusted)))))

(defn move-cursors [cursor-ranges move-fn lines]
  (into []
        (comp (map CursorRange->Cursor)
              (map (partial move-fn lines))
              (distinct)
              (map Cursor->CursorRange))
        cursor-ranges))

(defn- try-merge-cursor-range-pair [^CursorRange a ^CursorRange b]
  (let [as (cursor-range-start a)
        ae (cursor-range-end a)
        bs (cursor-range-start b)
        be (cursor-range-end b)
        as->bs (compare as bs)
        ae->be (compare ae be)]

    ;; => <=
    ;; => <A   <B
    ;; => <B   <A
    ;; A>  B>  <=
    ;; B>  A>  <=
    ;; A> <A    B> <B
    ;; A>  B>  <A  <B
    ;; A>  B>  <B  <A
    ;; B> <B    A> <A
    ;; B>  A>  <B  <A
    ;; B>  A>  <A  <B
    (cond
      (neg? as->bs)
      ;; A>  B>  <=
      ;; A> <A    B> <B
      ;; A>  B>  <A  <B
      ;; A>  B>  <B  <A
      (cond
        (neg? ae->be)
        ;; A> <A    B> <B
        ;; A>  B>  <A  <B
        (cond
          (pos? (compare ae bs))
          ;; A>  B>  <A  <B
          (if (neg? (compare (.from b) (.to b)))
            (->CursorRange as be)
            (->CursorRange be as))

          :else
          ;; A> <A    B> <B
          nil)

        (zero? ae->be)
        ;; A>  B>  <=
        a

        (pos? ae->be)
        ;; A>  B>  <B  <A
        a)

      (zero? as->bs)
      ;; => <=
      ;; => <A   <B
      ;; => <B   <A
      (cond
        (neg? ae->be)
        ;; => <A   <B
        b

        (zero? ae->be)
        ;; => <=
        a

        (pos? ae->be)
        ;; => <B   <A
        a)

      (pos? as->bs)
      ;; B>  A>  <=
      ;; B> <B    A> <A
      ;; B>  A>  <B  <A
      ;; B>  A>  <A  <B
      (cond
        (neg? ae->be)
        ;; B>  A>  <A  <B
        b

        (zero? ae->be)
        ;; B>  A>  <=
        b

        (pos? ae->be)
        ;; B> <B    A> <A
        ;; B>  A>  <B  <A
        (cond
          (pos? (compare be as))
          ;; B>  A>  <B  <A
          (if (neg? (compare (.from b) (.to b)))
            (->CursorRange bs ae)
            (->CursorRange ae bs))

          :else
          ;; B> <B    A> <A
          nil)))))

(defn- merge-cursor-ranges [ascending-cursor-ranges]
  (reduce (fn [merged-cursor-ranges ^CursorRange cursor-range]
            (if-some [[index updated-cursor-range]
                      (first (keep-indexed (fn [index merged-cursor-range]
                                             (when-some [updated-cursor-range (try-merge-cursor-range-pair merged-cursor-range cursor-range)]
                                               [index updated-cursor-range]))
                                           merged-cursor-ranges))]
              (assoc merged-cursor-ranges index updated-cursor-range)
              (conj merged-cursor-ranges cursor-range)))
          []
          ascending-cursor-ranges))

(def ^:private concat-cursor-ranges (comp merge-cursor-ranges sort concat))

(defn extend-selection [ascending-cursor-ranges move-fn lines]
  (merge-cursor-ranges
    (map (fn [^CursorRange cursor-range]
           (->CursorRange (.from cursor-range)
                          (move-fn lines (.to cursor-range))))
         ascending-cursor-ranges)))

(defn- move-type->move-fn [move-type]
  (case move-type
    :navigation move-cursors
    :selection extend-selection))

(defn- splice-lines [lines ascending-cursor-ranges-and-replacements]
  (into []
        cat
        (loop [start (->Cursor 0 0)
               rest ascending-cursor-ranges-and-replacements
               lines-seqs (transient [[""]])]
          (if-some [[cursor-range replacement-lines] (first rest)]
            (let [prior-end (adjust-cursor lines (cursor-range-start cursor-range))]
              (recur (cursor-range-end cursor-range)
                     (next rest)
                     (cond-> lines-seqs
                             (neg? (compare start prior-end)) (append-subsequence! (cursor-range-subsequence lines (->CursorRange start prior-end)))
                             (seq replacement-lines) (append-subsequence! (lines->subsequence replacement-lines)))))
            (let [end (->Cursor (dec (count lines)) (count (peek lines)))
                  end-seq (cursor-range-subsequence lines (->CursorRange start end))]
              (persistent! (if (empty-subsequence? end-seq)
                             lines-seqs
                             (append-subsequence! lines-seqs end-seq))))))))

(defn- splice-cursor-ranges [ascending-cursor-ranges-and-replacements]
  (loop [col-affected-row -1
         row-offset 0
         col-offset 0
         rest ascending-cursor-ranges-and-replacements
         new-cursor-ranges (transient [])]
    (if-some [[^CursorRange old-cursor-range replacement-lines] (first rest)]
      (let [^Cursor old-from (.from old-cursor-range)
            ^Cursor old-to (.to old-cursor-range)
            range-inverted? (pos? (compare old-from old-to))
            ^Cursor old-start (if range-inverted? old-to old-from)
            ^Cursor old-end (if range-inverted? old-from old-to)
            old-start-row (+ (.row old-start) row-offset)
            old-end-row (+ (.row old-end) row-offset)
            col-offset (if (= col-affected-row old-end-row) col-offset 0)
            old-start-col (+ (.col old-start) col-offset)
            old-end-col (+ (.col old-end) col-offset)
            old-row-count (inc (- old-end-row old-start-row))
            new-row-count (count replacement-lines)
            row-difference (- new-row-count old-row-count)
            ^long new-col-count (if (> new-row-count 1)
                                  (count (peek replacement-lines))
                                  (+ old-start-col (count (peek replacement-lines))))
            col-difference (- new-col-count old-end-col)
            new-start (->Cursor old-start-row old-start-col)
            new-end (->Cursor (+ old-end-row row-difference)
                              (+ old-end-col col-difference))
            new-cursor-range (if range-inverted?
                               (->CursorRange new-end new-start)
                               (->CursorRange new-start new-end))
            prev-cursor-range (peek! new-cursor-ranges)]
        (recur old-end-row
               (+ row-offset row-difference)
               (+ col-offset col-difference)
               (next rest)
               (if (not= prev-cursor-range new-cursor-range)
                 (conj! new-cursor-ranges new-cursor-range)
                 new-cursor-ranges)))
      (persistent! new-cursor-ranges))))

(defn- splice [lines ascending-cursor-ranges-and-replacements]
  ;; Cursor ranges are assumed to be adjusted to lines, and in ascending order.
  ;; The output cursor ranges will also confirm to these rules. Note that some
  ;; cursor ranges might have been merged by the splice.
  (when-not (empty? ascending-cursor-ranges-and-replacements)
    (let [invalidated-row (.row (cursor-range-start (ffirst ascending-cursor-ranges-and-replacements)))
          lines (splice-lines lines ascending-cursor-ranges-and-replacements)
          unadjusted-cursor-ranges (splice-cursor-ranges ascending-cursor-ranges-and-replacements)
          cursor-ranges (merge-cursor-ranges (map (partial adjust-cursor-range lines) unadjusted-cursor-ranges))]
      {:lines lines
       :cursor-ranges cursor-ranges
       :invalidated-row invalidated-row})))

(defn- insert-lines-seqs [lines cursor-ranges ^LayoutInfo layout lines-seqs]
  (when-not (empty? lines-seqs)
    (-> (splice lines (map (fn [cursor-range replacement-lines]
                             [(adjust-cursor-range lines cursor-range) replacement-lines])
                           cursor-ranges lines-seqs))
        (update :cursor-ranges (partial mapv cursor-range-end-range))
        (frame-cursor layout))))

(defn- insert-text [lines cursor-ranges ^LayoutInfo layout ^String text]
  (when-not (empty? text)
    (let [text-lines (string/split text #"\r?\n" -1)]
      (insert-lines-seqs lines cursor-ranges layout (repeat text-lines)))))

(defn delete-character-before-cursor [lines cursor-range]
  (let [from (CursorRange->Cursor cursor-range)
        to (cursor-left lines from)]
    [(->CursorRange from to) [""]]))

(defn delete-character-after-cursor [lines cursor-range]
  (let [from (CursorRange->Cursor cursor-range)
        to (cursor-right lines from)]
    [(->CursorRange from to) [""]]))

(defn- delete-range [lines cursor-range]
  [(adjust-cursor-range lines cursor-range) [""]])

(defn- put-selection-on-clipboard! [lines cursor-ranges clipboard]
  (let [selected-lines-ascending (mapv #(subsequence->lines (cursor-range-subsequence lines %))
                                       cursor-ranges)
        content {clipboard-mime-type-plain-text (string/join "\n" (flatten selected-lines-ascending))
                 clipboard-mime-type-multi-selection selected-lines-ascending}]
    (set-content! clipboard content)))

(defn- can-paste-plain-text? [clipboard]
  (has-content? clipboard clipboard-mime-type-plain-text))

(defn- can-paste-multi-selection? [clipboard cursor-ranges]
  (and (< 1 (count cursor-ranges))
       (has-content? clipboard clipboard-mime-type-multi-selection)))

(defn scroll [lines scroll-x scroll-y layout delta-x delta-y]
  (let [new-scroll-x (min 0.0 (+ ^double scroll-x ^double delta-x))
        new-scroll-y (limit-scroll-y layout lines (+ ^double scroll-y ^double delta-y))]
    (cond-> nil
            (not= scroll-x new-scroll-x) (assoc :scroll-x new-scroll-x)
            (not= scroll-y new-scroll-y) (assoc :scroll-y new-scroll-y))))

(defn- transform-indentation [lines ascending-cursor-ranges line-fn]
  (let [invalidated-row (.row (cursor-range-start (first ascending-cursor-ranges)))
        splices (into []
                      (comp (map (partial adjust-cursor-range lines))
                            (mapcat (fn [^CursorRange cursor-range]
                                      (range (.row (cursor-range-start cursor-range))
                                             (inc (.row (cursor-range-end cursor-range))))))
                            (distinct)
                            (keep (fn [row]
                                    (let [old-line (lines row)
                                          new-line (line-fn old-line)]
                                      (when (not= old-line new-line)
                                        (let [line-cursor-range (->CursorRange (->Cursor row 0) (->Cursor row (count old-line)))
                                              length-difference (- (count new-line) (count old-line))]
                                          [line-cursor-range [new-line] length-difference]))))))
                      ascending-cursor-ranges)
        lines (splice-lines lines splices)
        length-difference-by-row (into {}
                                       (keep (fn [[^CursorRange line-cursor-range _ ^long length-difference]]
                                               (when-not (zero? length-difference)
                                                 [(.row (CursorRange->Cursor line-cursor-range)) length-difference])))
                                       splices)
        cursor-ranges (into []
                            (map (fn [^CursorRange cursor-range]
                                   (let [^Cursor from (.from cursor-range)
                                         ^Cursor to (.to cursor-range)
                                         ^long from-col-offset (get length-difference-by-row (.row from) 0)
                                         ^long to-col-offset (get length-difference-by-row (.row to) 0)]
                                     (if (and (zero? from-col-offset)
                                              (zero? to-col-offset))
                                       cursor-range
                                       (->CursorRange (cursor-offset-unadjusted 0 from-col-offset from)
                                                      (cursor-offset-unadjusted 0 to-col-offset to))))))
                            ascending-cursor-ranges)]
    {:lines lines
     :cursor-ranges cursor-ranges
     :invalidated-row invalidated-row}))

(defn- deindent-lines [lines ascending-cursor-ranges indent-string]
  (let [indent-pattern (re-pattern (str "^\\t|" indent-string))
        deindent-line #(string/replace-first % indent-pattern "")]
    (transform-indentation lines ascending-cursor-ranges deindent-line)))

(defn- indent-lines [lines ascending-cursor-ranges indent-string]
  (let [indent-line #(if (empty? %) % (str indent-string %))]
    (transform-indentation lines ascending-cursor-ranges indent-line)))

(defn- can-indent? [cursor-ranges]
  (some? (some cursor-range-multi-line? cursor-ranges)))

(defn- can-deindent? [lines cursor-ranges]
  (some? (or (some cursor-range-multi-line? cursor-ranges)
             (some (fn [^CursorRange cursor-range]
                     (and (cursor-in-leading-whitespace? lines (.from cursor-range))
                          (cursor-in-leading-whitespace? lines (.to cursor-range))))
                   cursor-ranges))))

(defn key-typed [lines cursor-ranges layout typed meta-key? control-key?]
  (when-not (or meta-key? control-key?)
    (case typed
      "\r" ; Enter or Return.
      (insert-text lines cursor-ranges layout "\n")

      (when (not-any? #(Character/isISOControl ^char %) typed)
        (insert-text lines cursor-ranges layout typed)))))

(defn mouse-pressed [lines cursor-ranges layout button click-count x y shift-key? shortcut-key?]
  (when (= :primary button)
    (cond
      (and shift-key? (not shortcut-key?) (= 1 click-count) (= 1 (count cursor-ranges)))
      (let [mouse-cursor (adjust-cursor lines (canvas->cursor layout lines x y))
            cursor-range (->CursorRange (.from ^CursorRange (peek cursor-ranges)) mouse-cursor)]
        {:reference-cursor-range cursor-range
         :cursor-ranges [cursor-range]})

      (and (not shift-key?) (>= 3 ^long click-count))
      (let [mouse-cursor (adjust-cursor lines (canvas->cursor layout lines x y))
            cursor-range (case (long click-count)
                           1 (Cursor->CursorRange mouse-cursor)
                           2 (word-cursor-range-at-cursor lines mouse-cursor)
                           3 (->CursorRange (->Cursor (.row mouse-cursor) 0) (adjust-cursor lines (->Cursor (inc (.row mouse-cursor)) 0))))]
        {:reference-cursor-range cursor-range
         :cursor-ranges (if shortcut-key?
                          (concat-cursor-ranges cursor-ranges [cursor-range])
                          [cursor-range])}))))

(defn mouse-dragged [lines cursor-ranges ^CursorRange reference-cursor-range layout button click-count x y]
  (when (and (some? reference-cursor-range) (= :primary button) (>= 3 ^long click-count))
    (let [unadjusted-mouse-cursor (canvas->cursor layout lines x y)
          mouse-cursor (adjust-cursor lines unadjusted-mouse-cursor)
          cursor-range (case (long click-count)
                         1 (->CursorRange (.from reference-cursor-range) mouse-cursor)
                         2 (let [word-cursor-range (word-cursor-range-at-cursor lines mouse-cursor)]
                             (if (cursor-range-midpoint-follows? reference-cursor-range unadjusted-mouse-cursor)
                               (->CursorRange (cursor-range-end reference-cursor-range)
                                              (min-cursor (cursor-range-start word-cursor-range) (cursor-range-start reference-cursor-range)))
                               (->CursorRange (cursor-range-start reference-cursor-range)
                                              (max-cursor (cursor-range-end word-cursor-range) (cursor-range-end reference-cursor-range)))))
                         3 (if (cursor-range-midpoint-follows? reference-cursor-range unadjusted-mouse-cursor)
                             (->CursorRange (cursor-range-end reference-cursor-range)
                                            (adjust-cursor lines (->Cursor (.row mouse-cursor) 0)))
                             (->CursorRange (cursor-range-start reference-cursor-range)
                                            (adjust-cursor lines (->Cursor (inc (.row mouse-cursor)) 0)))))
          new-cursor-ranges [cursor-range]]
      (when (not= cursor-ranges new-cursor-ranges)
        (merge {:cursor-ranges new-cursor-ranges}
               (scroll-to-any-cursor layout lines new-cursor-ranges))))))

(defn cut! [lines cursor-ranges ^LayoutInfo layout clipboard]
  (when (put-selection-on-clipboard! lines cursor-ranges clipboard)
    (-> (splice lines (map (partial delete-range lines) cursor-ranges))
        (frame-cursor layout))))

(defn copy! [lines cursor-ranges clipboard]
  (put-selection-on-clipboard! lines cursor-ranges clipboard)
  nil)

(defn paste [lines cursor-ranges ^LayoutInfo layout clipboard]
  (cond
    (can-paste-multi-selection? clipboard cursor-ranges)
    (insert-lines-seqs lines cursor-ranges layout (cycle (get-content clipboard clipboard-mime-type-multi-selection)))

    (can-paste-plain-text? clipboard)
    (insert-text lines cursor-ranges layout (get-content clipboard clipboard-mime-type-plain-text))))

(defn can-paste? [cursor-ranges clipboard]
  (or (can-paste-plain-text? clipboard)
      (can-paste-multi-selection? clipboard cursor-ranges)))

(defn delete [lines cursor-ranges ^LayoutInfo layout delete-fn]
  (-> (if (every? cursor-range-empty? cursor-ranges)
        (splice lines (map (partial delete-fn lines) cursor-ranges))
        (splice lines (map (partial delete-range lines) cursor-ranges)))
      (frame-cursor layout)))

(defn- apply-move [lines cursor-ranges ^LayoutInfo layout move-fn cursor-fn]
  (let [new-cursor-ranges (move-fn cursor-ranges cursor-fn lines)]
    (when (not= cursor-ranges new-cursor-ranges)
      (merge {:cursor-ranges new-cursor-ranges}
             (scroll-to-any-cursor layout lines new-cursor-ranges)))))

(defn move [lines cursor-ranges ^LayoutInfo layout move-type cursor-type]
  (if (and (= :navigation move-type)
           (or (= :left cursor-type)
               (= :right cursor-type))
           (not-every? cursor-range-empty? cursor-ranges))
    (case cursor-type
      :left  {:cursor-ranges (mapv cursor-range-start-range cursor-ranges)}
      :right {:cursor-ranges (mapv cursor-range-end-range cursor-ranges)})
    (let [move-fn (move-type->move-fn move-type)
          cursor-fn (case cursor-type
                      :home  (partial cursor-home (every? #(zero? (.col (CursorRange->Cursor %))) (take 64 cursor-ranges)))
                      :end   cursor-end
                      :up    cursor-up
                      :down  cursor-down
                      :left  cursor-left
                      :right cursor-right
                      :prev-word cursor-prev-word
                      :next-word cursor-next-word
                      :line-start cursor-start
                      :line-end cursor-end)]
      (apply-move lines cursor-ranges layout move-fn cursor-fn))))

(defn page-up [lines cursor-ranges scroll-y ^LayoutInfo layout move-type]
  (let [row-count (dec (.drawn-line-count layout))
        ^double line-height (line-height (.glyph layout))
        scroll-height (* row-count line-height)
        move-fn (move-type->move-fn move-type)
        cursor-fn (partial cursor-offset-up row-count)]
    (some-> (apply-move lines cursor-ranges layout move-fn cursor-fn)
            (assoc :scroll-y (limit-scroll-y layout lines (+ ^double scroll-y scroll-height))))))

(defn page-down [lines cursor-ranges scroll-y ^LayoutInfo layout move-type]
  (let [row-count (dec (.drawn-line-count layout))
        ^double line-height (line-height (.glyph layout))
        scroll-height (* row-count line-height)
        move-fn (move-type->move-fn move-type)
        cursor-fn (partial cursor-offset-down row-count)]
    (some-> (apply-move lines cursor-ranges layout move-fn cursor-fn)
            (assoc :scroll-y (limit-scroll-y layout lines (- ^double scroll-y scroll-height))))))

(defn select-next-occurrence [lines cursor-ranges ^LayoutInfo layout]
  ;; If there are one or more non-range cursors, we select the words under the cursors instead of the next occurrence.
  (if-some [replaced-cursor-ranges-by-index (not-empty (into []
                                                             (keep-indexed (fn [index cursor-range]
                                                                             (when (cursor-range-empty? cursor-range)
                                                                               (let [cursor-range' (word-cursor-range-at-cursor lines (CursorRange->Cursor cursor-range))]
                                                                                 [index cursor-range']))))
                                                             cursor-ranges))]
    ;; There are one or more non-range cursors. Select the words under the cursors.
    (let [cursor-ranges' (reduce (fn [cursor-ranges' [index replaced-cursor-range]]
                                   (assoc cursor-ranges' index replaced-cursor-range))
                                 cursor-ranges
                                 replaced-cursor-ranges-by-index)
          focused-cursor-range (peek (peek replaced-cursor-ranges-by-index))
          scroll-properties (scroll-to-cursor-range scroll-shortest scroll-center layout lines focused-cursor-range)]
      (assoc scroll-properties :cursor-ranges cursor-ranges'))

    ;; Every cursor range has one or more characters
    ;; Add next occurrence of the bottom selected range to selection.
    (when-some [^CursorRange needle-cursor-range (peek cursor-ranges)]
      (let [needle-lines (subsequence->lines (cursor-range-subsequence lines needle-cursor-range))
            from-cursor (next-occurrence-search-cursor cursor-ranges)]
        (if-some [matching-cursor-range (find-next-occurrence lines needle-lines from-cursor)]
          (let [added-cursor-range (if (pos? (compare (.from needle-cursor-range) (.to needle-cursor-range)))
                                     (->CursorRange (.to matching-cursor-range) (.from matching-cursor-range))
                                     matching-cursor-range)
                cursor-ranges' (with-next-occurrence-search-cursor (concat-cursor-ranges cursor-ranges [added-cursor-range])
                                                                   (cursor-range-end added-cursor-range))
                scroll-properties (scroll-to-cursor-range scroll-shortest scroll-center layout lines added-cursor-range)]
            (assoc scroll-properties :cursor-ranges cursor-ranges'))
          (let [cursor-ranges' (with-next-occurrence-search-cursor cursor-ranges (->Cursor 0 0))]
            {:cursor-ranges cursor-ranges'}))))))

(defn split-selection-into-lines [lines cursor-ranges]
  (let [cursor-ranges' (into []
                             (comp (map (partial adjust-cursor-range lines))
                                   (mapcat (fn [^CursorRange cursor-range]
                                             (let [start (cursor-range-start cursor-range)
                                                   end (cursor-range-end cursor-range)]
                                               (if (zero? (- (.row end) (.row start)))
                                                 [(->CursorRange start end)]
                                                 (concat [(->CursorRange start (->Cursor (.row start) (count (lines (.row start)))))]
                                                         (map (fn [^long row] (->CursorRange (->Cursor row 0) (->Cursor row (count (lines row)))))
                                                              (range (inc (.row start)) (.row end)))
                                                         (when (pos? (.col end))
                                                           [(->CursorRange (->Cursor (.row end) 0) end)])))))))
                             cursor-ranges)]
    (when (not= cursor-ranges cursor-ranges')
      {:cursor-ranges cursor-ranges'})))

(defn single-selection [cursor-ranges]
  (when (< 1 (count cursor-ranges))
    {:cursor-ranges [(first cursor-ranges)]}))

(defn indent [lines cursor-ranges indent-string layout]
  (if (can-indent? cursor-ranges)
    (indent-lines lines cursor-ranges indent-string)
    (insert-text lines cursor-ranges layout indent-string)))

(defn deindent [lines cursor-ranges indent-string]
  (when (can-deindent? lines cursor-ranges)
    (deindent-lines lines cursor-ranges indent-string)))

(defn find-next [lines cursor-ranges ^LayoutInfo layout needle-lines wrap?]
  (let [from-cursor (next-occurrence-search-cursor cursor-ranges)]
    (if-some [matching-cursor-range (find-next-occurrence lines needle-lines from-cursor)]
      (let [scroll-properties (scroll-to-cursor-range scroll-shortest scroll-center layout lines matching-cursor-range)]
        (assoc scroll-properties :cursor-ranges [matching-cursor-range]))
      (when (and wrap? (not= from-cursor default-cursor))
        (recur lines (with-next-occurrence-search-cursor cursor-ranges default-cursor) layout needle-lines wrap?)))))

(defn find-prev [lines cursor-ranges ^LayoutInfo layout needle-lines wrap?]
  (let [from-cursor (prev-occurrence-search-cursor cursor-ranges)]
    (if-some [matching-cursor-range (find-prev-occurrence lines needle-lines from-cursor)]
      (let [scroll-properties (scroll-to-cursor-range scroll-shortest scroll-center layout lines matching-cursor-range)]
        (assoc scroll-properties :cursor-ranges [matching-cursor-range]))
      (when wrap?
        (let [document-end-cursor (document-end-cursor lines)]
          (when (not= from-cursor document-end-cursor)
            (recur lines (with-prev-occurrence-search-cursor cursor-ranges document-end-cursor) layout needle-lines wrap?)))))))

(defn replace-next [lines cursor-ranges ^LayoutInfo layout needle-lines replacement-lines wrap?]
  ;; TODO!
  )

(defn replace-all [lines cursor-ranges ^LayoutInfo layout needle-lines replacement-lines]
  ;; TODO!
  )