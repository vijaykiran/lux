;;  Copyright (c) Eduardo Julian. All rights reserved.
;;  This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
;;  If a copy of the MPL was not distributed with this file,
;;  You can obtain one at http://mozilla.org/MPL/2.0/.

(ns lux.compiler.case
  (:require (clojure [set :as set]
                     [template :refer [do-template]])
            clojure.core.match
            clojure.core.match.array
            (lux [base :as & :refer [|do return* return fail fail* |let |case]]
                 [type :as &type]
                 [lexer :as &lexer]
                 [parser :as &parser]
                 [analyser :as &analyser]
                 [host :as &host]
                 [optimizer :as &o])
            [lux.analyser.case :as &a-case]
            [lux.compiler.base :as &&])
  (:import (org.objectweb.asm Opcodes
                              Label
                              ClassWriter
                              MethodVisitor)))

;; [Utils]
(defn ^:private pop-alt-stack [^MethodVisitor writer stack-depth]
  (cond (= 0 stack-depth)
        writer

        (= 1 stack-depth)
        (doto writer
          (.visitInsn Opcodes/POP))
        
        (= 2 stack-depth)
        (doto writer
          (.visitInsn Opcodes/POP2))
        
        :else ;; > 2
        (doto writer
          (.visitInsn Opcodes/POP2)
          (pop-alt-stack (- stack-depth 2)))))

