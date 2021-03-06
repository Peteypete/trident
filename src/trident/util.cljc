(ns trident.util
  "Utility library. Docstrings are omitted for simple functions; read the source
  to see what they do."
  (:require
    [cemerick.url :as url]
    [clojure.walk :refer [postwalk]]
    [clojure.pprint]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
 #?@(:cljs [[goog.string :as gstring]
            [goog.string.format]
            [cljs.core.async :refer [<! put! chan close!]]]
      :clj [[clojure.reflect :refer [reflect]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]]))
  #?(:cljs (:require-macros
             trident.util
             [cljs.core.async.macros :refer [go go-loop]])))

(def pprint clojure.pprint/pprint)

(defn spy [x]
  (pprint x)
  x)

(defn pred-> [x f g]
  (if (f x) (g x) x))

(defn derive-config
  "Replaces any `^:derived` values in `m`.

  Example:
  ```
  (def m
    {:first-name \"John\"
     :last-name  \"Doe\"
     :full-name ^:derived #(str (get-config % :first-name) \" \"
                                (get-config % :last-name))})

  (derive-config m)
  => {:first-name \"John\" :last-name  \"Doe\" :full-name \"John Doe\"}
  ```
  Any values with a truthy `:derived` metadata value must be single-argument
  functions. These functions will be replaced with their return values, with
  the config map as the argument.

  `get-config` is like `get`, but if the return value is a `:derived` function,
  `get-config` will derive it before returning. It should be used within
  `:derived` function as in the example "
  [m]
  (postwalk #(if (:derived (meta %)) (% m) %) m))

(defn get-in-config
  "See [derive-config]."
  ([m ks not-found]
   (let [x (get-in m ks not-found)]
     (if (:derived (meta x))
       (x m)
       x)))
  ([m ks]
   (get-in-config m ks nil)))

(defn get-config
  "See [derive-config]."
  ([m k not-found]
   (get-in-config m [k] not-found))
  ([m k]
   (get-config m k nil)))

(defn ^:no-doc text-rows [lines]
  (str/join \newline (remove nil? (flatten lines))))

#?(:clj (do

(defn maybe-slurp
  "Attempts to slurp `f`, returning nil on failure"
  [f]
  (try
    (slurp f)
    (catch Exception e nil)))

(defn read-config [filename]
  (some-> filename io/resource maybe-slurp read-string))

(defmacro capture [& xs]
  `(do ~@(for [x xs] `(def ~x ~x))))

(defmacro cljs-import-vars
  "Like `potemkin/import-vars` but supports importing cljs-only functions.

  Example:
  ```
  (cljs-import-vars my.namespace.core
                    foo bar baz)
  ```"
  [nspace & syms]
  `(do ~@(for [s syms]
           `(def ~s ~(symbol (str nspace) (str s))))))

