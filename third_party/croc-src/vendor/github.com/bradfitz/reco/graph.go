package reco

import (
	"container/heap"
	"fmt"
	"reflect"
	"sync"
)

// Graph is an independent reactive dataflow graph instance.
type Graph struct {
	mu                      sync.Mutex
	nodes                   map[*nodeDef]nodeValue
	defs                    map[nodeName]*nodeDef
	subs                    map[*nodeDef]map[uint64]subscriber
	nextSub                 uint64
	version                 Version
	funcs                   map[*nodeDef]*funcState
	configs                 map[*nodeDef]*nodeDef // per-instance input/factory overrides
	rank                    map[*nodeDef]int
	users                   map[*nodeDef]map[*nodeDef]bool // active consumers only in demand mode
	initial                 []*nodeDef                     // newly registered functions, evaluated on the next update
	demand                  bool
	refs                    map[*nodeDef]int // demand for functions and durable leaves
	loaders                 map[*nodeDef]func() (any, error)
	cachedDurable           int
	functionCount, subCount int
	evaluations             uint64
	inputChecks, inputReads uint64
}

type nodeName struct {
	scope     *Scope
	className NodeClassName
}

// GraphOptions configures evaluation lifetime.
type GraphOptions struct {
	// DemandDriven evaluates only functions reachable from subscriptions.
	// Read evaluates on demand without retaining a watch. When the last
	// observer goes away, derived values and operator caches are released.
	// Registered definitions and ordinary data values remain available.
	// BindDurable permits reloadable data values to be released too.
	DemandDriven bool
}

type funcState struct {
	compute    computeFunc
	inputs     map[*nodeDef]*nodeDef // declared handle -> graph instance
	byInstance map[*nodeDef]*inputState
	unready    int
	ran        bool
	evaluating bool
}

type inputState struct {
	handles []Dependency // distinct declarations, including scoped aliases
	ready   bool
}

func (state *funcState) evaluate(e Eval) nodeValue {
	state.evaluating = true
	defer func() { state.evaluating = false }()
	return state.compute(e)
}

// initializeInputs pays for the complete input list once per active compute
// instance. Afterwards readiness is maintained only along changed edges.
func (g *Graph) initializeInputs(state *funcState, binding *nodeDef) {
	state.inputs = make(map[*nodeDef]*nodeDef, len(binding.deps))
	state.byInstance = make(map[*nodeDef]*inputState, len(binding.deps))
	for _, dep := range binding.deps {
		name := dep.handle.recoTypedNode().def
		if state.inputs[name] != nil {
			continue
		}
		state.inputs[name] = dep.node
		input := state.byInstance[dep.node]
		if input == nil {
			g.inputChecks++
			input = &inputState{ready: g.nodes[dep.node].valid}
			state.byInstance[dep.node] = input
			if !input.ready {
				state.unready++
			}
		}
		input.handles = append(input.handles, dep.handle)
	}
}

// NewGraph creates an empty, eager graph instance. See NewGraphWithOptions for
// demand-driven evaluation and automatic cache release.
func NewGraph() *Graph {
	return NewGraphWithOptions(GraphOptions{})
}

// NewGraphWithOptions creates a graph with the specified evaluation lifetime.
func NewGraphWithOptions(opts GraphOptions) *Graph {
	return &Graph{
		nodes:   make(map[*nodeDef]nodeValue),
		defs:    make(map[nodeName]*nodeDef),
		subs:    make(map[*nodeDef]map[uint64]subscriber),
		funcs:   make(map[*nodeDef]*funcState),
		configs: make(map[*nodeDef]*nodeDef),
		rank:    make(map[*nodeDef]int),
		users:   make(map[*nodeDef]map[*nodeDef]bool),
		refs:    make(map[*nodeDef]int),
		loaders: make(map[*nodeDef]func() (any, error)),
		demand:  opts.DemandDriven,
	}
}

