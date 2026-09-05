; Bounded observable contract for one around-lifecycle or around-step call.
;
; Inputs are suppression, operation validity, and target return/throw. The
; observation tuple records delegation, span lifecycle, error status, identity
; preservation, and pre-effect invalid-operation rejection. Implementations:
; 0 corrected nested control flow; 1 omits finally on throw; 2 calls an
; unsuppressed known target twice; 3 validates before checking suppression.
; Scenarios identify the five executable paths and one rejected observation
; used below.
(set-logic QF_LIA)

(declare-const implementation Int)
(declare-const scenario Int)

(declare-const suppressed Bool)
(declare-const known-operation Bool)
(declare-const target-throws Bool)

(declare-const observed-target-calls Int)
(declare-const observed-spans-started Int)
(declare-const observed-spans-ended Int)
(declare-const observed-error-status Bool)
(declare-const observed-return-identity Bool)
(declare-const observed-throw-identity Bool)
(declare-const observed-invalid-rejection Bool)

; Declarative reference relation. These definitions intentionally do not share
; derived helpers with the implementation relation.
(declare-const reference-target-should-run Bool)
(declare-const reference-target-calls Int)
(declare-const reference-spans-started Int)
(declare-const reference-spans-ended Int)
(declare-const reference-error-status Bool)
(declare-const reference-return-identity Bool)
(declare-const reference-throw-identity Bool)
(declare-const reference-invalid-rejection Bool)
(declare-const reference-valid Bool)

(assert (= reference-target-should-run (or suppressed known-operation)))
(assert (= reference-target-calls
           (ite reference-target-should-run 1 0)))
(assert (= reference-spans-started
           (ite (and (not suppressed) known-operation) 1 0)))
(assert (= reference-spans-ended reference-spans-started))
(assert (= reference-error-status
           (and (not suppressed) known-operation target-throws)))
(assert (= reference-return-identity
           (and reference-target-should-run (not target-throws))))
(assert (= reference-throw-identity
           (and reference-target-should-run target-throws)))
(assert (= reference-invalid-rejection
           (and (not suppressed) (not known-operation))))
(assert (= reference-valid
  (and (= observed-target-calls reference-target-calls)
       (= observed-spans-started reference-spans-started)
       (= observed-spans-ended reference-spans-ended)
       (= observed-error-status reference-error-status)
       (= observed-return-identity reference-return-identity)
       (= observed-throw-identity reference-throw-identity)
       (= observed-invalid-rejection reference-invalid-rejection))))

; Implementation relation derived independently from the wrapper's nested
; branches. The selector injects one fault without routing through reference
; outputs or helpers.
(declare-const implementation-target-calls Int)
(declare-const implementation-spans-started Int)
(declare-const implementation-spans-ended Int)
(declare-const implementation-error-status Bool)
(declare-const implementation-return-identity Bool)
(declare-const implementation-throw-identity Bool)
(declare-const implementation-invalid-rejection Bool)
(declare-const implementation-valid Bool)
(declare-const wrapper-contract-violation Bool)

(assert (= implementation-target-calls
  (ite (= implementation 2)
    (ite suppressed 1 (ite known-operation 2 0))
    (ite (= implementation 3)
      (ite known-operation 1 0)
      (ite suppressed 1 (ite known-operation 1 0))))))
(assert (= implementation-spans-started
  (ite suppressed 0 (ite known-operation 1 0))))
(assert (= implementation-spans-ended
  (ite (= implementation 1)
    (ite suppressed 0
      (ite (and known-operation (not target-throws)) 1 0))
    (ite suppressed 0 (ite known-operation 1 0)))))
(assert (= implementation-error-status
  (and (not suppressed) known-operation target-throws)))
(assert (= implementation-return-identity
  (ite (= implementation 3)
    (and known-operation (not target-throws))
    (and (or suppressed known-operation) (not target-throws)))))
(assert (= implementation-throw-identity
  (ite (= implementation 3)
    (and known-operation target-throws)
    (and (or suppressed known-operation) target-throws))))
(assert (= implementation-invalid-rejection
  (ite (= implementation 3)
    (not known-operation)
    (and (not suppressed) (not known-operation)))))