(defmacro forv [& body]
  `(vec (for ~@body)))

(defmacro condas->
  "Combination of `cond->` and `as->`."
  [expr name & clauses]
  (assert (even? (count clauses)))
  `(as-> ~expr ~name
     ~@(map (fn [[test step]] `(if ~test ~step ~name))
            (partition 2 clauses))))

(defmacro js<!
  "Like `<!` but for js promises. See [[to-chan]]."
  [form]
  `(let [ret# (cljs.core.async/<! (to-chan ~form))]
     (when (not= ret# ::nil)
       ret#)))

(defmacro for-every? [& forms]
  `(every? boolean (for ~@forms)))

(defmacro for-some? [& forms]
  `(some boolean (for ~@forms)))

(defmacro forcat [& forms]
  `(apply concat (for ~@forms)))

(defn load-var
  "Dynamically loads a var.

  `sym`: a fully-qualified var symbol."
  [sym]
  (require (symbol (namespace sym)))
  (let [f (if-let [f (resolve sym)]
            f
            (throw (ex-info sym " is not on the classpath.")))]
    @f))

(defn loadf
  "Returns a function that dynamically loads and calls the specified function.

  `sym`: a fully-qualified function symbol."
  [sym]
  (fn [& args]
    (apply (load-var sym) args)))

(defmacro load-fns
  "Defines a bunch of \"dynamic functions\" (see [[loadf]]).

  Example:
  ```
  (load-fns
    foo my.lib/foo
    bar my.lib/bar
    baz your.lib/baz)
  (foo) ; same as (do (require 'my.lib) (my.lib/foo))
  ```"
  [& forms]
  (assert (even? (count forms)))
  `(do ~@(for [[sym fn-sym] (partition 2 forms)]
           `(def ~sym (#'loadf (quote ~fn-sym))))))

(s/def ::overrides (s/+ (s/cat :interface symbol? :fns (s/* list?))))

(defn- sigs [interface]
  (if (class? interface)
    (->> interface
         reflect
         :members
         (filter #(contains? % :parameter-types))
         (mapv (juxt :name (comp inc count :parameter-types))))
    (for [{-name :name arglists :arglists} (vals (:sigs @interface))
          arglist arglists]
      [-name (count arglist)])))

(defmacro inherit
  "Like `deftype` but with default function implementations.

  The first field is the \"parent\": for any protocol functions you don't define,
  the function will be called again with the parent as the first argument instead
  of the current object.

  Example:
  ```
  (defprotocol P
    (foo [this])
    (bar [this]))

  (deftype Parent []
    P
    (foo [_] \"parent foo\")
    (bar [_] \"parent bar\"))

  (inherit Child [parent]
    P
    (foo [_] \"child foo\"))

  (def parent (Parent.))
  (def child (Child. parent))

  (foo child)
  => \"child foo\"
  (bar child)
  => \"parent bar\"
  ```"
  [child-name [parent-instance :as fields] & overrides]
  (s/assert ::overrides overrides)
  `(deftype ~child-name ~fields
     ~@(forcat [{:keys [interface fns]} (s/conform ::overrides overrides)]
         (let [interface' (resolve interface)
               override-fns (->> fns
                                 (map (fn [form] [[(first form)
                                                   (count (second form))] form]))
                                 (into {}))]
           `[~interface
             ~@(for [[-name argcount :as sig] (sigs interface')]
                 (or (override-fns sig)
                     (let [arglist (vec (conj (repeatedly (dec argcount) gensym) '_))
                           parent-method (if (class? interface')
                                           (symbol (str "." (name -name)))
                                           (symbol (namespace interface) (name -name)))]
                       `(~-name
                          ~arglist
                          (~parent-method
                            ~parent-instance
                            ~@(rest arglist))))))]))))

(defmacro text
  "Generates a string from pairs of conditions and lines of text.

  The right-hand side values will be flattened, so you can give strings,
  collections of strings, or nested collections of strings. `nil`s are
  removed.

  Example:
  ```
  (println (text
             true  [\"foo\" nil \"bar\"]
             false \"baz\"
             true  \"quux\"))
  foo
  bar
  quux
  ```"
  [& forms]
  `(text-rows [~@(for [[condition lines] (partition 2 forms)]
                   `(when ~condition ~lines))]))

(def ^:no-doc instant-type java.util.Date)

(def ord int)

(defn parse-int [s]
  (Long/parseLong s))

(defn sh
  "Runs a shell command.

  Returns the output if successful; otherwise, throws an exception."
  [& args]
  (let [result (apply shell/sh args)]
    (if (= 0 (:exit result))
      (:out result)
      (throw (ex-info (:err result) result)))))

(defmacro catchall [& forms]
  `(try ~@forms (catch Exception ~'_ nil)))

(defmacro catchall-js [& forms]
  `(try ~@forms (catch ~'js/Error ~'_ nil)))

) :cljs (do

(defn to-chan
  "Converts a js promise to a channel.
  If the promise throws an error, logs to the console and closes the channel."
  [p]
  (let [c (chan)]
    (.. p (then #(put! c (if (some? %) % ::nil)))
        (catch #(do
                  (.error js/console %)
                  (close! c))))
    c))

(def ^{:doc "alias for `goog.string.format`"}
  format gstring/format)

(def ^:no-doc instant-type (type #inst "0001-01-01"))

(def char->int (into {} (map #(vector (char %) %) (range 256))))

(def ord char->int)

(defn parse-int [s]
  (js/parseInt s))

))

(defn instant? [x]
  (= (type x) instant-type))

(defn rand-str [len]
  (apply str (take len (repeatedly #(char (+ (rand 26) 65))))))

(defn map-kv [f m]
  (into {} (map (fn [[k v]] (f k v)) m)))

(defn map-keys [f m]
  (map-kv (fn [k v] [(f k) v]) m))

(defn map-vals [f m]
  (map-kv (fn [k v] [k (f v)]) m))

(defn map-from-to [f g xs]
  (->> xs
       (map (juxt f g))
       (into {})))

(defn map-from [f xs]
  (map-from-to f identity xs))

(defn map-to [f xs]
  (map-from-to identity f xs))

(defn doclines
  "Returns the docstring of a var as a collection of lines, removing indentation."
  [_var]
  (when-some [_doc (:doc (meta _var))]
    (let [lines (str/split _doc #"\n")
          indent (->> (rest lines)
                      (map #(count (re-find #"^ *" %)))
                      (apply min ##Inf))]
      (map-indexed #(cond-> %2 (not (zero? %1)) (subs indent))
                   lines))))

(defn pad [n _val coll]
  (take n (concat coll (repeat _val))))

(defn format-columns
  "Formats rows of text into columns.

  Example:
  ```
  (doseq [row (format-columns [[\"hellooooooooo \" \"there\"]
                               [\"foo \" \"bar\"]
                               [\"one column\"]])]
    (println row))
  hellooooooooo there
  foo           bar
  one column
  ```"
  [rows]
  (let [n-cols (apply max (map count rows))
        rows (map (partial pad n-cols " ") rows)
        lens (apply map (fn [& column-parts]
                          (apply max (map count column-parts)))
                    rows)
        fmt (str/join (map #(str "%" (when (not (zero? %)) (str "-" %)) "s") lens))]
    (->> rows
         (map #(apply (partial format fmt) %))
         (map str/trimr))))

(defn split-by [pred xs]
  (reduce #(update %1 (if (pred %2) 0 1) (fnil conj []) %2)
          [nil nil] xs))

(defn print-table
  "Prints a nicely formatted table.

  Example:
  ```
  (print-table
    [[:foo \"Foo\"] [:bar \"Bar\"]]
    [{:foo 1 :bar 2} {:foo 3 :bar 4}])
  => Foo  Bar
     1    2
     3    4
  ```"
  [header-info table]
  (let [[ks header] (apply map vector header-info)
        header (map #(str % "  ") header)
        body (->> table
                  (map (apply juxt ks))
                  (map (fn [row] (map #(str % "  ") row))))
        rows (concat [header] body)]
    (doseq [row (format-columns rows)]
      (println row))))

(defn round
  ([t places]
   (let [factor (Math/pow 10 places)]
     (float (/ (int (* factor t)) factor))))
  ([t] (round t 1)))

(defn avg [xs]
  (if (empty? xs)
    0
    (/ (reduce + xs) (count xs))))

(defn assoc-pred
  "Like assoc, but skip kv pairs where (f v) is false."
  [m f & kvs]
  (if-some [kvs (some->> kvs
                         (partition 2)
                         (filter (comp f second))
                         (apply concat)
                         not-empty)]
    (apply assoc m kvs)
    m))

(defn assoc-some [m & kvs]
  (apply assoc-pred m some? kvs))

(defn emptyish? [x]
  "Like empty?, but return false whenever empty? would throw an exception."
  (boolean (#?(:clj catchall :cljs trident.util/catchall-js) (empty? x))))

; idk why, but fully-qualifying catchall-js prevents compiler warnings.
(defn assoc-not-empty [m & kvs]
  (apply assoc-pred m #(not (emptyish? %)) kvs))

(defn dissoc-empty [m]
  (apply dissoc m (filter #(emptyish? (m %)) (keys m))))

(defn take-str [n s]
  (some->> s (take n) (str/join "")))


(defn add-seconds [date seconds]
  #?(:clj (java.util.Date/from (.plusSeconds (.toInstant date) seconds))
     :cljs (js/Date. (+ (.getTime date) (* 1000 seconds)))))

(defn compare= [x y]
  (= 0 (compare x y)))

(defn compare< [x y]
  (= -1 (compare x y)))

(defn compare> [x y]
  (= 1 (compare x y)))

(defn compare<= [x y]
  (or (compare< x y) (compare= x y)))

(defn compare>= [x y]
  (or (compare> x y) (compare= x y)))

(defn distinct-by [f xs]
  (->>
    (reduce
      (fn [[xs ks] x]
        (let [k (f x)
              include? (not (contains? ks k))]
          [(cond-> xs include? (conj x))
           (cond-> ks include? (conj k))]))
      ['() #{}]
      xs)
    first
    reverse))

#?(:clj
   (do
     (defn parse-format-date [date in-format out-format]
       (cond->> date
         in-format (.parse (new java.text.SimpleDateFormat in-format))
         out-format (.format (new java.text.SimpleDateFormat out-format))))

     (defn parse-date [date in-format]
       (parse-format-date date in-format nil))

     (defn format-date [date out-format]
       (parse-format-date date nil out-format))))

#?(:cljs
   (defn synchronize
     "Returns a fn that will queue calls to `f`, an async fn."
     [f]
     (let [queue (atom #queue [])
           notify-chans (atom {})]
       (fn [& args]
         (let [f #(apply f args)
               wait? (not (empty? @queue))]
           (swap! queue conj f)
           (when wait?
             (swap! notify-chans assoc f (chan)))
           (go
             (when wait?
               (<! (@notify-chans f)))
             (let [ret (<! (f))]
               (swap! queue pop)
               (when wait?
                 (close! (@notify-chans f))
                 (swap! notify-chans dissoc f))
               (when-not (empty? @queue)
                 (put! (@notify-chans (peek @queue)) :done))
               ret)))))))

#?(:cljs
   (defn go-comp [& fs]
     (fn [& args]
       (go-loop [args args
                 fs (reverse fs)]
         (if-some [f (first fs)]
           (let [result (apply f args)
                 result (if (satisfies? cljs.core.async.impl.protocols/ReadPort result)
                          (<! result)
                          result)]
             (recur [result] (rest fs)))
           (first args))))))

(defn parse-url [url]
  (#?(:clj catchall :cljs trident.util/catchall-js)
    (url/url url)))

(defmacro when-some-all [[& bindings] & forms]
  (if (empty? bindings)
    `(do ~@forms)
    `(when-some [~@(take 2 bindings)]
       (when-some-all [~@(drop 2 bindings)]
         ~@forms))))

(defmacro if-some-all [[& bindings] & [then else :as forms]]
  (if (>= 2 (count bindings))
    `(if-some [~@bindings]
       ~@forms)
    `(if-some [~@(take 2 bindings)]
       (if-some-all [~@(drop 2 bindings)]
         ~@forms)
       ~else)))

(defn now []
  #?(:clj (java.util.Date.)
     :cljs (js/Date.)))

(defn interleave-weighted [w coll-a coll-b]
  ((fn step [na nb coll-a coll-b]
     (let [i (if (<= (/ na (max (+ na nb) 1)) w) 0 1)
           colls [coll-a coll-b]
           done (empty? (get colls i))
           x (first (get colls i))
           [coll-a coll-b] (update colls i rest)
           [na nb] (update [na nb] i inc)]
       (when-not done
         (lazy-seq
           (cons x
             (step na nb coll-a coll-b))))))
   0 0 coll-a coll-b))

(defn wrand [slices]
  (let [total (reduce + slices)
        r (rand total)]
    (loop [i 0 sum 0]
      (if (< r (+ (slices i) sum))
        i
        (recur (inc i) (+ (slices i) sum))))))

(defn sample-by [f xs]
  (when (not-empty xs)
    (let [choice (wrand (mapv f xs))
          ys (concat (take choice xs) (drop (inc choice) xs))]
      (lazy-seq (cons (nth xs choice) (sample-by f ys))))))

(defn random-by [f xs]
  (when (not-empty xs)
    (let [choice (wrand (mapv f xs))]
      (lazy-seq (cons (nth xs choice) (random-by f xs))))))

(defn ceil-at [x n]
  (int (* (Math/ceil (/ x n)) n)))

(defn dissoc-vec [v i]
  (into (subvec v 0 i) (subvec v (inc i))))

(defn wrap-vec
  "Use judiciously."
  [x]
  (if (coll? x)
    x
    (vector x)))