(defn ^:private compile-pattern* [^MethodVisitor writer bodies stack-depth $else pm]
  "(-> MethodVisitor Case-Pattern (List Label) Int Label MethodVisitor)"
  (|case pm
    (&o/$ExecPM _body-idx)
    (|case (&/|at _body-idx bodies)
      (&/$Some $body)
      (doto writer
        (pop-alt-stack stack-depth)
        (.visitJumpInsn Opcodes/GOTO $body))

      (&/$None)
      (assert false))

    (&o/$PopPM)
    (doto writer
      (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_pop" "([Ljava/lang/Object;)[Ljava/lang/Object;"))

    (&o/$BindPM _var-id)
    (doto writer
      (.visitInsn Opcodes/DUP)
      (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_peek" "([Ljava/lang/Object;)Ljava/lang/Object;")
      (.visitVarInsn Opcodes/ASTORE _var-id))

    (&o/$BoolPM _value)
    (doto writer
      (.visitInsn Opcodes/DUP)
      (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_peek" "([Ljava/lang/Object;)Ljava/lang/Object;")
      (.visitTypeInsn Opcodes/CHECKCAST "java/lang/Boolean")
      (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z")
      (.visitLdcInsn _value)
      (.visitJumpInsn Opcodes/IF_ICMPNE $else))

    (&o/$IntPM _value)
    (doto writer
      (.visitInsn Opcodes/DUP)
      (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_peek" "([Ljava/lang/Object;)Ljava/lang/Object;")
      (.visitTypeInsn Opcodes/CHECKCAST "java/lang/Long")
      (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Long" "longValue" "()J")
      (.visitLdcInsn (long _value))
      (.visitInsn Opcodes/LCMP)
      (.visitJumpInsn Opcodes/IFNE $else))

    (&o/$RealPM _value)
    (doto writer
      (.visitInsn Opcodes/DUP)
      (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_peek" "([Ljava/lang/Object;)Ljava/lang/Object;")
      (.visitTypeInsn Opcodes/CHECKCAST "java/lang/Double")
      (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Double" "doubleValue" "()D")
      (.visitLdcInsn (double _value))
      (.visitInsn Opcodes/DCMPL)
      (.visitJumpInsn Opcodes/IFNE $else))

    (&o/$CharPM _value)
    (doto writer
      (.visitInsn Opcodes/DUP)
      (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_peek" "([Ljava/lang/Object;)Ljava/lang/Object;")
      (.visitTypeInsn Opcodes/CHECKCAST "java/lang/Character")
      (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Character" "charValue" "()C")
      (.visitLdcInsn _value)
      (.visitJumpInsn Opcodes/IF_ICMPNE $else))

    (&o/$TextPM _value)
    (doto writer
      (.visitInsn Opcodes/DUP)
      (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_peek" "([Ljava/lang/Object;)Ljava/lang/Object;")
      (.visitLdcInsn _value)
      (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Object" "equals" "(Ljava/lang/Object;)Z")
      (.visitJumpInsn Opcodes/IFEQ $else))

    (&o/$TuplePM _idx+)
    (|let [[_idx is-tail?] (|case _idx+
                             (&/$Left _idx)
                             (&/T [_idx false])

                             (&/$Right _idx)
                             (&/T [_idx true]))]
      (doto writer
        (.visitInsn Opcodes/DUP)
        (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_peek" "([Ljava/lang/Object;)Ljava/lang/Object;")
        (.visitTypeInsn Opcodes/CHECKCAST "[Ljava/lang/Object;")
        (.visitLdcInsn (int _idx))
        (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" (if is-tail? "product_getRight" "product_getLeft") "([Ljava/lang/Object;I)Ljava/lang/Object;")
        (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_push" "([Ljava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;")
        ))

    (&o/$VariantPM _idx+)
    (|let [$success (new Label)
           $fail (new Label)
           [_idx is-last] (|case _idx+
                            (&/$Left _idx)
                            (&/T [_idx false])

                            (&/$Right _idx)
                            (&/T [_idx true]))
           _ (doto writer
               (.visitInsn Opcodes/DUP)
               (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_peek" "([Ljava/lang/Object;)Ljava/lang/Object;")
               (.visitTypeInsn Opcodes/CHECKCAST "[Ljava/lang/Object;")
               (.visitLdcInsn (int _idx)))
           _ (if is-last
               (.visitLdcInsn writer "")
               (.visitInsn writer Opcodes/ACONST_NULL))]
      (doto writer
        (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "sum_get" "([Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;")
        (.visitInsn Opcodes/DUP)
        (.visitJumpInsn Opcodes/IFNULL $fail)
        (.visitJumpInsn Opcodes/GOTO $success)
        (.visitLabel $fail)
        (.visitInsn Opcodes/POP)
        (.visitJumpInsn Opcodes/GOTO $else)
        (.visitLabel $success)
        (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_push" "([Ljava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;")))

    (&o/$SeqPM _left-pm _right-pm)
    (doto writer
      (compile-pattern* bodies stack-depth $else _left-pm)
      (compile-pattern* bodies stack-depth $else _right-pm))

    (&o/$AltPM _left-pm _right-pm)
    (|let [$alt-else (new Label)]
      (doto writer
        (.visitInsn Opcodes/DUP)
        (compile-pattern* bodies (inc stack-depth) $alt-else _left-pm)
        (.visitLabel $alt-else)
        (.visitInsn Opcodes/POP)
        (compile-pattern* bodies stack-depth $else _right-pm)))
    ))

(defn ^:private compile-pattern [^MethodVisitor writer bodies pm $end]
  (|let [$else (new Label)]
    (doto writer
      (compile-pattern* bodies 1 $else pm)
      (.visitLabel $else)
      (.visitInsn Opcodes/POP)
      (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_fail" "()V")
      (.visitInsn Opcodes/ACONST_NULL)
      (.visitJumpInsn Opcodes/GOTO $end))))

(defn ^:private compile-bodies [^MethodVisitor writer compile bodies-labels ?bodies $end]
  (&/map% (fn [label+body]
            (|let [[_label _body] label+body]
              (|do [:let [_ (.visitLabel writer _label)]
                    _ (compile _body)
                    :let [_ (.visitJumpInsn writer Opcodes/GOTO $end)]]
                (return nil))))
          (&/zip2 bodies-labels ?bodies)))

;; [Resources]
(defn compile-case [compile ?value ?pm ?bodies]
  (|do [^MethodVisitor *writer* &/get-writer
        :let [$end (new Label)
              bodies-labels (&/|map (fn [_] (new Label)) ?bodies)]
        _ (compile ?value)
        :let [_ (doto *writer*
                  (.visitInsn Opcodes/ACONST_NULL)
                  (.visitInsn Opcodes/SWAP)
                  (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxUtils" "pm_stack_push" "([Ljava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;"))
              _ (compile-pattern *writer* bodies-labels ?pm $end)]
        _ (compile-bodies *writer* compile bodies-labels ?bodies $end)
        :let [_ (.visitLabel *writer* $end)]]
    (return nil)))
