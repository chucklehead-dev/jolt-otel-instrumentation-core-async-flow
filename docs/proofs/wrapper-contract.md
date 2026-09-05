# Core.async.flow wrapper contract

The OpenTelemetry advice has one small correctness-critical control boundary:
suppression must bypass validation and tracing, while an unsuppressed known
operation must call its target exactly once and end its span on both return and
throw. Unknown unsuppressed operations must fail before target or span effects.
Return and thrown values retain object identity.

## Source-to-model map

The model abstracts one call to either `around-lifecycle` or `around-step`.
Both functions have the same control shape and delegate span ownership to
`traced`:

- `suppressed` corresponds to `context/instrumentation-suppressed?`;
- `known-operation` corresponds to membership in `lifecycle-names` or
  `step-names` before `invalid-operation!`;
- `target-calls` counts calls to `proceed`;
- `spans-started` and `spans-ended` correspond to `trace/start-span` and the
  `trace/end!` in `finally`;
- `error-status` corresponds to the catch branch's status update;
- identity observations correspond to returning `result` and rethrowing the
  original `error`.

The model deliberately omits attribute extraction and asynchronous context
propagation. Attribute privacy/boundedness is covered by the executable suite;
the package explicitly makes no implicit cross-executor propagation claim.

## Proof obligations and controls

`wrapper-corrected.smt2` encodes the nested implementation branches separately
from the declarative contract and asks for an observable deviation. Z3 returns
`unsat`: those relations are equivalent over the finite Boolean input domain.
The source-to-model map above, executable scenarios, and mutation controls are
the evidence that connects this bounded equivalence check to production code.

The three buggy models use the same deviation predicate and each return `sat`
with a concrete counterexample:

- missing `finally` leaves a throwing operation's span unended;
- double `proceed` violates exactly-once delegation;
- validating before suppression rejects a suppressed unknown operation.

`wrapper-nonvacuity.smt2` returns `sat` only while five concrete corrected
paths coexist: suppressed unknown return and throw, known return, known throw,
and unsuppressed unknown rejection. The matching executable tests invoke the
production advice for those same paths, including exported-span counts and
return/throw identity across both wrapper families.

These are bounded model checks over the wrapper's observable state, not a proof
of the OTel SDK or the Jolt compiler.

Run the checked solver expectations with:

```sh
sh test/formal/check_wrapper_models.sh
```

Expected results are one corrected `unsat`, three mutation-control `sat`, and
one five-path non-vacuity `sat`.
