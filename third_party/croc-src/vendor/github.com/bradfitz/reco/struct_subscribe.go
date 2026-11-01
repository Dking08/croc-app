package reco

import "fmt"

// StructEvent describes one settled record change. Changes includes typed field
// replacements and net map/set mutations. Copying the event is cheap and safe;
// all published snapshots and change batches are immutable.
type StructEvent[T any] struct {
	Previous StructSnapshot[T]
	Current  StructSnapshot[T]
	Changes  StructChanges[T]
	Version  Version
}

// SubscribeStruct atomically returns the current snapshot and watches later
// changes. An invalid initial snapshot means the node has not produced a value;
// use the first event as the initial state in that case. Events may start before
// this function returns, so initialize callback state before calling it.
//
// Callbacks run synchronously under the graph lock after propagation. Capture
// or accumulate the immutable changes there; encode/compress/write outside the
// callback. Changes.Then supports coalescing without scanning full snapshots,
// but callers must bound pending state and decide when to disconnect/resync a
// slow consumer. Options have the same current limitations as Subscribe.
// Snapshot/Event still do not expose Result error/partial status; validity alone
// does not establish application-level completeness or readiness.
func SubscribeStruct[T any](g *Graph, node Node[StructSnapshot[T]], opts SubscribeOptions, fn func(StructEvent[T])) (Snapshot[StructSnapshot[T]], SubscriptionHandle, error) {
	if fn == nil {
		return Snapshot[StructSnapshot[T]]{}, nil, fmt.Errorf("reco: nil subscriber")
	}
	structSchemaFor[T]()
	g.mu.Lock()
	defer g.mu.Unlock()
	if node.def == nil {
		return Snapshot[StructSnapshot[T]]{}, nil, fmt.Errorf("reco: zero node handle")
	}
	_, ok := g.nodes[node.def]
	if !ok {
		return Snapshot[StructSnapshot[T]]{}, nil, fmt.Errorf("reco: node %s is not registered", node.ClassName())
	}
	if err := g.observeLocked(node.def); err != nil {
		return Snapshot[StructSnapshot[T]]{}, nil, err
	}
	cur := g.nodes[node.def]
	g.subCount++
	g.nextSub++
	id := g.nextSub
	if g.subs[node.def] == nil {
		g.subs[node.def] = make(map[uint64]subscriber)
	}
	g.subs[node.def][id] = typedSubscriber[StructSnapshot[T]]{fn: func(ev Event[StructSnapshot[T]]) {
		previous, current := ev.Previous.Value(), ev.Current.Value()
		fn(StructEvent[T]{Previous: previous, Current: current, Changes: current.ChangesSince(previous), Version: ev.Version})
	}}
	return snapshotFromNodeValue[StructSnapshot[T]](cur), &subscription{g: g, def: node.def, id: id}, nil
}
