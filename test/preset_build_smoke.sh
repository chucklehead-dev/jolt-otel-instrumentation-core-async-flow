#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
case ${JOLT_BIN:-} in
  /*) jolt=$JOLT_BIN ;;
  "") jolt=jolt ;;
  *) jolt=$repo/$JOLT_BIN ;;
esac
fixture=$repo/test-app-presets
tmp=${TMPDIR:-/tmp}/jolt-core-async-flow-preset-$$
deadline=${TIMEOUT_BIN:-timeout}
trap 'rm -rf "$tmp"' EXIT INT TERM
command -v "$deadline" >/dev/null 2>&1 || {
  echo "core.async.flow preset smoke requires $deadline" >&2
  exit 2
}
mkdir -p "$tmp"
cp "$fixture/deps.edn" "$tmp/deps.edn"
cp -R "$repo/src" "$tmp/src"
cp -R "$repo/test" "$tmp/test"

(cd "$tmp" && env JOLT_PWD="$tmp" \
  JOLT_CACHE_DIR="$tmp/cache" \
  JOLT_GITLIBS_DIR=${JOLT_GITLIBS_DIR:-$tmp/gitlibs} \
  "$jolt" aspects manifest) >"$tmp/manifest.out"
grep -q "core-async-flow-0.1.0.edn" "$tmp/manifest.out"

(cd "$tmp" && env JOLT_PWD="$tmp" \
  JOLT_CACHE_DIR="$tmp/cache" \
  JOLT_GITLIBS_DIR=${JOLT_GITLIBS_DIR:-$tmp/gitlibs} \
  "$jolt" aspects plan) >"$tmp/plan.edn"
grep -q ":id :otel.core-async-flow/basic" "$tmp/plan.edn"
grep -q "otel.instrumentation.core-async-flow/aspect-provider" "$tmp/plan.edn"

(cd "$tmp" && env JOLT_PWD="$tmp" \
  JOLT_CACHE_DIR="$tmp/cache" \
  JOLT_GITLIBS_DIR=${JOLT_GITLIBS_DIR:-$tmp/gitlibs} \
  "$jolt" build -m jolt.aspect-packs.scenario.core-async-flow \
    -o target/core-async-flow-preset)
"$deadline" 30s "$tmp/target/core-async-flow-preset"

if "$deadline" 5s "$tmp/target/core-async-flow-preset" fail \
     >"$tmp/failure.out" 2>&1; then
  echo "core.async.flow failure probe unexpectedly succeeded" >&2
  exit 1
else
  failure_status=$?
fi
test "$failure_status" -eq 1 || {
  echo "core.async.flow failure probe exited $failure_status, expected 1" >&2
  cat "$tmp/failure.out" >&2
  exit 1
}
grep -q "CORE-ASYNC-FLOW-OTEL-PRESET FAILED intentional smoke failure" \
  "$tmp/failure.out"

grep -q "otel.instrumentation.core-async-flow/aspect-provider" \
  "$tmp/target/core-async-flow-preset-aspects.edn"
(cd "$repo" && "$jolt" -m jolt.aspect-packs.effect-evidence woven \
  "$tmp/target/core-async-flow-preset.build/effects.edn" \
  "$tmp/target/core-async-flow-preset-aspects.edn")

echo "PASS: core.async.flow package-owned OTel preset"
