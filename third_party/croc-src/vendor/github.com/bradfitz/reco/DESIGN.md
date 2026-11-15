# Reco design

Experimental research prototype. Do not depend on it. APIs, semantics, and
implementation choices may change without compatibility guarantees.

This document describes the public library as implemented today and the next
design questions. Keep it in sync with changes to runtime semantics, primitive
values, collection operators, and the distributed demo.

## Purpose and package boundaries

Reco explores typed, incremental reactive computation in Go. Applications
declare mutable inputs and derived values, commit atomic mutations, and watch
the resulting values/deltas. Updating a small part of a large collection should
not force every consumer to copy or compare the whole collection.

- The root package owns graphs, node definitions, transactions, evaluation,
  subscriptions, scopes, and immutable value/delta types.
- `nodes` provides reusable operators built exclusively with those public APIs.
  Applications can implement their own operators without extending reco.
- `metagraph` experiments with explicitly bound remote mirrors over WebSockets.
- `cmd/webdemo` illustrates leaf edits, transactions, incremental computation,
  and optionally two processes owning different inputs.
- `recotestcontrol` is a small in-memory implementation of the public Tailscale
  integration-test control API. It is an example application, not a deployable
  control service. See its README for compatibility and test instructions.

## Definitions, instances, and names

`Node[T]` is a typed handle. `Data`, `SetData`, `MapData`, `MultisetData`,
`StructData`, `Func`, and `Operator` construct definitions. `ClassName()` returns
the definition's `NodeClassName`, an exact case-sensitive string.

Class names are unique within a graph/scope; they are not collection keys.
Reusing the same definition is allowed. Separate definitions with the same name
in one scope are an error. Definitions can be used in independent graphs.
`Scope` and `In(scope, definition)` instantiate a reusable DAG in a local
namespace. Explicitly scoped inputs permit dependencies between scopes in the
same graph. Scopes are not security boundaries or distributed addresses.

`Graph.Register` registers an output's dependency closure and rejects cycles.
The graph tracks dependency ordering and affected edges rather than scanning
every registered definition on each update.

## Transactions and evaluation

`Graph.Update` serializes a transaction. Edits to several leaves become visible
at one settled boundary. Returning an error aborts the transaction. Consumers
run after their inputs settle; diamonds do not expose mixed intermediate state.
Equal outputs suppress downstream work and notifications.

`Func` is a convenient typed dependency-struct adapter. It fills the whole
struct on evaluation. `Operator` supplies a per-instance compute factory and
reads declared inputs with `Input(eval, node)`. Mutable incremental caches belong
inside the factory, never in shared definition state. Compute must not call back
into graph mutation/read/subscription APIs or perform external side effects.

`Eval` is not `context.Context`. It and its iterators cannot outlive evaluation.
`Result[T]` carries value, error, and partial-result status; downstream operators
decide how to handle input errors. Published values must remain immutable.

### Changed inputs

`Eval.ChangedInputs()` yields typed declared node handles. First evaluation
reports all inputs, including after eviction or reconfiguration. Later calls
report value/result-status changes since the preceding invocation, in unspecified
order. Repeated declarations yield once; distinct declared aliases of one scoped
instance each retain their original handle. Iteration is reusable during the
call and supports early termination.

Failed evaluations still advance that boundary. Operators that leave work
unapplied must retain it for recovery. `SumMultisets`, for example, retains
pending inputs until a whole aggregation succeeds.

The runtime indexes scoped inputs and readiness once per active instance.
Subsequent readiness checks visit changed edges, and `Input` performs an indexed
lookup without copying a full input table. Wide incremental operators should
use ChangedInputs; it cannot eliminate loops in an application's own compute
function. First activation and retained indexes still cost O(number of inputs).

## Primitive values and atomic deltas

Scalars use `Data[T]` and `Set`. Sets, maps, and multisets use persistent HAMT
storage from `github.com/benbjohnson/immutable`; point changes share unchanged
structure instead of copying a Go map. Published snapshots support callback
`Range` methods and native Go range iterators.

- `SetDelta`: clear all, remove listed keys, then add listed keys, atomically.
  Clear plus add is an exact replacement.
- `MapDelta`: clear all, remove keys, then put key/value pairs atomically.
- `MultisetDelta`: clear, remove, put absolute counts, then apply signed count
  adjustments. Counts must fit a nonnegative int64; zero removes a key.
  Invalid adjustments/overflow fail without publishing partial changes.
- `StructSnapshot[T]` and typed `Field[T,V]` descriptors represent fixed records
  without a heterogeneous `Map[string, any]`. `StructDelta` changes several
  fields atomically. Collection-valued fields retain their nested delta paths.

