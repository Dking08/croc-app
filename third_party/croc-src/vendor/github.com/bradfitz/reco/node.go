package reco

import "fmt"

// NodeClassName names a reusable node definition (a node class).
// Constructors such as Data, Func, and Operator take a class name; Node.ClassName
// returns it.
//
// A name must be nonempty. Names are exact, case-sensitive strings: reco does
// not trim whitespace, normalize text, or interpret separators or punctuation.
// There is no required namespace or path syntax. The zero value is invalid for
// a definition, but is returned by ClassName on a zero Node handle.
//
// Within a Graph and Scope, all definitions, including dependencies, must have
// distinct names regardless of their value types or constructors. Unscoped
// definitions share the graph's default namespace. Registering
// the same definition again is allowed; separately constructed definitions with
// the same name are an error, not aliases. Definitions and names can be reused
// across independent graphs or instantiated with In in multiple scopes;
// names are not globally unique.
//
// A class name is not a collection key or a key selecting an instance of a
// class. Scope provides local instances; generic keyed resolution and remote
// addressing are not yet implemented;
// this type does not define a distributed address or versioning scheme.
type NodeClassName string

// Node is a typed handle to a value in a graph.
type Node[T any] struct {
	def *nodeDef
}

// ClassName returns the name of the node's definition, or "" for a zero handle.
func (n Node[T]) ClassName() NodeClassName {
	if n.def == nil {
		return ""
	}
	return n.def.className
}

// Valid reports whether this handle references a node definition.
func (n Node[T]) Valid() bool {
	return n.def != nil
}

func (n Node[T]) recoTypedNode() typedNode {
	if n.def == nil {
		panic("reco: zero node handle")
	}
	return typedNode{def: n.def, typ: typeOf[T](), handle: n}
}

// Data creates a mutable data node.
func Data[T any](className NodeClassName) Node[T] {
	mustClassName(className)
	return Node[T]{def: &nodeDef{
		className: className,
		kind:      nodeData,
	}}
}

// Func creates a function node using either a typed binding struct or an inline
// dependency declaration created with Deps.
func Func[DepsT any, Out any](className NodeClassName, deps any, compute func(Eval, DepsT) Result[Out]) Node[Out] {
	mustClassName(className)
	if compute == nil {
		panic("reco: nil compute function")
	}
	bindings, adapter, err := compileFunc[DepsT, Out](deps, compute)
	if err != nil {
		panic(err)
	}
	return Node[Out]{def: &nodeDef{
		className: className,
		kind:      nodeFunc,
		deps:      bindings,
		compute:   adapter,
		valueTyp:  typeOf[Out](),
	}}
}

func mustClassName(className NodeClassName) {
	if className == "" {
		panic("reco: empty node class name")
	}
}

func sameTypeNode[T any](n Node[T]) typedNode {
	return n.recoTypedNode()
}

type nodeKind int

const (
	nodeData nodeKind = iota
	nodeFunc
)

type nodeDef struct {
	scope     *Scope
	className NodeClassName
	kind      nodeKind
	deps      []depBinding
	compute   computeFunc
	// newCompute creates instance-local incremental state for Operator nodes.
	newCompute func() computeFunc
	valueTyp   typeID
}

type depBinding struct {
	name string
	node *nodeDef
	typ  typeID
	// handle is the name used by the computation. Scoping changes node (the
	// graph instance) but preserves handle (the original declared input).
	handle Dependency
}

type typedNode struct {
	def    *nodeDef
	typ    typeID
	handle Dependency // canonical Node[T] value, not a mutable *Node[T]
}

func (d *nodeDef) String() string {
	if d == nil {
		return "<nil>"
	}
	return fmt.Sprintf("Node(%s)", d.className)
}
