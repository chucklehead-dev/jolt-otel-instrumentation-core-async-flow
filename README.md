# Jolt OpenTelemetry instrumentation for core.async.flow

Compiler-selected OpenTelemetry spans for application-owned lifecycle wrappers
and step functions around Jolt's bundled `clojure.core.async.flow`.

The library consumes the semantic join-point ABI published by
`jolt-aspect-packs`. Merely adding it to the classpath activates nothing. Select
the package preset at build time:

```clojure
{:jolt/build
 {:instrumentation-presets
  ["META-INF/jolt/instrumentation/core-async-flow/basic.edn"]}}
```

The basic profile emits internal spans for create, start, pause, resume, ping,
inject, stop, and the describe/init/transition/transform process steps. It keeps
only bounded counts, the requested ping timeout, closed lifecycle/transition
and workload names, and booleans. Graphs, process
ids and state, channel coordinates, messages, outputs, exception objects, and
exception messages are not retained.

This package does not modify or target private functions in the alpha flow
scheduler. Applications expose stable wrappers and process step functions using
the aspect pack's source-annotation pattern; those functions call the real flow
implementation. Synchronous spans retain the current OTel parent. Work handed
to an executor starts from whatever context that executor establishes; this
package does not claim implicit cross-executor propagation.

Run the focused provider suite with Jolt on `PATH`:

```sh
jolt -M:test
```

The advice control contract also has a checked Z3 model for suppression order,
exactly-once delegation, span finalization, and return/throw identity. It
includes three satisfiable mutation controls, five matching positive runtime
scenarios, and an explicitly rejected observation; see
[`docs/proofs/wrapper-contract.md`](docs/proofs/wrapper-contract.md).

```sh
sh test/formal/check_wrapper_models.sh
```

The wrapper invokes the reusable structural anti-vacuity checker from the exact
`jolt-aspect-packs` revision pinned in `deps.edn`; it does not merely compare raw
solver labels.

Run the source-annotation, preset, binary, runtime, and effect-evidence smoke
with an aspect-capable compiler:

```sh
JOLT_BIN=/absolute/path/to/jolt sh test/preset_build_smoke.sh
```
