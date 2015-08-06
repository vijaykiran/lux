;;   Copyright (c) Eduardo Julian. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lux.base
  (:require (clojure [template :refer [do-template]])
            [clojure.core.match :as M :refer [matchv]]
            clojure.core.match.array))

;; [Tags]
(def $Nil "lux;Nil")
(def $Cons "lux;Cons")

;; [Fields]
;; Binding
(def $COUNTER 0)
(def $MAPPINGS 1)

;; Env
(def $CLOSURE 0)
(def $INNER-CLOSURES 1)
(def $LOCALS 2)
(def $NAME 3)

;; Host
(def $CLASSES 0)
(def $LOADER 1)
(def $WRITER 2)

;; Compiler
(def $cursor 0)
(def $ENVS 1)
(def $EVAL? 2)
(def $EXPECTED 3)
(def $HOST 4)
(def $MODULES 5)
(def $SEED 6)
(def $SOURCE 7)
(def $TYPES 8)

;; [Exports]
(def +name-separator+ ";")

(defn T [& elems]
  (to-array elems))

(defn V [tag value]
  (to-array [tag value]))

(defn R [& kvs]
  (to-array kvs))

(defn get$ [slot ^objects record]
  (aget record slot))

(defn set$ [slot value ^objects record]
  (let [record* (aclone record)
        size (alength record)]
    (aset record* slot value)
    record*))

(defmacro update$ [slot f record]
  `(let [record# ~record]
     (set$ ~slot (~f (get$ ~slot record#))
           record#)))

(defn fail* [message]
  (V "lux;Left" message))

(defn return* [state value]
  (V "lux;Right" (T state value)))

(defn transform-pattern [pattern]
  (cond (vector? pattern) (mapv transform-pattern pattern)
        (seq? pattern) (let [parts (mapv transform-pattern (rest pattern))]
                         (vec (cons (eval (first pattern))
                                    (list (case (count parts)
                                            0 '_
                                            1 (first parts)
                                            ;; else
                                            `[~@parts])))))
        :else pattern
        ))

