(ns taoensso.truss.impl
  "Private implementation details."
  (:require [clojure.set :as set])
  (:refer-clojure :exclude [some?])
  #?(:cljs
     (:require-macros
      [taoensso.truss.impl
       :refer [compile-if catching -invar]])))

(comment (require '[taoensso.encore :as enc]))

;;;; TODO
;; - Namespaced kw registry like clojure.spec, (truss/def <kw> <pred>)?
;; - Ideas for easier sharing of composed preds?

;;;; Manual Encore imports
;; A bit of a nuisance but:
;;   - Allows Encore to depend on Truss (esp. nb for back-compatibility wrappers).
;;   - Allows Truss to be entirely dependency free.

#?(:clj (defmacro if-cljs [then else] (if (:ns &env) then else)))
#?(:clj
   (defmacro compile-if [test then else]
     (if (try (eval test) (catch Throwable _ false))
       `(do ~then)
       `(do ~else))))

#?(:clj
   (defmacro catching "Cross-platform try/catch/finally."
     ;; We badly need something like http://dev.clojure.org/jira/browse/CLJ-1293
     ;; TODO js/Error instead of :default as temp workaround for http://goo.gl/UW7773
     ([try-expr                     ] `(catching ~try-expr ~'_ nil))
     ([try-expr error-sym catch-expr]
      `(if-cljs
         (try ~try-expr (catch js/Error  ~error-sym ~catch-expr))
         (try ~try-expr (catch Throwable ~error-sym ~catch-expr))))
     ([try-expr error-sym catch-expr finally-expr]
      `(if-cljs
         (try ~try-expr (catch js/Error  ~error-sym ~catch-expr) (finally ~finally-expr))
         (try ~try-expr (catch Throwable ~error-sym ~catch-expr) (finally ~finally-expr))))))

(defn rsome   [pred coll]       (reduce (fn [acc in] (when-let [p (pred in)] (reduced p))) nil coll))
(defn revery? [pred coll]       (reduce (fn [acc in] (if (pred in) true (reduced nil))) true coll))
(defn revery  [pred coll] (when (reduce (fn [acc in] (if (pred in) true (reduced nil))) true coll) coll))

(comment (revery integer? [1 2 3]) (revery integer? nil))

#?(:cljs (defn ^boolean some? [x] (if (nil? x) false true))
   :clj
   (defn some?
     {:inline (fn [x] `(if (identical? ~x nil) false true))}
     [x] (if (identical? x nil) false true)))

