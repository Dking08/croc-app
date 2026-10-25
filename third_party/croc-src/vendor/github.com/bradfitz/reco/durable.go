package reco

import "fmt"

// BindDurable makes a registered data node a cache of externally stored data.
// It requires a demand-driven graph and a node with no observers. Any current
// value is discarded. The binding belongs to this graph and scoped instance,
// not the reusable node definition.
//
// load returns an immutable value and runs synchronously under the graph lock.
// It must not reenter the graph or acquire a lock held by a graph caller. Errors
// are returned by Read/Subscribe; failed activation releases all acquired demand
// and can be retried. The cached value is released with its last observer.
// Definitions, the loader, and snapshots retained by callers remain alive.
//
// Set and collection mutation helpers reject bound nodes. To change one, commit
// its backing store and call UpdateDurable within the same Graph.Update callback.
// Only that callback may write the backing store while the graph is in use.
// This serializes reloads with durable commits and cache publication.
func BindDurable[T any](g *Graph, node Node[T], load func() (T, error)) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if !g.demand || node.def == nil || node.def.kind != nodeData || load == nil {
		return fmt.Errorf("reco: BindDurable requires a demand-driven graph, data node, and loader")
	}
	if _, ok := g.nodes[node.def]; !ok {
		return fmt.Errorf("reco: node %s is not registered", node.def.className)
	}
	if len(g.users[node.def]) != 0 || len(g.subs[node.def]) != 0 || g.loaders[node.def] != nil {
		return fmt.Errorf("reco: node %s is observed or already durable", node.def.className)
	}
	g.loaders[node.def] = func() (any, error) { return load() }
	g.nodes[node.def] = nodeValue{}
	return nil
}

// UpdateDurable publishes an already-persisted mutation to a durable leaf's
// cache. If cold, it does nothing, including not calling update: a later observer
// reloads the latest stored value. If resident, update receives the current
// immutable value (including earlier publications in this transaction). Return
// a delta-based snapshot to preserve incremental downstream computation.
//
// Commit the backing store inside the same Graph.Update callback, before calling
// UpdateDurable, and return its error without publishing if the commit fails.
// Do all fallible preparation before committing; graph rollback cannot undo an
// external commit. This function never writes storage itself.
func UpdateDurable[T any](tx *Tx, node Node[T], update func(T) T) {
	tx.checkData(node.def)
	if tx.g.loaders[node.def] == nil {
		panic(fmt.Sprintf("reco: node %s is not durable", node.def.className))
	}
	if v := tx.current(node.def); v.valid {
		next := any(update(typedValue[T](v.value)))
		if _, pending := tx.writes[node.def]; pending {
			if accumulator, ok := next.(interface{ recoAccumulate(any, any) any }); ok {
				next = accumulator.recoAccumulate(tx.g.nodes[node.def].value, v.value)
			}
		}
		tx.writes[node.def] = next
	}
}

// ReadCached returns a node's resident snapshot without loading, computing, or
// adding demand. An invalid snapshot means the node is cold or uninitialized.
// It is useful for point reads backed by a store lookup when a collection is
// cold. Returned snapshots retain their values independently of graph demand.
func ReadCached[T any](g *Graph, node Node[T]) (Snapshot[T], error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if node.def == nil {
		return Snapshot[T]{}, fmt.Errorf("reco: zero node handle")
	}
	v, ok := g.nodes[node.def]
	if !ok {
		return Snapshot[T]{}, fmt.Errorf("reco: node %s is not registered", node.def.className)
	}
	return snapshotFromNodeValue[T](v), nil
}