(defmacro |case [value & branches]
  (assert (= 0 (mod (count branches) 2)))
  (let [value* (if (vector? value)
                 [`(T ~@value)]
                 [value])]
    `(matchv ::M/objects ~value*
       ~@(mapcat (fn [[pattern body]]
                   (list [(transform-pattern pattern)]
                         body))
                 (partition 2 branches)))))

(defmacro |let [bindings body]
  (reduce (fn [inner [left right]]
            `(|case ~right
               ~left
               ~inner))
          body
          (reverse (partition 2 bindings))))

(defmacro |list [& elems]
  (reduce (fn [tail head]
            `(V "lux;Cons" (T ~head ~tail)))
          `(V "lux;Nil" nil)
          (reverse elems)))

(defmacro |table [& elems]
  (reduce (fn [table [k v]]
            `(|put ~k ~v ~table))
          `(|list)
          (reverse (partition 2 elems))))

(defn |get [slot table]
  (|case table
    ($Nil)
    nil
    
    ($Cons [k v] table*)
    (if (.equals ^Object k slot)
      v
      (|get slot table*))))

(defn |put [slot value table]
  (|case table
    ($Nil)
    (V "lux;Cons" (T (T slot value) (V "lux;Nil" nil)))
    
    ($Cons [k v] table*)
    (if (.equals ^Object k slot)
      (V "lux;Cons" (T (T slot value) table*))
      (V "lux;Cons" (T (T k v) (|put slot value table*))))

    _
    (assert false (prn-str '|put (aget table 0)))))

(defn |remove [slot table]
  (|case table
    ($Nil)
    table
    
    ($Cons [k v] table*)
    (if (.equals ^Object k slot)
      table*
      (V "lux;Cons" (T (T k v) (|remove slot table*))))))

(defn |update [k f table]
  (|case table
    ($Nil)
    table

    ($Cons [k* v] table*)
    (if (.equals ^Object k k*)
      (V "lux;Cons" (T (T k* (f v)) table*))
      (V "lux;Cons" (T (T k* v) (|update k f table*))))))

(defn |head [xs]
  (|case xs
    ($Nil)
    (assert false)

    ($Cons x _)
    x))

(defn |tail [xs]
  (|case xs
    ($Nil)
    (assert false)

    ($Cons _ xs*)
    xs*))

;; [Resources/Monads]
(defn fail [message]
  (fn [_]
    (V "lux;Left" message)))

(defn return [value]
  (fn [state]
    (V "lux;Right" (T state value))))

(defn bind [m-value step]
  (fn [state]
    (let [inputs (m-value state)]
      (|case inputs
        ("lux;Right" ?state ?datum)
        ((step ?datum) ?state)
        
        ("lux;Left" _)
        inputs
        ))))

(defmacro |do [steps return]
  (assert (= 0 (rem (count steps) 2)) "The number of steps must be even!")
  (reduce (fn [inner [label computation]]
            (case label
              :let `(|let ~computation ~inner)
              ;; else
              `(bind ~computation
                     (fn [val#]
                       (|case val#
                         ~label
                         ~inner)))))
          return
          (reverse (partition 2 steps))))

;; [Resources/Combinators]
(defn |cons [head tail]
  (V "lux;Cons" (T head tail)))

(defn |++ [xs ys]
  (|case xs
    ($Nil)
    ys

    ($Cons x xs*)
    (V "lux;Cons" (T x (|++ xs* ys)))))

(defn |map [f xs]
  (|case xs
    ($Nil)
    xs

    ($Cons x xs*)
    (V "lux;Cons" (T (f x) (|map f xs*)))))

(defn |empty? [xs]
  (|case xs
    ($Nil)
    true

    ($Cons _ _)
    false))

(defn |filter [p xs]
  (|case xs
    ($Nil)
    xs

    ($Cons x xs*)
    (if (p x)
      (V "lux;Cons" (T x (|filter p xs*)))
      (|filter p xs*))))

(defn flat-map [f xs]
  (|case xs
    ($Nil)
    xs

    ($Cons x xs*)
    (|++ (f x) (flat-map f xs*))))

(defn |split-with [p xs]
  (|case xs
    ($Nil)
    (T xs xs)

    ($Cons x xs*)
    (if (p x)
      (|let [[pre post] (|split-with p xs*)]
        (T (|cons x pre) post))
      (T (V "lux;Nil" nil) xs))))

(defn |contains? [k table]
  (|case table
    ($Nil)
    false

    ($Cons [k* _] table*)
    (or (.equals ^Object k k*)
        (|contains? k table*))))

(defn fold [f init xs]
  (|case xs
    ($Nil)
    init

    ($Cons x xs*)
    (fold f (f init x) xs*)))

(defn fold% [f init xs]
  (|case xs
    ($Nil)
    (return init)

    ($Cons x xs*)
    (|do [init* (f init x)]
      (fold% f init* xs*))))

(defn folds [f init xs]
  (|case xs
    ($Nil)
    (|list init)

    ($Cons x xs*)
    (|cons init (folds f (f init x) xs*))))

(defn |length [xs]
  (fold (fn [acc _] (inc acc)) 0 xs))

(let [|range* (fn |range* [from to]
                (if (< from to)
                  (V "lux;Cons" (T from (|range* (inc from) to)))
                  (V "lux;Nil" nil)))]
  (defn |range [n]
    (|range* 0 n)))

(defn |first [pair]
  (|let [[_1 _2] pair]
    _1))

(defn |second [pair]
  (|let [[_1 _2] pair]
    _2))

(defn zip2 [xs ys]
  (|case [xs ys]
    [($Cons x xs*) ($Cons y ys*)]
    (V "lux;Cons" (T (T x y) (zip2 xs* ys*)))

    [_ _]
    (V "lux;Nil" nil)))

(defn |keys [plist]
  (|case plist
    ($Nil)
    (|list)
    
    ($Cons [k v] plist*)
    (|cons k (|keys plist*))))

(defn |vals [plist]
  (|case plist
    ($Nil)
    (|list)
    
    ($Cons [k v] plist*)
    (|cons v (|vals plist*))))

(defn |interpose [sep xs]
  (|case xs
    ($Nil)
    xs

    ($Cons _ ($Nil))
    xs
    
    ($Cons x xs*)
    (V "lux;Cons" (T x (V "lux;Cons" (T sep (|interpose sep xs*)))))))

(do-template [<name> <joiner>]
  (defn <name> [f xs]
    (|case xs
      ($Nil)
      (return xs)

      ($Cons x xs*)
      (|do [y (f x)
            ys (<name> f xs*)]
        (return (<joiner> y ys)))))

  map%      |cons
  flat-map% |++)

(defn list-join [xss]
  (fold |++ (V "lux;Nil" nil) xss))

(defn |as-pairs [xs]
  (|case xs
    ($Cons x ($Cons y xs*))
    (V "lux;Cons" (T (T x y) (|as-pairs xs*)))

    _
    (V "lux;Nil" nil)))

(defn |reverse [xs]
  (fold (fn [tail head]
          (|cons head tail))
        (|list)
        xs))

(defn assert! [test message]
  (if test
    (return nil)
    (fail message)))

(def get-state
  (fn [state]
    (return* state state)))

(defn try-all% [monads]
  (|case monads
    ($Nil)
    (fail "There are no alternatives to try!")

    ($Cons m monads*)
    (fn [state]
      (let [output (m state)]
        (|case [output monads*]
          [("lux;Right" _) _]
          output

          [_ ($Nil)]
          output
          
          [_ _]
          ((try-all% monads*) state)
          )))
    ))

(defn repeat% [monad]
  (try-all% (|list (|do [head monad
                         tail (repeat% monad)]
                     (return (|cons head tail)))
                   (return (|list)))))

(defn exhaust% [step]
  (fn [state]
    (|case (step state)
      ("lux;Right" state* _)
      ((exhaust% step) state*)

      ("lux;Left" msg)
      (if (.equals "[Reader Error] EOF" msg)
        (return* state nil)
        (fail* msg)))))

(defn ^:private normalize-char [char]
  (case char
    \* "_ASTER_"
    \+ "_PLUS_"
    \- "_DASH_"
    \/ "_SLASH_"
    \\ "_BSLASH_"
    \_ "_UNDERS_"
    \% "_PERCENT_"
    \$ "_DOLLAR_"
    \' "_QUOTE_"
    \` "_BQUOTE_"
    \@ "_AT_"
    \^ "_CARET_"
    \& "_AMPERS_"
    \= "_EQ_"
    \! "_BANG_"
    \? "_QM_"
    \: "_COLON_"
    \. "_PERIOD_"
    \, "_COMMA_"
    \< "_LT_"
    \> "_GT_"
    \~ "_TILDE_"
    \| "_PIPE_"
    ;; default
    char))

(defn normalize-name [ident]
  (reduce str "" (map normalize-char ident)))

(def loader
  (fn [state]
    (return* state (->> state (get$ $HOST) (get$ $LOADER)))))

(def classes
  (fn [state]
    (return* state (->> state (get$ $HOST) (get$ $CLASSES)))))

(def +init-bindings+
  (R ;; "lux;counter"
   0
   ;; "lux;mappings"
   (|table)))

(defn env [name]
  (R ;; "lux;closure"
   +init-bindings+
   ;; "lux;inner-closures"
   0
   ;; "lux;locals"
   +init-bindings+
   ;; "lux;name"
   name
   ))

(let [define-class (doto (.getDeclaredMethod java.lang.ClassLoader "defineClass" (into-array [String
                                                                                              (class (byte-array []))
                                                                                              Integer/TYPE
                                                                                              Integer/TYPE]))
                     (.setAccessible true))]
  (defn memory-class-loader [store]
    (proxy [java.lang.ClassLoader]
      []
      (findClass [^String class-name]
        ;; (prn 'findClass class-name)
        (if-let [^bytes bytecode (get @store class-name)]
          (try (.invoke define-class this (to-array [class-name bytecode (int 0) (int (alength bytecode))]))
            (catch java.lang.reflect.InvocationTargetException e
              (prn 'InvocationTargetException (.getCause e))
              (throw e)))
          (do (prn 'memory-class-loader/store class-name (keys @store))
            (throw (IllegalStateException. (str "[Class Loader] Unknown class: " class-name)))))))))

(defn host [_]
  (let [store (atom {})]
    (R ;; "lux;classes"
     store
     ;; "lux;loader"
     (memory-class-loader store)
     ;; "lux;writer"
     (V "lux;None" nil))))

(defn init-state [_]
  (R ;; "lux;cursor"
   (T "" -1 -1)
   ;; "lux;envs"
   (|list)
   ;; "lux;eval?"
   false
   ;; "lux;expected"
   (V "lux;VariantT" (|list))
   ;; "lux;host"
   (host nil)
   ;; "lux;modules"
   (|table)
   ;; "lux;seed"
   0
   ;; "lux;source"
   (V "lux;None" nil)
   ;; "lux;types"
   +init-bindings+
   ))

(defn save-module [body]
  (fn [state]
    (|case (body state)
      ("lux;Right" state* output)
      (return* (->> state*
                    (set$ $ENVS (get$ $ENVS state))
                    (set$ $SOURCE (get$ $SOURCE state)))
               output)

      ("lux;Left" msg)
      (fail* msg))))

(defn with-eval [body]
  (fn [state]
    (|case (body (set$ $EVAL? true state))
      ("lux;Right" state* output)
      (return* (set$ $EVAL? (get$ $EVAL? state) state*) output)

      ("lux;Left" msg)
      (fail* msg))))

(def get-eval
  (fn [state]
    (return* state (get$ $EVAL? state))))

(def get-writer
  (fn [state]
    (let [writer* (->> state (get$ $HOST) (get$ $WRITER))]
      (|case writer*
        ("lux;Some" datum)
        (return* state datum)

        _
        (fail* "Writer hasn't been set.")))))

(def get-top-local-env
  (fn [state]
    (try (let [top (|head (get$ $ENVS state))]
           (return* state top))
      (catch Throwable _
        (fail* "No local environment.")))))

(def gen-id
  (fn [state]
    (let [seed (get$ $SEED state)]
      (return* (set$ $SEED (inc seed) state) seed))))

(defn ->seq [xs]
  (|case xs
    ($Nil)
    (list)

    ($Cons x xs*)
    (cons x (->seq xs*))))

(defn ->list [seq]
  (if (empty? seq)
    (|list)
    (|cons (first seq) (->list (rest seq)))))

(defn |repeat [n x]
  (if (> n 0)
    (|cons x (|repeat (dec n) x))
    (|list)))

(def get-module-name
  (fn [state]
    (|case (|reverse (get$ $ENVS state))
      ($Nil)
      (fail* "[Analyser Error] Can't get the module-name without a module.")

      ($Cons ?global _)
      (return* state (get$ $NAME ?global)))))

(defn with-scope [name body]
  (fn [state]
    (let [output (body (update$ $ENVS #(|cons (env name) %) state))]
      (|case output
        ("lux;Right" state* datum)
        (return* (update$ $ENVS |tail state*) datum)
        
        _
        output))))

(defn run-state [monad state]
  (monad state))

(defn with-closure [body]
  (|do [closure-name (|do [top get-top-local-env]
                       (return (->> top (get$ $INNER-CLOSURES) str)))]
    (fn [state]
      (let [body* (with-scope closure-name body)]
        (run-state body* (update$ $ENVS #(|cons (update$ $INNER-CLOSURES inc (|head %))
                                                (|tail %))
                                  state))))))

(def get-scope-name
  (fn [state]
    (return* state (->> state (get$ $ENVS) (|map #(get$ $NAME %)) |reverse))))

(defn with-writer [writer body]
  (fn [state]
    (let [output (body (update$ $HOST #(set$ $WRITER (V "lux;Some" writer) %) state))]
      (|case output
        ("lux;Right" ?state ?value)
        (return* (update$ $HOST #(set$ $WRITER (->> state (get$ $HOST) (get$ $WRITER)) %) ?state)
                 ?value)

        _
        output))))

(defn with-expected-type [type body]
  "(All [a] (-> Type (Lux a)))"
  (fn [state]
    (let [output (body (set$ $EXPECTED type state))]
      (|case output
        ("lux;Right" ?state ?value)
        (return* (set$ $EXPECTED (get$ $EXPECTED state) ?state)
                 ?value)

        _
        output))))

(defn with-cursor [cursor body]
  "(All [a] (-> Cursor (Lux a)))"
  (if (= "" (aget cursor 0))
    body
    (fn [state]
      (let [output (body (set$ $cursor cursor state))]
        (|case output
          ("lux;Right" ?state ?value)
          (return* (set$ $cursor (get$ $cursor state) ?state)
                   ?value)

          _
          output)))))

(defn show-ast [ast]
  (|case ast
    ("lux;Meta" _ ["lux;BoolS" ?value])
    (pr-str ?value)

    ("lux;Meta" _ ["lux;IntS" ?value])
    (pr-str ?value)

    ("lux;Meta" _ ["lux;RealS" ?value])
    (pr-str ?value)

    ("lux;Meta" _ ["lux;CharS" ?value])
    (pr-str ?value)

    ("lux;Meta" _ ["lux;TextS" ?value])
    (str "\"" ?value "\"")

    ("lux;Meta" _ ["lux;TagS" ?module ?tag])
    (str "#" ?module ";" ?tag)

    ("lux;Meta" _ ["lux;SymbolS" ?module ?ident])
    (if (.equals "" ?module)
      ?ident
      (str ?module ";" ?ident))

    ("lux;Meta" _ ["lux;TupleS" ?elems])
    (str "[" (->> ?elems (|map show-ast) (|interpose " ") (fold str "")) "]")

    ("lux;Meta" _ ["lux;RecordS" ?elems])
    (str "{" (->> ?elems
                  (|map (fn [elem]
                          (|let [[k v] elem]
                            (str (show-ast k) " " (show-ast v)))))
                  (|interpose " ") (fold str "")) "}")

    ("lux;Meta" _ ["lux;FormS" ?elems])
    (str "(" (->> ?elems (|map show-ast) (|interpose " ") (fold str "")) ")")
    ))

(defn ident->text [ident]
  (|let [[?module ?name] ident]
    (str ?module ";" ?name)))

(defn fold2% [f init xs ys]
  (|case [xs ys]
    [($Cons x xs*) ($Cons y ys*)]
    (|do [init* (f init x y)]
      (fold2% f init* xs* ys*))

    [($Nil) ($Nil)]
    (return init)

    [_ _]
    (fail "Lists don't match in size.")))

(defn map2% [f xs ys]
  (|case [xs ys]
    [($Cons x xs*) ($Cons y ys*)]
    (|do [z (f x y)
          zs (map2% f xs* ys*)]
      (return (|cons z zs)))

    [($Nil) ($Nil)]
    (return (V "lux;Nil" nil))

    [_ _]
    (fail "Lists don't match in size.")))

(defn map2 [f xs ys]
  (|case [xs ys]
    [($Cons x xs*) ($Cons y ys*)]
    (|cons (f x y) (map2 f xs* ys*))

    [_ _]
    (V "lux;Nil" nil)))

(defn fold2 [f init xs ys]
  (|case [xs ys]
    [($Cons x xs*) ($Cons y ys*)]
    (and init
         (fold2 f (f init x y) xs* ys*))

    [($Nil) ($Nil)]
    init

    [_ _]
    false))

(defn ^:private enumerate* [idx xs]
  (|case xs
    ($Cons x xs*)
    (V "lux;Cons" (T (T idx x)
                     (enumerate* (inc idx) xs*)))

    ($Nil)
    xs
    ))

(defn enumerate [xs]
  (enumerate* 0 xs))

(def modules
  "(Lux (List Text))"
  (fn [state]
    (return* state (|keys (get$ $MODULES state)))))

(defn when% [test body]
  "(-> Bool (Lux (,)) (Lux (,)))"
  (if test
    body
    (return nil)))