(defn ensure-set [x] (if (set? x) x (set x)))
(let [ensure-set ensure-set]
  (defn #?(:clj ks=      :cljs ^boolean ks=)      [ks m] (=             (set (keys m)) (ensure-set ks)))
  (defn #?(:clj ks<=     :cljs ^boolean ks<=)     [ks m] (set/subset?   (set (keys m)) (ensure-set ks)))
  (defn #?(:clj ks>=     :cljs ^boolean ks>=)     [ks m] (set/superset? (set (keys m)) (ensure-set ks)))
  (defn #?(:clj ks-nnil? :cljs ^boolean ks-nnil?) [ks m] (revery?     #(some? (get m %))           ks)))

;;;; Truss

(defn default-error-fn [data_]
  (let [data @data_]
    (throw (ex-info @(:msg_ data) (dissoc data :msg_)))))

(def ^:dynamic *data* nil)
(def ^:dynamic *error-fn* default-error-fn)

(defn  non-throwing [pred] (fn [x] (catching (pred x))))
(defn- non-throwing?
  "Returns true for some common preds that are naturally non-throwing."
  [p]
  #?(:cljs false ; Would need `resolve`; other ideas?
     :clj
     (or
       (keyword? p)
       (map?     p)
       (set?     p)
       (boolean
         (#{nil? #_some? string? integer? number? symbol? keyword? float?
            set? vector? coll? list? ifn? fn? associative? sequential? delay?
            sorted? counted? reversible? true? false? identity not boolean}
           (if (symbol? p) (when-let [v (resolve p)] @v) p))))))

(defn -xpred
  "Expands any special predicate forms and returns [<expanded-pred> <non-throwing?>]."
  [pred]
  (if-not (vector? pred)
    [pred (non-throwing? pred)]
    (let [[type a1 a2 a3] pred]
      (assert a1 "Special predicate [<special-type> <arg>] form w/o <arg>")
      (case type
        :set=             [`(fn [~'x] (=             (ensure-set ~'x) (ensure-set ~a1))) false]
        :set<=            [`(fn [~'x] (set/subset?   (ensure-set ~'x) (ensure-set ~a1))) false]
        :set>=            [`(fn [~'x] (set/superset? (ensure-set ~'x) (ensure-set ~a1))) false]
        :ks=              [`(fn [~'x] (ks=      ~a1 ~'x)) false]
        :ks<=             [`(fn [~'x] (ks<=     ~a1 ~'x)) false]
        :ks>=             [`(fn [~'x] (ks>=     ~a1 ~'x)) false]
        :ks-nnil?         [`(fn [~'x] (ks-nnil? ~a1 ~'x)) false]
        (    :el     :in) [`(fn [~'x]      (contains? (ensure-set ~a1) ~'x))  false]
        (:not-el :not-in) [`(fn [~'x] (not (contains? (ensure-set ~a1) ~'x))) false]

        :n=               [`(fn [~'x] (=  (count ~'x) ~a1)) false]
        :n>=              [`(fn [~'x] (>= (count ~'x) ~a1)) false]
        :n<=              [`(fn [~'x] (<= (count ~'x) ~a1)) false]

        ;; Pred composition
        (let [self (fn [?pred] (when ?pred (-xpred ?pred)))

              ;; Support recursive expansion:
              [[a1 nt-a1?] [a2 nt-a2?] [a3 nt-a3?]] [(self a1) (self a2) (self a3)]

              nt-a1    (when a1 (if nt-a1? a1 `(non-throwing ~a1)))
              nt-a2    (when a2 (if nt-a2? a2 `(non-throwing ~a2)))
              nt-a3    (when a3 (if nt-a3? a3 `(non-throwing ~a3)))
              nt-comp? (cond a3 (and nt-a1? nt-a2? nt-a3?)
                             a2 (and nt-a1? nt-a2?)
                             a1 nt-a1?)]

          (case type
            :and ; all-of
            (cond
              a3 [`(fn [~'x] (and (~a1 ~'x) (~a2 ~'x) (~a3 ~'x))) nt-comp?]
              a2 [`(fn [~'x] (and (~a1 ~'x) (~a2 ~'x))) nt-comp?]
              a1 [a1 nt-a1?])

            :or  ; any-of
            (cond
              a3 [`(fn [~'x] (or (~nt-a1 ~'x) (~nt-a2 ~'x) (~nt-a3 ~'x))) true]
              a2 [`(fn [~'x] (or (~nt-a1 ~'x) (~nt-a2 ~'x))) true]
              a1 [a1 nt-a1?])

            :not ; complement/none-of
            ;; Note that it's a little ambiguous whether we'd want
            ;; non-throwing behaviour here or not so choosing to interpret
            ;; throws as undefined to minimize surprise
            (cond
              a3 [`(fn [~'x] (not (or (~a1 ~'x) (~a2 ~'x) (~a3 ~'x)))) nt-comp?]
              a2 [`(fn [~'x] (not (or (~a1 ~'x) (~a2 ~'x)))) nt-comp?]
              a1 [`(fn [~'x] (not     (~a1 ~'x))) nt-a1?])))))))

(comment
  (-xpred string?)
  (-xpred [:or string? integer? :foo]) ; t
  (-xpred [:or string? integer? seq])  ; f
  (-xpred [:or string? integer? [:and number? integer?]]) ; t
  (-xpred [:or string? integer? [:and number? pos?]])     ; f
  )

;; #?(:clj
;;    (defn- fast-pr-str
;;      "Combination `with-out-str`, `pr`. Ignores *print-dup*."
;;      [x]
;;      (let [w (java.io.StringWriter.)]
;;        (print-method x w)
;;        (.toString      w))))

;; (comment (enc/qb 1e5 (pr-str {:a :A}) (fast-pr-str {:a :A})))

(defn- error-message
  ;; Temporary, to support Clojure 1.9
  ;; Clojure 1.10+ now has `ex-message`
  [x]
  #?(:clj  (when (instance? Throwable x) (.getMessage ^Throwable x))
     :cljs (when (instance? js/Error  x) (.-message              x))))

