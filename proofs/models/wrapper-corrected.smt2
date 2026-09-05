; One invocation of around-lifecycle or around-step.
; The query asks for any externally observable deviation from the wrapper contract.
(set-logic ALL)

(declare-const suppressed Bool)
(declare-const known-operation Bool)
(declare-const target-throws Bool)

(declare-const target-should-run Bool)
(declare-const expected-target-calls Int)
(declare-const expected-spans-started Int)
(declare-const expected-spans-ended Int)
(declare-const expected-error-status Bool)
(declare-const expected-return-identity Bool)
(declare-const expected-throw-identity Bool)
(declare-const expected-invalid-rejection Bool)

(declare-const actual-target-calls Int)
(declare-const actual-spans-started Int)
(declare-const actual-spans-ended Int)
(declare-const actual-error-status Bool)
(declare-const actual-return-identity Bool)
(declare-const actual-throw-identity Bool)
(declare-const actual-invalid-rejection Bool)
(declare-const violation Bool)

(assert (= target-should-run (or suppressed known-operation)))
(assert (= expected-target-calls (ite target-should-run 1 0)))
(assert (= expected-spans-started (ite (and (not suppressed) known-operation) 1 0)))
(assert (= expected-spans-ended expected-spans-started))
(assert (= expected-error-status
           (and (not suppressed) known-operation target-throws)))
(assert (= expected-return-identity
           (and target-should-run (not target-throws))))
(assert (= expected-throw-identity
           (and target-should-run target-throws)))
(assert (= expected-invalid-rejection
           (and (not suppressed) (not known-operation))))

; Encode the implementation control flow independently from the contract above:
; suppression is the outer branch; only its false branch validates and traces.
(assert (= actual-target-calls
           (ite suppressed 1 (ite known-operation 1 0))))
(assert (= actual-spans-started
           (ite suppressed 0 (ite known-operation 1 0))))
(assert (= actual-spans-ended actual-spans-started))
(assert (= actual-error-status
           (and (= actual-spans-started 1) target-throws)))
(assert (= actual-return-identity
           (and (= actual-target-calls 1) (not target-throws))))
(assert (= actual-throw-identity
           (and (= actual-target-calls 1) target-throws)))
(assert (= actual-invalid-rejection
           (and (not suppressed)
                (= actual-target-calls 0)
                (= actual-spans-started 0))))

(assert (= violation
  (or (not (= actual-target-calls expected-target-calls))
      (not (= actual-spans-started expected-spans-started))
      (not (= actual-spans-ended expected-spans-ended))
      (not (= actual-error-status expected-error-status))
      (not (= actual-return-identity expected-return-identity))
      (not (= actual-throw-identity expected-throw-identity))
      (not (= actual-invalid-rejection expected-invalid-rejection)))))
(assert (! violation :named contract_deviation))