`Changes()` describes a snapshot's immediate delta. Incremental consumers should
use `ChangesSince(previous)`: shared roots and adjacent lineage avoid scanning,
while arbitrary replacements or skipped bases may require reconciliation.
Versions are graph-local monotonic values, not global clocks.

`StructChanges.Then` composes consecutive changes for coalescing delivery.
Field accessors let encoders inspect just changed scalar fields or collection
entries. Custom immutable values can implement `ValueEqualer` and
`VersionedValue` to preserve application-appropriate equality/version behavior.

Collection keys must have reflexive, comparable equality: no NaNs or dynamically
non-comparable values hidden inside interface keys. Pointer identity is retained
where Go equality applies. Nil/zero values are values, not removal markers.

## Reusable operators

`nodes` contains:

- `Union`, `Intersection`, `Xor`, and `Difference`. XOR means odd membership
  parity; Difference means the first set minus the union of the others.
- `MapSet`: preserve each set key and compute only the value for newly added
  keys. Its pure callback cannot introduce per-key reactive dependencies.
- `MapKeys` and `MapValues`: project maps to keys or distinct comparable values.
- `Map`: preserve map keys and transform changed values.
- `Index`: group map keys into sets; `InvertSets`: reverse a set-valued map.
- `CountBy`: count map entries by a pure grouping function.
- `GroupCounts`: join a set-valued map and a same-key grouping map, counting
  member contributions per group without enumerating Cartesian products.
- `SumMultisets`: add counts, including repeated inputs, not maximum-count bag
  union. `Distinct` projects positive-count keys and propagates zero crossings.
- `Struct`: assemble typed fields while preserving nested collection deltas.

Multi-input set operators and SumMultisets visit changed input handles and their
delta keys, not the entire input list. Duplicate contributions are weighted:
`Xor(a,a)` cancels and `SumMultisets(a,a)` doubles. Transactions aggregate all
contributions before publishing, so transfers need not notify downstream nodes.
Errors, clear/replacement, and actual large output changes may require more work.

## Demand and lifetime

`GraphOptions{DemandDriven: true}` computes only values with demand from active
subscribers or downstream computations. `Read` acquires temporary demand.
Last release discards unused derived values and operator caches; a later read
or subscription rebuilds them. Definitions and ordinary data remain available.

`BindDurable` allows application-provided loaders to reload evicted data leaves.
`UpdateDurable` publishes changes to resident leaves without loading unused
ones just to mutate them. This is a cache-lifetime facility, not a transactional
database or durability guarantee; applications own persistence and failure policy.
Loaders run under graph serialization and must avoid recursive graph access.

`Reconfigure` atomically replaces a function instance's dependencies/factory.
It requires a demand-driven graph and already registered, lower-ranked inputs.
It preserves the instance handle, not its previous incremental cache. Generic
dynamic per-entry dependencies remain a separate design problem.

## Subscriptions and remote mirrors

Subscriptions deliver settled snapshots/events. Collection and struct adapters
provide atomic initial-snapshot-plus-watch and typed changes. Callbacks should
be short; encoding, blocking I/O, and application-level buffering belong outside
the graph callback. Slow-consumer policy and byte-bounded queues are not universal
runtime guarantees. Executor scheduling/coalescing remains synchronous for now.

The experimental `metagraph` package multiplexes watches over a WebSocket rather
than allocating a transport stream per watched value. Peers explicitly bind
authoritative leaves and remote mirrors using compatible codecs. Reconnection
resynchronizes snapshots; subsequent changes are incremental. It is an eventual
consistency experiment, not consensus or cross-process atomic transactions.
Automatic Locator routing, transparent generic-key addressing, authentication,
and production transport hardening remain future work.

Run `make -C cmd/webdemo multiproc` for the two-process demo. Browsers watch live
computed values while each process owns different leaf inputs. Its browser
protocol is an implementation detail, not the peer protocol or a stable API.

## Verification and remaining work

Run `go test ./...` and `go test -race ./...`. Tests cover transactions, diamonds,
snapshot isolation, exact delta semantics, scoped instances, errors, recovery,
demand eviction, and remote reconnects. Work-counter tests and benchmarks check
point updates independently of collection size and input count. Those checks are
not claims of universal constant-time operations or production capacity.

Open directions:

- Scheduled/asynchronous evaluation and controlled coalescing.
- Per-key reactive dependencies and more incremental joins/reductions.
- Compact clear/reset delivery without enumerating every removed entry.
- Canonical cross-machine identity/encoding and Locator-based routing.
- Explicit byte bounds, fairness, and recovery behavior for slow consumers.
- Better application-facing error/partial-result observation.

Keep application examples small. General improvements belong in the runtime or
`nodes` when they have useful, well-defined semantics independent of an example.