// Register atomically adds definitions and dependencies, validating class name
// uniqueness within each Scope and DAG structure. Work is proportional to newly
// registered definitions and their edges, not the existing graph's size.
func (g *Graph) Register(nodes ...any) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	staged := make(map[nodeName]*nodeDef)
	seen := make(map[*nodeDef]int)
	var order []*nodeDef
	var visit func(*nodeDef) error
	visit = func(n *nodeDef) error {
		if _, ok := g.nodes[n]; ok {
			return nil
		}
		switch seen[n] {
		case 1:
			return fmt.Errorf("reco: cycle involving %s", n.className)
		case 2:
			return nil
		}
		name := nodeName{n.scope, n.className}
		if old := g.defs[name]; old != nil && old != n {
			return fmt.Errorf("reco: duplicate node class name %q", n.className)
		}
		if old := staged[name]; old != nil && old != n {
			return fmt.Errorf("reco: duplicate node class name %q", n.className)
		}
		staged[name], seen[n] = n, 1
		for _, dep := range n.deps {
			if err := visit(dep.node); err != nil {
				return err
			}
		}
		seen[n] = 2
		order = append(order, n)
		return nil
	}
	for _, n := range nodes {
		tn, err := nodeFromAny(n)
		if err != nil {
			return err
		}
		if err := visit(tn.def); err != nil {
			return err
		}
	}
	for _, def := range order {
		g.defs[nodeName{def.scope, def.className}] = def
		g.nodes[def] = nodeValue{}
		for _, dep := range def.deps {
			g.rank[def] = max(g.rank[def], g.rank[dep.node]+1)
		}
		if def.kind != nodeFunc {
			continue
		}
		g.functionCount++
		if g.demand {
			continue
		}
		g.initial = append(g.initial, def)
		for _, dep := range def.deps {
			g.addUser(dep.node, def)
		}
	}
	return nil
}

func (g *Graph) addUser(dep, user *nodeDef) {
	if g.users[dep] == nil {
		g.users[dep] = make(map[*nodeDef]bool)
	}
	g.users[dep][user] = true
}

// Update runs one serialized transaction and then recomputes affected active
// functions to a fixed point before delivering subscription events. In eager
// graphs all registered functions are active; demand-driven graphs activate
// only subscribed dependency closures.
func (g *Graph) Update(fn func(*Tx) error) error {
	g.mu.Lock()
	defer g.mu.Unlock()

	tx := &Tx{g: g, writes: make(map[*nodeDef]any)}
	defer tx.releaseHolds()
	if err := fn(tx); err != nil {
		return err
	}
	if tx.err != nil {
		return tx.err
	}
	// Keep only changed nodes' old values. Neither committing a point update
	// nor notifying its subscribers should scan/copy the whole node registry.
	before := make(map[*nodeDef]nodeValue)
	dirty := &dirtyQueue{rank: g.rank, scheduled: make(map[*nodeDef]bool)}
	tx.applyConfigs(dirty)
	for _, def := range g.initial {
		dirty.add(def)
	}
	g.initial = nil
	for def, v := range tx.writes {
		// Rebinding a function may have removed the last consumer after a
		// durable cache edit was staged. Its backing store is authoritative;
		// do not resurrect the evicted value just to publish an unwatched write.
		if g.loaders[def] != nil && g.refs[def] == 0 {
			continue
		}
		prev := g.nodes[def]
		if normalizer, ok := v.(interface{ recoNormalize(any) any }); ok {
			v = normalizer.recoNormalize(prev.value)
		}
		next := nodeValue{value: v, valid: true}
		if nodeValuesEqual(prev, next) {
			continue
		}
		before[def] = prev
		g.version++
		v = valueWithVersion(v, g.version)
		g.nodes[def] = nodeValue{value: v, version: g.version, valid: true}
		for user := range g.users[def] {
			dirty.addInput(user, def)
		}
	}
	g.recompute(before, dirty)
	g.deliverEvents(before)
	return nil
}

