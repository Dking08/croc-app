package reco

import (
	"fmt"
	"iter"
)

// Dependency is a node that an operator declares as an input. Every Node[T]
// implements Dependency, allowing heterogeneous or variable-length input lists.
// Create nodes with Data, Func, or Operator; the runtime owns their handles.
type Dependency interface {
	nodeHandle
}

// Compute evaluates an operator using its declared inputs. Its closure may
// retain instance-local incremental caches, but must not mutate published values.
// The result must depend only on the inputs, not on the cache's history.
type Compute[T any] func(Eval) Result[T]

// Operator declares a reusable incremental node. newCompute is called lazily,
// per graph/node instance, to create its compute function and state. In demand-
// driven graphs, the state is discarded when last demand disappears and the
// factory runs again on reactivation. Caches must be rebuildable from inputs.
// Allocate mutable caches INSIDE newCompute, not in the shared definition.
// A factory must return a non-nil function. It can run concurrently with
// factories/computations in other graphs; calls within one graph are serialized.
//
// Compute reads declared inputs with Input. The runtime waits until they are
// initialized, schedules affected nodes in dependency order, and delivers the
// settled results. A compute function must not call Read, Update, or Subscribe
// on its graph, perform external side effects, or retain its Eval.
//
// For collections, use ChangesSince to consume input deltas and WithDelta to
// publish a new immutable output. A normal stateless computation can use Func.
// For many inputs, Eval.ChangedInputs avoids inspecting unchanged dependencies.
// It reports changes since the previous evaluation, even if that evaluation
// returned an error; operators must retain any unapplied work needed to recover.
func Operator[T any](className NodeClassName, deps []Dependency, newCompute func() Compute[T]) Node[T] {
	mustClassName(className)
	if newCompute == nil {
		panic("reco: nil operator factory")
	}
	bindings := make([]depBinding, len(deps))
	for i, dep := range deps {
		n, err := nodeFromAny(dep)
		if err != nil {
			panic(err)
		}
		bindings[i] = depBinding{node: n.def, typ: n.typ, handle: n.handle}
	}
	return Node[T]{def: &nodeDef{
		className: className, kind: nodeFunc, deps: bindings, valueTyp: typeOf[T](),
		newCompute: func() computeFunc {
			compute := newCompute()
			if compute == nil {
				panic(fmt.Sprintf("reco: operator %q factory returned nil", className))
			}
			return func(eval Eval) nodeValue {
				result := compute(eval)
				return nodeValue{value: result.Value, valid: true, err: result.Err, isPartial: result.IsPartial}
			}
		},
	}}
}

// Input returns the current value, version, and result status of a declared
// dependency during a computation. It panics for a zero or undeclared node or
// outside an evaluation. It does not acquire a graph lock or create a watch.
// Unlike Changes(), collection ChangesSince(previous) safely handles both
// unchanged inputs and replacements since the operator last evaluated them.
func Input[T any](eval Eval, node Node[T]) Dep[T] {
	if node.def == nil {
		panic("reco: zero input node")
	}
	v := eval.input(node.def)
	return Dep[T]{Value_: typedValue[T](v.value), Version_: v.version, Valid_: v.valid, Err_: v.err, IsPartial_: v.isPartial}
}

func (e Eval) input(def *nodeDef) nodeValue {
	if e.state == nil || !e.state.evaluating || e.state.inputs[def] == nil {
		panic(fmt.Sprintf("reco: input %q was not declared for this evaluation", def.className))
	}
	e.graph.inputReads++
	return e.graph.nodes[e.state.inputs[def]]
}

// ChangedInputs yields declared input handles whose value or result status
// changed since this computation's previous invocation. The first invocation
// of a compute instance yields all inputs, including after demand eviction or
// Reconfigure. Errors returned by Compute do not defer this boundary: retain
// unapplied work if recovery needs changes from earlier failed evaluations.
//
// Handles are Node[T] values of the declared types, so callers can type assert
// or look them up in a map keyed by Dependency. Scoping preserves the handles
// from the original declaration, just as Input does. Repeated declarations of
// the same node yield it once; distinct declared handles that resolve to the
// same scoped instance are each yielded. Order is unspecified. All values read
// with Input are settled for this transaction, whether changed or not.
//
// The iterator is reusable within this evaluation, supports early termination,
// and visits only changed handles. Do not retain it or Eval after computation
// returns. It panics outside an evaluation. Func can use it too, but populating
// Func's dependency struct still visits all of that struct's fields.
func (e Eval) ChangedInputs() iter.Seq[Dependency] {
	return func(yield func(Dependency) bool) {
		if e.state == nil || !e.state.evaluating {
			panic("reco: ChangedInputs outside evaluation")
		}
		for _, input := range e.changed {
			if !yield(input) {
				return
			}
		}
	}
}
