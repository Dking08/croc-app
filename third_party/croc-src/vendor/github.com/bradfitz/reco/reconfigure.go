package reco

import "fmt"

// Reconfigure stages new inputs and a compute factory for a function instance,
// without replacing its handle or subscriptions. It requires a demand-driven
// graph. Inputs must already be registered at lower topological ranks than node;
// this restriction preserves ordering and rules out cycles without a graph scan.
// The reusable definition and other graph instances are not changed.
//
// An active function acquires its new inputs now, returning loader errors before
// commit. The old inputs/output remain in use until the transaction succeeds.
// Aborting releases temporary demand. Commit replaces the operator cache and
// computes the new output atomically with other writes. An inactive function
// only changes its binding, without loading data. Configure each instance at
// most once per transaction. If committing external storage, call this first.
// Any returned error also aborts the transaction, even if the caller ignores it.
// Active bindings require initialized inputs (or inputs already staged for
// writing/reconfiguration in this transaction), so an uninitialized replacement
// cannot silently leave subscribers seeing the old computation's value.
func Reconfigure[T any](tx *Tx, node Node[T], deps []Dependency, factory func() Compute[T]) (err error) {
	defer func() {
		if err != nil && tx.err == nil {
			tx.err = err
		}
	}()
	if !tx.g.demand || node.def == nil || node.def.kind != nodeFunc || factory == nil {
		return fmt.Errorf("reco: Reconfigure requires a demand-driven graph and function")
	}
	if _, ok := tx.g.nodes[node.def]; !ok {
		return fmt.Errorf("reco: unregistered function")
	}
	if tx.configs[node.def] != nil {
		return fmt.Errorf("reco: function already reconfigured in transaction")
	}
	for _, dep := range deps {
		n, err := nodeFromAny(dep)
		if err != nil {
			return err
		}
		if _, ok := tx.g.nodes[n.def]; !ok || tx.g.rank[n.def] >= tx.g.rank[node.def] {
			return fmt.Errorf("reco: reconfigured inputs must be registered at lower ranks")
		}
	}
	next := Operator(node.ClassName(), deps, factory).def
	if tx.configs == nil {
		tx.configs = make(map[*nodeDef]*nodeDef)
	}
	tx.configs[node.def] = next
	return tx.prepareConfigs()
}

// Preparing another binding can activate a previously dormant configured
// function. Repeat until every such function's future inputs are held. Also
// hold the functions themselves so a neighboring reconfiguration cannot drop
// their last demand partway through applyConfigs. No fallible I/O at commit.
func (tx *Tx) prepareConfigs() error {
	held := make(map[*nodeDef]bool, len(tx.holds))
	for _, def := range tx.holds {
		held[def] = true
	}
	for {
		added := false
		for def, next := range tx.configs {
			if tx.g.refs[def] == 0 {
				continue
			}
			for _, dep := range append([]depBinding{{node: def}}, next.deps...) {
				if held[dep.node] {
					continue
				}
				if err := tx.g.observeLocked(dep.node); err != nil {
					return err
				}
				tx.holds = append(tx.holds, dep.node)
				held[dep.node] = true
				added = true
			}
		}
		if !added {
			for def, next := range tx.configs {
				if tx.g.refs[def] == 0 {
					continue
				}
				for _, dep := range next.deps {
					_, written := tx.writes[dep.node]
					if !tx.g.nodes[dep.node].valid && !written && tx.configs[dep.node] == nil {
						return fmt.Errorf("reco: reconfigured input %q is uninitialized", dep.node.className)
					}
				}
			}
			return nil
		}
	}
}

func (g *Graph) definition(def *nodeDef) *nodeDef {
	if c := g.configs[def]; c != nil {
		return c
	}
	return def
}

func (tx *Tx) releaseHolds() {
	for _, def := range tx.holds {
		tx.g.releaseLocked(def)
	}
}

func (tx *Tx) applyConfigs(dirty *dirtyQueue) {
	g := tx.g
	for def, next := range tx.configs {
		if g.refs[def] > 0 {
			newDeps := make(map[*nodeDef]bool)
			for _, dep := range next.deps {
				newDeps[dep.node] = true
				if !g.users[dep.node][def] {
					// Prepared above; the temporary hold guarantees no reload.
					if err := g.retainLocked(dep.node, dirty); err != nil {
						panic(err)
					}
					g.addUser(dep.node, def)
				}
			}
			for _, dep := range g.definition(def).deps {
				if !newDeps[dep.node] && g.users[dep.node][def] {
					delete(g.users[dep.node], def)
					if len(g.users[dep.node]) == 0 {
						delete(g.users, dep.node)
					}
					g.releaseLocked(dep.node)
				}
			}
			dirty.add(def)
		}
		g.configs[def] = next
		delete(g.funcs, def)
	}
}
