#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

cd "$root"
bb --config "$root/deps.edn" -m jolt.aspect-packs.formal-antivacuity \
  "$root/proofs/models/wrapper-contract.contract.edn"
