// Package nodes provides reusable incremental node definitions for reco graphs.
//
// # EXPERIMENTAL: DO NOT USE
//
// This package is part of an unfinished research prototype, not a supported
// library. Do not depend on it. APIs and behavior may change or disappear without
// notice. There are no compatibility or correctness guarantees.
//
// # Overview
//
// Union, Intersection, Xor, and Difference implement set algebra. MapSet
// preserves set keys while computing a value for each added member. MapKeys
// and MapValues project a map into a set of keys or distinct comparable values.
// Point updates visit only changed inputs and their changed keys, plus persistent
// storage traversal, rather than scanning all inputs or rebuilding collections.
// Initial snapshots, replacements, and clear operations may require enumeration.
//
// Map preserves map keys while transforming changed values. Index groups map
// keys into sets, and InvertSets reverses a set-valued membership index. CountBy
// counts map entries by a pure grouping function. GroupCounts joins set-valued
// memberships to a same-key grouping map and counts members within each group,
// without enumerating combinations of peers. Empty index/count buckets vanish;
// zero-valued group keys are ordinary groups, not missing values.
//
// Distinct projects a multiset to its positive-count keys. SumMultisets adds
// multiplicities (not maximum-count bag union); repeated inputs count repeatedly.
// These indexing/counting helpers propagate input errors without advancing caches;
// after errors or skipped delta bases, recovery can require reconciliation.
// Group functions must be pure, and derived keys must obey the same equality
// rules as input keys: Go-comparable, reflexive (no NaNs), and dynamically
// comparable for interface values. All published values must remain immutable.
//
// Definitions can be reused across independent graphs; incremental caches are
// graph-local. These nodes use only public reco APIs, the same APIs available
// to other packages implementing their own nodes. See [github.com/bradfitz/reco.Operator].
//
// Struct assembles typed record fields from input nodes, retaining efficient
// collection equality and changes for reco.SubscribeStruct.
package nodes
