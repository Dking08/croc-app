package reco

import (
	"fmt"
	"reflect"
	"strings"
	"sync"
)

type typeID = reflect.Type

func typeOf[T any]() reflect.Type {
	var zero *T
	return reflect.TypeOf(zero).Elem()
}

// Eval represents one synchronous node evaluation. Use Input to read its
// declared dependencies; ChangedInputs identifies which dependencies changed.
// It is not a cancellation or deadline context.
// Do not retain an Eval after the computation returns.
type Eval struct {
	graph   *Graph
	state   *funcState
	changed []Dependency
}

type computeFunc func(Eval) nodeValue

type nodeValue struct {
	value     any
	version   Version
	valid     bool
	err       error
	isPartial bool
}

type inlineDeps struct {
	v any
}

// Deps declares inline dependencies for Func.
func Deps[T any](v T) inlineDeps {
	return inlineDeps{v: v}
}

type compileKey struct {
	depsType    reflect.Type
	computeType reflect.Type
	outType     reflect.Type
	inline      bool
}

type compiledShape struct {
	fields []compiledField
}

type compiledField struct {
	name  string
	index int
	typ   reflect.Type
}

type nodeHandle interface {
	recoTypedNode() typedNode
}

var compileCache sync.Map // map[compileKey]compiledShape

func compileFunc[DepsT any, Out any](deps any, compute func(Eval, DepsT) Result[Out]) ([]depBinding, computeFunc, error) {
	depsType := typeOf[DepsT]()
	if depsType.Kind() != reflect.Struct {
		return nil, nil, fmt.Errorf("reco: dependency input for %s must be a struct, got %s", typeOf[Out](), depsType)
	}
	computeType := reflect.TypeOf(compute)
	inline := false
	var depStruct reflect.Value

	switch d := deps.(type) {
	case inlineDeps:
		inline = true
		depStruct = reflect.ValueOf(d.v)
	default:
		depStruct = reflect.ValueOf(deps)
	}
	if !depStruct.IsValid() {
		return nil, nil, fmt.Errorf("reco: nil dependency binding")
	}
	if depStruct.Kind() == reflect.Pointer {
		if depStruct.IsNil() {
			return nil, nil, fmt.Errorf("reco: nil dependency binding pointer")
		}
		depStruct = depStruct.Elem()
	}
	if depStruct.Kind() != reflect.Struct {
		return nil, nil, fmt.Errorf("reco: dependency binding must be a struct, got %s", depStruct.Type())
	}

	key := compileKey{
		depsType:    depsType,
		computeType: computeType,
		outType:     typeOf[Out](),
		inline:      inline,
	}
	shapeAny, ok := compileCache.Load(key)
	var shape compiledShape
	var err error
	if ok {
		shape = shapeAny.(compiledShape)
	} else {
		shape, err = compileShape(depsType, inline)
		if err != nil {
			return nil, nil, err
		}
		shapeAny, _ = compileCache.LoadOrStore(key, shape)
		shape = shapeAny.(compiledShape)
	}

	if depStruct.NumField() < len(shape.fields) {
		return nil, nil, fmt.Errorf("reco: dependency binding has too few fields")
	}

	bindings := make([]depBinding, len(shape.fields))
	bindingByField := make([]int, len(shape.fields))
	for i, f := range shape.fields {
		field := depStruct.Field(f.index)
		if !field.IsValid() {
			return nil, nil, fmt.Errorf("reco: missing dependency field %s", f.name)
		}
		node, err := extractNode(field)
		if err != nil {
			return nil, nil, fmt.Errorf("reco: dependency %s: %w", f.name, err)
		}
		if node.typ != f.typ {
			return nil, nil, fmt.Errorf("reco: dependency %s has type %s, want %s", f.name, node.typ, f.typ)
		}
		bindings[i] = depBinding{name: f.name, node: node.def, typ: f.typ, handle: node.handle}
		bindingByField[i] = i
	}

	adapter := func(eval Eval) nodeValue {
		var in DepsT
		inVal := reflect.ValueOf(&in).Elem()
		for i, f := range shape.fields {
			depVal := eval.input(bindings[bindingByField[i]].node)
			if inline {
				if depVal.value != nil {
					inVal.Field(f.index).Set(reflect.ValueOf(depVal.value))
				}
			} else {
				inVal.Field(f.index).Set(makeDepValue(inVal.Field(f.index).Type(), depVal))
			}
		}
		res := compute(eval, in)
		return nodeValue{
			value:     res.Value,
			valid:     true,
			err:       res.Err,
			isPartial: res.IsPartial,
		}
	}
	return bindings, adapter, nil
}

func compileShape(depsType reflect.Type, inline bool) (compiledShape, error) {
	fields := make([]compiledField, 0, depsType.NumField())
	for i := 0; i < depsType.NumField(); i++ {
		sf := depsType.Field(i)
		if !sf.IsExported() {
			return compiledShape{}, fmt.Errorf("reco: dependency field %s must be exported", sf.Name)
		}
		ft := sf.Type
		var valueType reflect.Type
		if inline {
			valueType = ft
		} else {
			if ft.Kind() != reflect.Struct || ft.PkgPath() != typeOf[Dep[any]]().PkgPath() || !isGenericName(ft.Name(), "Dep") {
				return compiledShape{}, fmt.Errorf("reco: dependency field %s must be Dep[T], got %s", sf.Name, ft)
			}
			valueType = ft.Field(0).Type
		}
		fields = append(fields, compiledField{name: sf.Name, index: i, typ: valueType})
	}
	return compiledShape{fields: fields}, nil
}

func extractNode(v reflect.Value) (typedNode, error) {
	if !v.CanInterface() {
		return typedNode{}, fmt.Errorf("field is not interfaceable")
	}
	iv := v.Interface()
	h, ok := iv.(nodeHandle)
	if !ok {
		return typedNode{}, fmt.Errorf("binding must be Node[T], got %T", iv)
	}
	return h.recoTypedNode(), nil
}

func makeDepValue(depType reflect.Type, val nodeValue) reflect.Value {
	out := reflect.New(depType).Elem()
	field := out.FieldByName("Value_")
	if val.value != nil {
		field.Set(reflect.ValueOf(val.value))
	}
	out.FieldByName("Version_").Set(reflect.ValueOf(val.version))
	out.FieldByName("Valid_").SetBool(val.valid)
	if val.err != nil {
		out.FieldByName("Err_").Set(reflect.ValueOf(val.err))
	}
	out.FieldByName("IsPartial_").SetBool(val.isPartial)
	return out
}

func isGenericName(name, base string) bool {
	return name == base || strings.HasPrefix(name, base+"[")
}

// Dep is the typed value wrapper used by typed-struct dependency inputs.
type Dep[T any] struct {
	Value_     T
	Version_   Version
	Valid_     bool
	Err_       error
	IsPartial_ bool
}

// Value returns the dependency value.
func (d Dep[T]) Value() T { return d.Value_ }

// Version returns the dependency version.
func (d Dep[T]) Version() Version { return d.Version_ }

// Valid reports whether the dependency is available.
func (d Dep[T]) Valid() bool { return d.Valid_ }

// Err returns the dependency error, if any.
func (d Dep[T]) Err() error { return d.Err_ }

// IsPartial reports whether the dependency value is partial.
func (d Dep[T]) IsPartial() bool { return d.IsPartial_ }