(deftype WrappedError [val])
(defn -assertion-error [msg] #?(:clj (AssertionError. msg) :cljs (js/Error. msg)))
(def  -dummy-error #?(:clj (Object.) :cljs (js-obj)))
(defn -invar-violation!
  ;; - http://dev.clojure.org/jira/browse/CLJ-865 would be handy for line numbers.
  [elidable? ns-sym ?line pred arg ?err ?data-fn]
  (when-let [error-fn *error-fn*]
    (error-fn ; Nb consumer must deref while bindings are still active
     (delay
      (let [instant     #?(:clj (java.util.Date.) :cljs (js/Date.))
            undefn-arg? (instance? WrappedError arg)
            arg-val     (if undefn-arg? 'truss/undefined-arg       arg)
            arg-type    (if undefn-arg? 'truss/undefined-arg (type arg))

            ;; arg-str
            ;; (cond
            ;;   undefn-arg? "<truss/undefined-arg>"
            ;;   (nil? arg)  "<truss/nil>"
            ;;   :else
            ;;   (binding [*print-readably* false
            ;;             *print-length*   3]
            ;;     #?(:clj  (fast-pr-str arg)
            ;;        :cljs (pr-str      arg))))

            ?err
            (cond
              (identical? -dummy-error ?err) nil
              (instance?  WrappedError ?err)
              (.-val     ^WrappedError ?err)
              :else                    ?err)

            msg_
            (delay
              (let [msg (str "Invariant failed at " ns-sym (when ?line (str "|" ?line)) ": "
                          (list pred arg-val))]

                (if-let [err ?err]
                  (let [err-msg #_(ex-message err) (error-message err)]
                    (if undefn-arg?
                      (str msg "\r\n\r\nError evaluating arg: "  err-msg)
                      (str msg "\r\n\r\nError evaluating pred: " err-msg)))
                  msg)))

            ?data
            (let [dynamic *data*
                  arg
                  (when-let [data-fn ?data-fn]
                    (catching (data-fn) e
                      {:truss/error e}))]

              (when (or   dynamic      arg)
                {:dynamic dynamic :arg arg}))

            output
            {:msg_  msg_
             :dt    instant
             :pred  pred
             :arg   {:value arg-val
                     :type  arg-type}

             :loc {:ns ns-sym :line ?line}
             :env {:elidable?  elidable?
                   :*assert*   *assert*}}

            output (if-let [v ?data] (assoc output :data v) output)
            output (if-let [v ?err]  (assoc output :err  v) output)]

        output)))))

(defn- ns-sym [] (symbol (str *ns*)))

#?(:clj
   (defmacro -invar
     "Written to maximize performance + minimize post Closure+gzip Cljs code size."
     [elidable? truthy? line pred x ?data-fn]
     (let [non-throwing-x? (not (list? x)) ; Pre-evaluated (common case)
           [pred* non-throwing-pred?] (-xpred pred)]

       (if non-throwing-x? ; Common case
         (if non-throwing-pred? ; Common case
           `(if (~pred* ~x)
              ~(if truthy? true x)
              (-invar-violation! ~elidable? '~(ns-sym) ~line '~pred ~x nil ~?data-fn))

           `(let [~'e (catching (if (~pred* ~x) nil -dummy-error) ~'e ~'e)]
              (if (nil? ~'e)
                ~(if truthy? true x)
                (-invar-violation! ~elidable? '~(ns-sym) ~line '~pred ~x ~'e ~?data-fn))))

         (if non-throwing-pred?
           `(let [~'z (catching ~x ~'e (WrappedError. ~'e))
                  ~'e (if (instance? WrappedError ~'z)
                        ~'z
                        (if (~pred* ~'z) nil -dummy-error))]

              (if (nil? ~'e)
                ~(if truthy? true 'z)
                (-invar-violation! ~elidable? '~(ns-sym) ~line '~pred ~'z ~'e ~?data-fn)))

           `(let [~'z (catching ~x ~'e (WrappedError. ~'e))
                  ~'e (catching
                        (if (instance? WrappedError ~'z)
                          ~'z
                          (if (~pred* ~'z) nil -dummy-error)) ~'e ~'e)]

              (if (nil? ~'e)
                ~(if truthy? true 'z)
                (-invar-violation! ~elidable? '~(ns-sym) ~line '~pred ~'z ~'e ~?data-fn))))))))

(comment
  (macroexpand '(-invar true false 1      string?    "foo"             nil)) ; Type 0
  (macroexpand '(-invar true false 1 [:or string?]   "foo"             nil)) ; Type 0
  (macroexpand '(-invar true false 1    #(string? %) "foo"             nil)) ; Type 1
  (macroexpand '(-invar true false 1      string?    (str "foo" "bar") nil)) ; Type 2
  (macroexpand '(-invar true false 1    #(string? %) (str "foo" "bar") nil)) ; Type 3
  (enc/qb 1e6
    (string? "foo")                                          ; Baseline
    (-invar true false 1   string?    "foo"             nil) ; Type 0
    (-invar true false 1 #(string? %) "foo"             nil) ; Type 1
    (-invar true false 1   string?    (str "foo" "bar") nil) ; Type 2
    (-invar true false 1 #(string? %) (str "foo" "bar") nil) ; Type 3
    (try
      (string? (try "foo" (catch Throwable _ nil)))
      (catch Throwable _ nil)))
  ;; [41.86 50.43 59.56 171.12 151.2 42.0]

  (-invar false false 1 integer? "foo"   nil) ; Pred failure example
  (-invar false false 1 zero?    "foo"   nil) ; Pred error example
  (-invar false false 1 zero?    (/ 5 0) nil) ; Form error example
  )

#?(:clj
   (defmacro -invariant [elidable? truthy? line & args]
     (let [bang?      (= (first args) :!) ; For back compatibility, undocumented
           elidable?  (and elidable? (not bang?))
           elide?     (and elidable? (not *assert*))
           args       (if bang? (next args) args)
           in?        (= (second args) :in) ; (have pred :in xs1 xs2 ...)
           args       (if in? (cons (first args) (nnext args)) args)

           data?      (and (> (count args) 2) ; Distinguish from `:data` pred
                           (= (last (butlast args)) :data))
           ?data-fn   (when data? `(fn [] ~(last args)))
           args       (if data? (butlast (butlast args)) args)

           auto-pred? (= (count args) 1) ; Unique common case: (have ?x)
           pred       (if auto-pred? 'taoensso.truss.impl/some? (first args))
           [?x1 ?xs]  (if auto-pred?
                        [(first args) nil]
                        (if (nnext args) [nil (next args)] [(second args) nil]))
           single-x?  (nil? ?xs)
           in-fn
           `(fn [~'__in] ; Will (necessarily) lose exact form
              (-invar ~elidable? ~truthy? ~line ~pred ~'__in ~?data-fn))]

       (if elide?
         (if truthy?
           true
           (if single-x? ?x1 (vec ?xs)))

         (if-not in?

           (if single-x?
             ;; (have pred x) -> x
             `(-invar ~elidable? ~truthy? ~line ~pred ~?x1 ~?data-fn)

             ;; (have pred x1 x2 ...) -> [x1 x2 ...]
             (if truthy?
               `(do ~@(mapv (fn [x] `(-invar ~elidable? ~truthy? ~line ~pred ~x ~?data-fn)) ?xs) true)
               (do    (mapv (fn [x] `(-invar ~elidable? ~truthy? ~line ~pred ~x ~?data-fn)) ?xs))))

           (if single-x?

             ;; (have? pred :in xs) -> bool
             ;; (have  pred :in xs) -> xs
             (if truthy?
               `(taoensso.truss.impl/revery? ~in-fn ~?x1)
               `(taoensso.truss.impl/revery  ~in-fn ~?x1))

             ;; (have? pred :in xs1 xs2 ...) -> [bool1 ...]
             ;; (have  pred :in xs1 xs2 ...) -> [xs1   ...]
             (if truthy?
               `(do ~@(mapv (fn [xs] `(taoensso.truss.impl/revery? ~in-fn ~xs)) ?xs) true)
               (do    (mapv (fn [xs] `(taoensso.truss.impl/revery  ~in-fn ~xs)) ?xs)))))))))
