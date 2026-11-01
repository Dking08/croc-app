// Package reco is an experimental research prototype for reactive computation.
//
// # EXPERIMENTAL: DO NOT USE
//
// Nothing to see here; move along. This is unfinished research, not a supported
// library. Do not depend on it. APIs and behavior may change or disappear without
// notice. There are no compatibility or correctness guarantees.
//
// # Overview
//
// Reco stands for reactive computation. It provides typed, in-process
// reactive dataflow graphs.
// Declare mutable leaves with Data, SetData, MultisetData, MapData, or StructData,
// and derived nodes with Func. Register an output with Graph.Register to register its
// dependencies too.
// Graph.Update applies a transaction, recomputes affected nodes in dependency
// order, and delivers subscription events after the graph has settled.
// Node definitions can be reused across independent Graph instances.
// Scope and In instantiate the same definitions independently within one graph;
// explicitly scoped inputs allow dependencies across scopes.
//
// Each definition has a NodeClassName, supplied to its constructor and returned
// by Node.ClassName. Class names must be unique within a graph/scope, including its
// dependencies; they are distinct from collection keys and future instance keys.
//
// # Incremental operators
//
// Package [github.com/bradfitz/reco/nodes] provides reusable incremental nodes,
// including set union and set-to-map computation. Packages can implement their own
// operators using Operator and Input.
// Allocate incremental caches inside the operator's per-instance compute factory.
// Consume collection changes with ChangesSince and publish one WithDelta batch
// per evaluation to preserve the incremental fast path. See the Operator example.
// Eval.ChangedInputs identifies changed dependency handles without scanning all
// declared inputs. The first evaluation reports all inputs; later evaluations
// report only value/result-status changes. It retains original declaration
// handles across scoping. Input reads the current settled value in O(1) expected
// time. Readiness and scope mapping do not rebuild input tables on point edits.
// Func still populates every field of its dependency struct; use Operator for
// wide, incrementally maintained computations. See the ChangedInputs example.
//
// Published values must be immutable. SetSnapshot, MultisetSnapshot and
// MapSnapshot provide structurally shared storage, native Go iterators, and
// atomic delta application.
// Custom immutable types may implement ValueEqualer and VersionedValue.
// Multisets store positive int64 multiplicities, with signed atomic adjustments
// and checked underflow/overflow. Len counts distinct keys; All yields key/count
// pairs. nodes.CountBy and nodes.GroupCounts aggregate contributors, and
// nodes.Distinct suppresses count changes that do not cross zero. Indexes and
// counted classes can avoid expanding relationships into every node pair.
//
// # Demand-driven lifetimes
//
// NewGraph keeps registered functions eager. NewGraphWithOptions with
// GraphOptions.DemandDriven enabled computes only subscribed dependency
// closures. Unsubscribe releases now-unused derived values and operator caches;
// ordinary authoritative leaves and registered definitions remain. Read temporarily
// activates a closure, returning an immutable snapshot without a lasting watch.
// SubscribeSnapshot, SubscribeMap, and SubscribeStruct atomically snapshot and
// subscribe to an output, so changes cannot fall between reading and watching.
// Reconnecting rebuilds released caches from current leaves. Stats exposes
// constant-time lifetime and evaluation counters.
// Reconfigure changes a function instance's inputs and compute factory inside
// a transaction while retaining its handle and subscribers. It requires a
// demand-driven graph and already-registered, lower-rank inputs. Newly needed
// inputs load before commit; abort releases temporary demand. Shared definitions
// in other graph instances remain unchanged.
//
// BindDurable optionally binds a registered data node to an application-supplied
// loader. Its value is cached only while observed, and reloaded when demand
// returns. This works for scalars, sets, multisets, maps, records, and custom immutable
// values. Read/Subscribe return load errors without retaining partial demand.
// Set and collection mutation helpers reject these externally owned leaves.
// Commit backing storage inside Graph.Update, then use UpdateDurable to patch
// the resident cache; cold publications need neither loading nor computation.
// ReadCached probes residency without loading. Storage itself is application
// code: reco neither writes a database nor undoes an external commit.
// Loaders run synchronously under the graph lock and must not reenter it.
// Returned snapshots and loader closures can retain values independently;
// avoid capturing a full initial snapshot in a loader intended to save memory.
//
// # Typed records
//
// StructSnapshot[T] uses an ordinary Go struct as a fixed, typed schema. Field
// handles provide typed edits and change inspection. SubscribeStruct atomically
// obtains an initial snapshot and watches settled field/collection changes.
// StructChanges.Then coalesces contiguous events without scanning unchanged
// collection contents. Use MapFieldChanges and SetFieldChanges on the composed
// batch rather than comparing its endpoint collections; MultisetFieldChanges
// provides the same touched-key behavior for counted fields. See the
// SubscribeStruct example for an incremental stream-encoding boundary.
//
// # Experimental remote watches
//
// Package [github.com/bradfitz/reco/metagraph] connects explicit remote mirrors
// and authoritative exports using multiplexed bidirectional WebSockets. The core
// Graph remains local and synchronous. Remote-watch ownership and lifetime are
// currently application-managed, not automatically resolved through a Locator.
// See cmd/webdemo for a two-process example and the current limitations.
package reco