(assert (= implementation-valid
  (and (= observed-target-calls implementation-target-calls)
       (= observed-spans-started implementation-spans-started)
       (= observed-spans-ended implementation-spans-ended)
       (= observed-error-status implementation-error-status)
       (= observed-return-identity implementation-return-identity)
       (= observed-throw-identity implementation-throw-identity)
       (= observed-invalid-rejection implementation-invalid-rejection))))
(assert (= wrapper-contract-violation
           (not (= implementation-valid reference-valid))))

; Correct implementation: no observation is classified differently.
(push 1)
(assert (= implementation 0))
(assert wrapper-contract-violation)
(check-sat)
(pop 1)

; Mutation controls, each pinned to a revealing executable path.
(push 1)
(assert (= implementation 1))
(assert (= scenario 2))
(assert (= suppressed false))
(assert (= known-operation true))
(assert (= target-throws true))
(assert wrapper-contract-violation)
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 2))
(assert (= scenario 1))
(assert (= suppressed false))
(assert (= known-operation true))
(assert (= target-throws false))
(assert wrapper-contract-violation)
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 3))
(assert (= scenario 0))
(assert (= suppressed true))
(assert (= known-operation false))
(assert (= target-throws false))
(assert wrapper-contract-violation)
(check-sat)
(pop 1)

; Correct positive boundaries. Every query fixes the complete observable tuple
; and explicitly classifies both independently derived relations.
(push 1)
(assert (= implementation 0))
(assert (= scenario 0))
(assert (= suppressed true))
(assert (= known-operation false))
(assert (= target-throws false))
(assert (= observed-target-calls 1))
(assert (= observed-spans-started 0))
(assert (= observed-spans-ended 0))
(assert (= observed-error-status false))
(assert (= observed-return-identity true))
(assert (= observed-throw-identity false))
(assert (= observed-invalid-rejection false))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 1))
(assert (= suppressed false))
(assert (= known-operation true))
(assert (= target-throws false))
(assert (= observed-target-calls 1))
(assert (= observed-spans-started 1))
(assert (= observed-spans-ended 1))
(assert (= observed-error-status false))
(assert (= observed-return-identity true))
(assert (= observed-throw-identity false))
(assert (= observed-invalid-rejection false))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 2))
(assert (= suppressed false))
(assert (= known-operation true))
(assert (= target-throws true))
(assert (= observed-target-calls 1))
(assert (= observed-spans-started 1))
(assert (= observed-spans-ended 1))
(assert (= observed-error-status true))
(assert (= observed-return-identity false))
(assert (= observed-throw-identity true))
(assert (= observed-invalid-rejection false))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 3))
(assert (= suppressed false))
(assert (= known-operation false))
(assert (= target-throws false))
(assert (= observed-target-calls 0))
(assert (= observed-spans-started 0))
(assert (= observed-spans-ended 0))
(assert (= observed-error-status false))
(assert (= observed-return-identity false))
(assert (= observed-throw-identity false))
(assert (= observed-invalid-rejection true))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 4))
(assert (= suppressed true))
(assert (= known-operation false))
(assert (= target-throws true))
(assert (= observed-target-calls 1))
(assert (= observed-spans-started 0))
(assert (= observed-spans-ended 0))
(assert (= observed-error-status false))
(assert (= observed-return-identity false))
(assert (= observed-throw-identity true))
(assert (= observed-invalid-rejection false))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

; Negative boundary: a known returning operation observed with two target calls
; must be rejected by both the reference and corrected implementation.
(push 1)
(assert (= implementation 0))
(assert (= scenario 5))
(assert (= suppressed false))
(assert (= known-operation true))
(assert (= target-throws false))
(assert (= observed-target-calls 2))
(assert (= observed-spans-started 1))
(assert (= observed-spans-ended 1))
(assert (= observed-error-status false))
(assert (= observed-return-identity true))
(assert (= observed-throw-identity false))
(assert (= observed-invalid-rejection false))
(assert (not reference-valid))
(assert (not implementation-valid))
(check-sat)
(pop 1)
