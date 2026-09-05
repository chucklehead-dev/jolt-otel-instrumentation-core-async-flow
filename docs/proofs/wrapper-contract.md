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

`wrapper-contract.smt2` encodes the nested implementation branches separately
from the declarative contract and asks for an observable classification
deviation. The shared inputs and complete observation tuple are raw variables;
the two relations share no derived decision helpers. Z3 returns `unsat` for the
corrected selector: those relations are equivalent over the finite Boolean
input domain. The source-to-model map above, executable scenarios, and mutation
controls are the evidence that connects this bounded equivalence check to
production code.

Three selector branches use the same disagreement predicate and each return
`sat` with a concrete counterexample:

- missing `finally` leaves a throwing operation's span unended;
- double `proceed` violates exactly-once delegation;
- validating before suppression rejects a suppressed unknown operation.

Six separately scoped boundary queries must return `sat`. Five explicitly
classify complete corrected observations as accepted: suppressed unknown return
and throw, known return, known throw, and unsuppressed unknown rejection. A
sixth explicitly classifies a double-delegation observation as rejected. The
matching executable tests invoke the production advice for the five positive
paths, including exported-span counts and return/throw identity across both
wrapper families.

The machine-readable `wrapper-contract.contract.edn` is checked by the
reusable `jolt-aspect-packs` anti-vacuity CLI pinned in `deps.edn`. In addition
to executing the solver expectations, that gate rejects reference aliases,
shared derived decision helpers, missing mutation or boundary controls,
unclassified boundaries, vacuous boundaries, and unexpected solver queries.

These are bounded model checks over the wrapper's observable state, not a proof
of the OTel SDK or the Jolt compiler.

Run the checked solver expectations with:

```sh
sh test/formal/check_wrapper_models.sh
```

Expected results are one corrected `unsat`, three mutation-control `sat`, and
six classified boundary `sat` results.
