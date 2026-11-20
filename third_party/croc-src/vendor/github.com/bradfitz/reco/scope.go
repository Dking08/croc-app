package reco

import "sync"

// Scope gives reusable definitions independent instances within one Graph.
// It is a local namespace, not a separate graph, transaction boundary, or
// security boundary. A Scope must not be copied after first use. Its zero value
// is ready for use; retain it to obtain the same instances on subsequent calls.
type Scope struct {
	mu    sync.Mutex
	nodes map[*nodeDef]*nodeDef
}

// In returns the instance of node in scope, recursively instantiating its
// unscoped dependencies. Already-scoped nodes are preserved, allowing explicit
// dependencies between scopes in the same graph. ClassName is unchanged.
// In is safe to call concurrently. Nil scope and zero node handles panic.
func In[T any](scope *Scope, node Node[T]) Node[T] {
	if scope == nil || node.def == nil {
		panic("reco: nil scope or zero node handle")
	}
	scope.mu.Lock()
	defer scope.mu.Unlock()
	return Node[T]{def: scope.instance(node.def)}
}

func (s *Scope) instance(def *nodeDef) *nodeDef {
	if def.scope != nil {
		return def
	}
	if n := s.nodes[def]; n != nil {
		return n
	}
	if s.nodes == nil {
		s.nodes = make(map[*nodeDef]*nodeDef)
	}
	n := *def
	n.scope = s
	s.nodes[def] = &n // break recursion; Register validates cycles
	n.deps = make([]depBinding, len(def.deps))
	for i, dep := range def.deps {
		n.deps[i] = dep
		n.deps[i].node = s.instance(dep.node)
	}
	// depBinding.handle retains the declaration's original input names. Eval
	// resolves them directly through a per-active-instance index; there is no
	// per-evaluation input-table copy or scoped compute wrapper.
	return &n
}
