#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

check_model() {
  model=$1
  expected=$2
  # Chiasmus specs omit solver commands; defensively discard any accidentally
  # committed commands before appending exactly one deterministic query.
  actual=$({ sed '/^(check-sat)/d; /^(get-model)/d' "$root/$model"; printf '(check-sat)\n'; } | z3 -in)
  if [ "$actual" != "$expected" ]; then
    echo "$model: expected $expected, got $actual" >&2
    exit 1
  fi
}

check_model proofs/models/wrapper-corrected.smt2 unsat
check_model proofs/models/wrapper-missing-finally-buggy.smt2 sat
check_model proofs/models/wrapper-double-proceed-buggy.smt2 sat
check_model proofs/models/wrapper-validate-before-suppression-buggy.smt2 sat
check_model proofs/models/wrapper-nonvacuity.smt2 sat

echo "PASS: core.async.flow wrapper formal controls"
