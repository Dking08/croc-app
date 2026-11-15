package reco

import (
	"fmt"
	"slices"
	"sync"
)

type subscriber interface {
	deliver(prev nodeValue, cur nodeValue)
}

type typedSubscriber[T any] struct {
	fn func(Event[T])
}

// MapEvent is delivered after a transaction changes a map node. Changes lists
// only changed keys. Consecutive snapshots use their recorded delta; arbitrary
// snapshot replacements are reconciled against the previous value.
type MapEvent[K comparable, V any] struct {
	Previous MapSnapshot[K, V]
	Current  MapSnapshot[K, V]
	Changes  []MapChange[K, V]
	Version  Version
}

func (s typedSubscriber[T]) deliver(prev nodeValue, cur nodeValue) {
	ev := Event[T]{
		Previous: snapshotFromNodeValue[T](prev),
		Current:  snapshotFromNodeValue[T](cur),
		Version:  cur.version,
	}
	s.fn(ev)
}

func snapshotFromNodeValue[T any](v nodeValue) Snapshot[T] {
	if !v.valid {
		return Snapshot[T]{}
	}
	return newSnapshot(typedValue[T](v.value), v.version)
}

// A valid interface-valued node may contain a nil interface. A direct type
// assertion would panic, though nil is the correct zero value for its type.
func typedValue[T any](v any) T {
	if v == nil {
		var zero T
		return zero
	}
	return v.(T)
}

type subscription struct {
	mu  sync.Mutex
	g   *Graph
	def *nodeDef
	id  uint64
}

func (s *subscription) Unsubscribe() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	g := s.g
	if g == nil {
		return nil
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.subs[s.def], s.id)
	if len(g.subs[s.def]) == 0 {
		delete(g.subs, s.def)
	}
	g.subCount--
	g.releaseLocked(s.def)
	s.g, s.def = nil, nil // cancelled handles retain neither graph nor callbacks
	return nil
}

// Subscribe registers a callback for node value changes, without an initial
// event. In demand-driven mode it keeps the dependency closure active until
// Unsubscribe. Callbacks run under the graph lock and must not reenter it.
func Subscribe[T any](g *Graph, node Node[T], opts SubscribeOptions, fn func(Event[T])) (SubscriptionHandle, error) {
	_, sub, err := SubscribeSnapshot(g, node, opts, fn)
	return sub, err
}

// SubscribeSnapshot atomically reads node and installs a watch for subsequent
// changes. Unlike a separate Read followed by Subscribe, no update can fall in
// between. The callback may run as soon as this function releases the graph
// lock, including before the caller has processed the returned snapshot.
// Callbacks run under that lock and must not reenter the graph or do network I/O.
func SubscribeSnapshot[T any](g *Graph, node Node[T], opts SubscribeOptions, fn func(Event[T])) (Snapshot[T], SubscriptionHandle, error) {
	if fn == nil {
		return Snapshot[T]{}, nil, fmt.Errorf("reco: nil subscriber")
	}
	g.mu.Lock()
	defer g.mu.Unlock()

	if node.def == nil {
		return Snapshot[T]{}, nil, fmt.Errorf("reco: zero node handle")
	}
	if _, ok := g.nodes[node.def]; !ok {
		return Snapshot[T]{}, nil, fmt.Errorf("reco: node %s is not registered", node.def.className)
	}
	if err := g.observeLocked(node.def); err != nil {
		return Snapshot[T]{}, nil, err
	}
	g.subCount++
	g.nextSub++
	id := g.nextSub
	if g.subs[node.def] == nil {
		g.subs[node.def] = make(map[uint64]subscriber)
	}
	g.subs[node.def][id] = typedSubscriber[T]{fn: fn}
	return snapshotFromNodeValue[T](g.nodes[node.def]), &subscription{g: g, def: node.def, id: id}, nil
}

// SubscribeMap atomically returns the current snapshot and subscribes fn to
// later transaction-local map mutations. The atomic setup prevents updates
// from being lost between taking the initial snapshot and starting the watch.
func SubscribeMap[K comparable, V any](g *Graph, node Node[MapSnapshot[K, V]], opts SubscribeOptions, fn func(MapEvent[K, V])) (Snapshot[MapSnapshot[K, V]], SubscriptionHandle, error) {
	if fn == nil {
		return Snapshot[MapSnapshot[K, V]]{}, nil, fmt.Errorf("reco: nil subscriber")
	}
	g.mu.Lock()
	defer g.mu.Unlock()

	if node.def == nil {
		return Snapshot[MapSnapshot[K, V]]{}, nil, fmt.Errorf("reco: zero node handle")
	}
	_, ok := g.nodes[node.def]
	if !ok {
		return Snapshot[MapSnapshot[K, V]]{}, nil, fmt.Errorf("reco: node %s is not registered", node.def.className)
	}
	if err := g.observeLocked(node.def); err != nil {
		return Snapshot[MapSnapshot[K, V]]{}, nil, err
	}
	cur := g.nodes[node.def]
	g.subCount++
	g.nextSub++
	id := g.nextSub
	if g.subs[node.def] == nil {
		g.subs[node.def] = make(map[uint64]subscriber)
	}
	g.subs[node.def][id] = typedSubscriber[MapSnapshot[K, V]]{fn: func(ev Event[MapSnapshot[K, V]]) {
		current := ev.Current.Value()
		fn(MapEvent[K, V]{
			Previous: ev.Previous.Value(),
			Current:  current,
			Changes:  slices.Collect(current.ChangesSince(ev.Previous.Value())),
			Version:  ev.Version,
		})
	}}
	return snapshotFromNodeValue[MapSnapshot[K, V]](cur), &subscription{g: g, def: node.def, id: id}, nil
}

func (g *Graph) deliverEvents(before map[*nodeDef]nodeValue) {
	for def, prev := range before {
		cur := g.nodes[def]
		if nodeValuesEqual(prev, cur) {
			continue
		}
		for _, sub := range g.subs[def] {
			sub.deliver(prev, cur)
		}
	}
}
