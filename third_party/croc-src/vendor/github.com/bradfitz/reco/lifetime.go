package reco

import "fmt"

// GraphStats reports graph lifetime and work counters. Registered definitions
// and ordinary data leaves outlive subscriptions. CachedFunctions counts
// retained operator states. DurableNodes counts loader bindings and
// CachedDurableNodes counts their resident values. Demand-driven graphs release
// both types of cache with last demand, without unregistering definitions.
type GraphStats struct {
	Nodes, Functions, ActiveFunctions, CachedFunctions, Subscriptions int
	DurableNodes, CachedDurableNodes                                  int
	Evaluations                                                       uint64
	// InputChecks counts dependency readiness checks, including first-demand
	// initialization. InputReads counts values requested by computations (also
	// including Func's struct adapter). They measure work, not elapsed time.
	InputChecks, InputReads uint64
}

// Stats returns a constant-time snapshot of graph counters.
func (g *Graph) Stats() GraphStats {
	g.mu.Lock()
	defer g.mu.Unlock()
	active := g.functionCount
	if g.demand {
		active = len(g.refs) - g.cachedDurable
	}
	return GraphStats{Nodes: len(g.nodes), Functions: g.functionCount,
		ActiveFunctions: active, CachedFunctions: len(g.funcs),
		Subscriptions: g.subCount, Evaluations: g.evaluations,
		InputChecks: g.inputChecks, InputReads: g.inputReads,
		DurableNodes: len(g.loaders), CachedDurableNodes: g.cachedDurable}
}

// observeLocked acquires one demand reference and settles newly active work.
// Subscription callers install the callback afterwards: this is initial state,
// not a change event. Read releases its temporary demand before returning.
func (g *Graph) observeLocked(def *nodeDef) error {
	if !g.demand {
		return nil
	}
	dirty := &dirtyQueue{rank: g.rank, scheduled: make(map[*nodeDef]bool)}
	if err := g.retainLocked(def, dirty); err != nil {
		return err
	}
	g.recompute(make(map[*nodeDef]nodeValue), dirty)
	return nil
}

func (g *Graph) retainLocked(def *nodeDef, dirty *dirtyQueue) error {
	load := g.loaders[def]
	if def.kind != nodeFunc && load == nil {
		return nil
	}
	g.refs[def]++
	if g.refs[def] != 1 {
		return nil
	}
	if load != nil {
		value, err := load()
		if err != nil {
			delete(g.refs, def)
			return fmt.Errorf("reco: load %s: %w", def.className, err)
		}
		g.version++
		g.nodes[def] = nodeValue{value: valueWithVersion(value, g.version), version: g.version, valid: true}
		g.cachedDurable++
		return nil
	}
	for _, dep := range g.definition(def).deps {
		if !g.users[dep.node][def] {
			if err := g.retainLocked(dep.node, dirty); err != nil {
				g.releaseLocked(def) // unwind only the edges already acquired
				return err
			}
			g.addUser(dep.node, def)
		}
	}
	dirty.add(def)
	return nil
}

func (g *Graph) releaseLocked(def *nodeDef) {
	if !g.demand || def.kind != nodeFunc && g.loaders[def] == nil {
		return
	}
	g.refs[def]--
	if g.refs[def] != 0 {
		return
	}
	delete(g.refs, def)
	if g.loaders[def] != nil {
		g.cachedDurable--
	}
	delete(g.funcs, def)       // release incremental caches, including old input roots
	g.nodes[def] = nodeValue{} // release the derived output or durable leaf cache
	for _, dep := range g.definition(def).deps {
		if g.users[dep.node][def] {
			delete(g.users[dep.node], def)
			if len(g.users[dep.node]) == 0 {
				delete(g.users, dep.node)
			}
			g.releaseLocked(dep.node)
		}
	}
}