func (g *Graph) recompute(before map[*nodeDef]nodeValue, dirty *dirtyQueue) {
	// Evaluate only affected nodes, in dependency order and at most once per
	// transaction. Diamond joins see all changed branches together.
	for dirty.Len() != 0 {
		def := heap.Pop(dirty).(*nodeDef)
		binding := g.definition(def)
		state := g.funcs[def]
		if state == nil {
			state = &funcState{compute: binding.compute}
			if binding.newCompute != nil {
				state.compute = binding.newCompute()
			}
			g.initializeInputs(state, binding)
			g.funcs[def] = state
		} else {
			for _, dep := range dirty.changed[def] {
				input := state.byInstance[dep]
				g.inputChecks++
				ready := g.nodes[dep].valid
				if ready != input.ready {
					if ready {
						state.unready--
					} else {
						state.unready++
					}
					input.ready = ready
				}
			}
		}
		if state.unready != 0 || state.ran && len(dirty.changed[def]) == 0 {
			continue
		}
		var changed []Dependency
		if !state.ran {
			for _, input := range state.byInstance {
				changed = append(changed, input.handles...)
			}
		} else {
			for _, dep := range dirty.changed[def] {
				changed = append(changed, state.byInstance[dep].handles...)
			}
		}
		next := state.evaluate(Eval{graph: g, state: state, changed: changed})
		g.evaluations++
		state.ran = true
		if !nodeValuesEqual(g.nodes[def], next) {
			before[def] = g.nodes[def]
			g.version++
			next.value = valueWithVersion(next.value, g.version)
			next.version = g.version
			next.valid = true
			g.nodes[def] = next
			for user := range g.users[def] {
				dirty.addInput(user, def)
			}
		}
	}
}

// dirtyQueue's ranks are fixed when the graph is registered, not on each
// mutation. scheduled deduplicates fan-in and diamond branches.
type dirtyQueue struct {
	nodes     []*nodeDef
	rank      map[*nodeDef]int
	scheduled map[*nodeDef]bool
	changed   map[*nodeDef][]*nodeDef
}

func (q *dirtyQueue) addInput(user, input *nodeDef) {
	if q.changed == nil {
		q.changed = make(map[*nodeDef][]*nodeDef)
	}
	// A node publishes at most once per transaction. g.users deduplicates
	// declared aliases, so each changed graph instance appears exactly once.
	q.changed[user] = append(q.changed[user], input)
	q.add(user)
}

func (q *dirtyQueue) add(def *nodeDef) {
	if !q.scheduled[def] {
		q.scheduled[def] = true
		heap.Push(q, def)
	}
}

func (q *dirtyQueue) Len() int           { return len(q.nodes) }
func (q *dirtyQueue) Less(i, j int) bool { return q.rank[q.nodes[i]] < q.rank[q.nodes[j]] }
func (q *dirtyQueue) Swap(i, j int)      { q.nodes[i], q.nodes[j] = q.nodes[j], q.nodes[i] }
func (q *dirtyQueue) Push(v any)         { q.nodes = append(q.nodes, v.(*nodeDef)) }
func (q *dirtyQueue) Pop() any {
	i := len(q.nodes) - 1
	def := q.nodes[i]
	q.nodes[i] = nil
	q.nodes = q.nodes[:i]
	return def
}

func valueWithVersion(v any, version Version) any {
	if versioned, ok := v.(VersionedValue); ok {
		next := versioned.RecoWithVersion(version)
		if reflect.TypeOf(next) != reflect.TypeOf(v) {
			panic(fmt.Sprintf("reco: RecoWithVersion changed value type from %T to %T", v, next))
		}
		return next
	}
	return v
}

func nodeValuesEqual(a, b nodeValue) bool {
	if a.valid != b.valid || a.isPartial != b.isPartial {
		return false
	}
	if !reflect.DeepEqual(a.err, b.err) {
		return false
	}
	return valuesEqual(a.value, b.value)
}

func valuesEqual(a, b any) bool {
	if equaler, ok := a.(ValueEqualer); ok {
		return equaler.RecoValueEqual(b)
	}
	return reflect.DeepEqual(a, b)
}

func nodeFromAny(n any) (typedNode, error) {
	h, ok := n.(nodeHandle)
	if !ok {
		return typedNode{}, fmt.Errorf("reco: expected Node[T], got %T", n)
	}
	return h.recoTypedNode(), nil
}
