package reco

import "fmt"

// Version is a monotonic graph-local version.
type Version uint64

// Snapshot is the immutable view delivered through subscription events.
//
// For scalar values, Value returns the value itself. Collection snapshots can
// use richer concrete types while still carrying the same graph version.
type Snapshot[T any] struct {
	value   T
	version Version
	valid   bool
}

// Value returns the snapshot value.
func (s Snapshot[T]) Value() T {
	return s.value
}

// Version returns the graph-local version at which this snapshot was observed.
func (s Snapshot[T]) Version() Version {
	return s.version
}

// Valid reports whether the snapshot contains a value.
func (s Snapshot[T]) Valid() bool {
	return s.valid
}

func newSnapshot[T any](v T, version Version) Snapshot[T] {
	return Snapshot[T]{value: v, version: version, valid: true}
}

// Read returns the latest value of a registered node. In demand-driven mode it
// temporarily observes its dependency closure and releases that demand before
// returning; the returned immutable snapshot remains valid.
func Read[T any](g *Graph, node Node[T]) (Snapshot[T], error) {
	g.mu.Lock()
	defer g.mu.Unlock()

	if node.def == nil {
		return Snapshot[T]{}, fmt.Errorf("reco: zero node handle")
	}
	_, ok := g.nodes[node.def]
	if !ok {
		return Snapshot[T]{}, fmt.Errorf("reco: node %s is not registered", node.def.className)
	}
	if err := g.observeLocked(node.def); err != nil {
		return Snapshot[T]{}, err
	}
	defer g.releaseLocked(node.def)
	return snapshotFromNodeValue[T](g.nodes[node.def]), nil
}

// Event is delivered to subscribers when a node value changes.
type Event[T any] struct {
	Previous Snapshot[T]
	Current  Snapshot[T]
	Version  Version
}

// SubscribeOptions controls subscription delivery.
type SubscribeOptions struct {
	FixedPointOnly bool
	Coalesce       bool
}

// SubscriptionHandle cancels a subscription.
type SubscriptionHandle interface {
	Unsubscribe() error
}
