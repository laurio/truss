(ns taoensso.truss
  "An opinionated assertions API for Clojure/Script"
  {:author "Peter Taoussanis (@ptaoussanis)"}
  #+clj  (:require [taoensso.truss.impl :as impl :refer        (-invariant)])
  #+cljs (:require [taoensso.truss.impl :as impl :refer-macros (-invariant)]))

(comment (require '[taoensso.encore :as enc :refer (qb)]))

;;;; Core API

(defmacro have
  "Takes a pred and one or more vals. Tests pred against each val,
  trapping errors. If any pred test fails, throws a detailed assertion error.
  Otherwise returns input val/vals for convenient inline-use/binding.

  Respects *assert* value so tests can be elided from production for zero
  runtime costs.

  Provides a small, simple, flexible alternative to tools like core.typed,
  prismatic/schema, etc.

    ;; Will throw a detailed error message on invariant violation:
    (fn my-fn [x] (str/trim (have string? x)))

  May attach arbitrary debug info to assertion violations like:
    `(have string? x :data {:my-arbitrary-debug-info \"foo\"})`

  See also `have?`, `have!`."
  {:arglists '([pred (:in) x] [pred (:in) x & more-xs])}
  [& sigs] `(-invariant :assertion nil ~(:line (meta &form)) ~@sigs))

(defmacro have?
  "Like `have` but returns `true` on successful tests. This can be handy for use
  with :pre/:post conditions. Compare:
    (fn my-fn [x] {:post [(have  nil? %)]} nil) ; {:post [nil]} FAILS
    (fn my-fn [x] {:post [(have? nil? %)]} nil) ; {:post [true]} passes as intended"
  {:arglists '([pred (:in) x] [pred (:in) x & more-xs])}
  [& sigs] `(-invariant :assertion :truthy ~(:line (meta &form)) ~@sigs))

(defmacro have!
  "Like `have` but ignores *assert* value (so can never be elided). Useful for
  important conditions in production (e.g. security checks)."
  {:arglists '([pred (:in) x] [pred (:in) x & more-xs])}
  [& sigs] `(-invariant nil nil ~(:line (meta &form)) ~@sigs))

(defmacro have!?
  "Specialized cross between `have?` and `have!`. Not used often but can be
  handy for semantic clarification and/or to improve multi-val performance when
  the return vals aren't necessary.

  **WARNING**: Resist the temptation to use these in :pre/:post conds since
  they're always subject to *assert* and will interfere with the intent of the
  bang (`!`) here."
  {:arglists '([pred (:in) x] [pred (:in) x & more-xs])}
  [& sigs] `(-invariant :assertion :truthy ~(:line (meta &form)) ~@sigs))

(comment
  (let [x 5]      (have    integer? x))
  (let [x 5]      (have    string?  x))
  (let [x 5]      (have :! string?  x))
  (let [x 5 y  6] (have odd?  x x x y x))
  (let [x 0 y :a] (have zero? x x x y x))
  (have string? (do (println "eval1") "foo")
                (do (println "eval2") "bar"))
  (have number? (do (println "eval1") 5)
                (do (println "eval2") "bar")
                (do (println "eval3") 10))
  (have nil? false)
  (have nil)
  (have false)
  (have string? :in ["a" "b"])
  (have string? :in (if true  ["a" "b"] [1 2]))
  (have string? :in (if false ["a" "b"] [1 2]))
  (have string? :in (mapv str (range 10)))
  (have string? :in ["a" 1])
  (have string? :in ["a" "b"] ["a" "b"])
  (have string? :in ["a" "b"] ["a" "b" 1])
  ((fn foo [x] {:pre [(have? integer? x)]} (* x x)) "foo")
  (macroexpand '(have a))
  (have? [:or nil? string?] "hello")
  (macroexpand '(have? [:or nil? string?] "hello"))
  (have? [:set>= #{:a :b}]    [:a :b :c])
  (have? [:set<= [:a :b :c]] #{:a :b}))

(comment
  ;; HotSpot is great with these:
  (qb 10000
    (string? "a")
    (have?   "a")
    (have            string?  "a" "b" "c")
    (have? [:or nil? string?] "a" "b" "c")
    (have? [:or nil? string?] "a" "b" "c" :data "foo"))
  ;; [     5.59 26.48 45.82] ; 1st gen (macro form)
  ;; [     3.31 13.48 36.22] ; 2nd gen (fn form)
  ;; [0.82 1.75  7.57 27.05] ; 3rd gen (lean macro form)

  (qb 10000
    (have  string? :in ["foo" "bar" "baz"])
    (have? string? :in ["foo" "bar" "baz"]))

  (macroexpand '(have string? 5))
  (macroexpand '(have string? 5 :data "foo"))
  (macroexpand '(have string? 5 :data (enc/get-env)))
  (let [x :x]   (have string? 5 :data (enc/get-env)))

  (have string? 5)
  (have string? 5 :data {:a "a"})
  (have string? 5 :data {:a (/ 5 0)})

  ((fn [x]
     (let [a "a" b "b"]
       (have string? x :data {:env (enc/get-env)}))) 5))

;;;; Utils

(defn get-dynamic-assertion-data
  "Returns current value of dynamic assertion data"
  [] impl/*-?data*)

(defmacro with-dynamic-assertion-data
  "Executes body with dynamic assertion data bound to given value.
  This data will be included in any violation errors thrown by body."
  [data & body] `(binding [impl/*-?data* ~data] ~@body))

(comment (with-dynamic-assertion-data "foo" (have string? 5 :data "bar")))