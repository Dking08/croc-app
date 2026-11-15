package reco

import (
	"fmt"
	"iter"
	"reflect"
	"slices"
	"sync"
)

// StructSnapshot is an immutable record whose schema is the Go struct T.
// T must be a struct with exported, non-embedded fields. Value returns a typed
// shallow copy; referenced objects must remain immutable, as with other reco
// values. A zero snapshot contains the zero value of T.
//
// Fields use ValueEqualer when available, otherwise ordinary scalar equality.
// MapSnapshot and SetSnapshot fields preserve their incremental changes. Other
// fields are whole-value replacements, not recursively patched Go objects.
// Record overhead is proportional to the fixed field count; scalar comparison
// still follows each value's equality contract. Unchanged map/set contents are
// never scanned. Use Field to declare typed edits and inspect
// changes, and SubscribeStruct to obtain an initial snapshot plus future changes.
type StructSnapshot[T any] struct {
	root    *T
	base    *T
	fields  []structFieldChange
	delta   bool
	version Version
}

// NewStruct wraps value without copying its referenced storage. It validates
// the schema but does not enumerate collection contents or calculate changes.
func NewStruct[T any](value T) StructSnapshot[T] {
	structSchemaFor[T]()
	return StructSnapshot[T]{root: &value}
}

// Value returns the typed record. Do not mutate objects referenced by it.
func (s StructSnapshot[T]) Value() (value T) {
	if s.root != nil {
		return *s.root
	}
	return value
}

// Version returns the graph-local version at which this record was observed.
func (s StructSnapshot[T]) Version() Version { return s.version }

// RecoWithVersion implements VersionedValue without changing field versions.
func (s StructSnapshot[T]) RecoWithVersion(v Version) any { s.version = v; return s }

// RecoValueEqual compares field values, respecting their ValueEqualer hooks.
// Collection fields compare persistent roots, not backing-store contents.
func (s StructSnapshot[T]) RecoValueEqual(other any) bool {
	o, ok := other.(StructSnapshot[T])
	if !ok {
		return false
	}
	if s.root == o.root {
		return true
	}
	schema := structSchemaFor[T]()
	a, b := reflect.ValueOf(s.Value()), reflect.ValueOf(o.Value())
	for i := range schema.fields {
		if !valuesEqual(a.Field(i).Interface(), b.Field(i).Interface()) {
			return false
		}
	}
	return true
}

// StructField is a typed handle to a declared field of T. Its zero value is
// invalid. Create one with Field, normally once alongside node definitions.
type StructField[T, V any] struct {
	schema *structSchema
	index  int
}

// Field finds an exported, non-embedded field by its Go name. It panics if T is
// not a supported struct, the field is missing, or its type is not exactly V.
// Validation happens at declaration time; edits and reads remain typed.
func Field[T, V any](name string) StructField[T, V] {
	s := structSchemaFor[T]()
	for i, f := range s.fields {
		if f.Name == name {
			if f.Type != typeOf[V]() {
				panic(fmt.Sprintf("reco: field %s.%s has type %s, want %s", typeOf[T](), name, f.Type, typeOf[V]()))
			}
			return StructField[T, V]{s, i}
		}
	}
	panic(fmt.Sprintf("reco: unknown field %s.%s", typeOf[T](), name))
}

func (f StructField[T, V]) check() {
	if f.schema == nil {
		panic("reco: zero struct field")
	}
}

// Name returns the field's Go name.
func (f StructField[T, V]) Name() string { f.check(); return f.schema.fields[f.index].Name }

// Get returns the field's typed value.
func (f StructField[T, V]) Get(s StructSnapshot[T]) V {
	f.check()
	return typedValue[V](reflect.ValueOf(s.Value()).Field(f.index).Interface())
}

// Set replaces this field, including with a nil, zero, false, or empty value.
func (f StructField[T, V]) Set(value V) StructEdit[T] {
	f.check()
	return StructEdit[T]{schema: f.schema, index: f.index, value: value}
}

// Update transforms this field, seeing earlier edits in the same atomic delta.
// fn must be pure and must not mutate its input. For collection fields, return
// current.WithDelta(batch) to preserve delta-sized work.
func (f StructField[T, V]) Update(fn func(V) V) StructEdit[T] {
	f.check()
	if fn == nil {
		panic("reco: nil struct field update")
	}
	return StructEdit[T]{schema: f.schema, index: f.index, update: func(v any) any { return fn(typedValue[V](v)) }}
}

// Change returns this field's before/after values and whether it changed.
// When unchanged, both returned values are zero. For collection entry changes,
// use MapFieldChanges or SetFieldChanges instead of diffing these snapshots.
func (f StructField[T, V]) Change(c StructChanges[T]) (before, after V, changed bool) {
	f.check()
	if len(c.fields) == 0 || c.fields[f.index] == nil || c.fields[f.index].empty() {
		return before, after, false
	}
	return f.Get(c.Before()), f.Get(c.After()), true
}

// StructEdit is an opaque typed field edit, created by StructField.Set or Update.
// Its zero value is invalid.
type StructEdit[T any] struct {
	schema *structSchema
	index  int
	value  any
	update func(any) any
}

// StructDelta is an ordered atomic batch of field edits. Repeated edits see
// earlier values; the last Set wins. An empty batch does nothing. Fields cannot
// be added or deleted. Edits and referenced values must not be mutated while
// the batch is being applied.
type StructDelta[T any] []StructEdit[T]

// WithDelta applies all edits without publishing intermediate states. It
// coalesces nested collection changes, preserving their original delta base.
// Net-zero edits reuse the previous storage. Whole collection replacements
// without retained delta lineage may require enumeration; point edits do not.
func (s StructSnapshot[T]) WithDelta(d StructDelta[T]) StructSnapshot[T] {
	schema := structSchemaFor[T]()
	value := s.Value()
	v := reflect.ValueOf(&value).Elem()
	fields := make([]structFieldChange, len(schema.fields))
	for _, edit := range d {
		if edit.schema != schema {
			panic("reco: invalid struct edit")
		}
		field := v.Field(edit.index)
		after := edit.value
		if edit.update != nil {
			after = edit.update(field.Interface())
		}
		change := diffStructField(schema.fields[edit.index].Type, field.Interface(), after)
		if change != nil {
			if old := fields[edit.index]; old != nil {
				change = old.then(change)
			}
			fields[edit.index] = change
		}
		setStructField(field, after)
	}
	c := StructChanges[T]{before: s.root, after: &value, fields: fields, valid: true, beforeVersion: s.version}
	if c.Len() == 0 {
		return s
	}
	return c.normalized()
}

// ChangesSince describes the net field/entry changes from previous to s.
// Adjacent retained collection deltas avoid scanning unchanged contents. If
// collection versions were skipped or replaced without lineage, reconciliation
// may enumerate them. Capture each event and use StructChanges.Then to coalesce
// efficiently, rather than discarding intermediate snapshots and diffing later.
func (s StructSnapshot[T]) ChangesSince(previous StructSnapshot[T]) StructChanges[T] {
	c := StructChanges[T]{before: previous.root, after: s.root, beforeVersion: previous.version, afterVersion: s.version, valid: true}
	if s.root == previous.root {
		return c
	}
	if s.delta && s.base == previous.root {
		c.fields = s.fields
		return c
	}
	schema := structSchemaFor[T]()
	c.fields = make([]structFieldChange, len(schema.fields))
	a, b := reflect.ValueOf(previous.Value()), reflect.ValueOf(s.Value())
	for i, f := range schema.fields {
		c.fields[i] = diffStructField(f.Type, a.Field(i).Interface(), b.Field(i).Interface())
	}
	return c
}

// StructChanges is an immutable net change batch between two record snapshots.
// It retains only its endpoints and touched-key changes, not an event history.
// A zero value is an empty accumulator. Then composes contiguous batches without
// re-diffing collection snapshots; external synchronization is needed only when
// assigning a shared accumulator, not for reading a published batch.
type StructChanges[T any] struct {
	before, after               *T
	fields                      []structFieldChange
	beforeVersion, afterVersion Version
	valid                       bool
}

// Len returns the number of fields with nonempty net changes.
func (c StructChanges[T]) Len() int {
	n := 0
	for _, f := range c.fields {
		if f != nil && !f.empty() {
			n++
		}
	}
	return n
}

// ChangeCount counts scalar field replacements and map/set entry changes. It
// inspects only the fixed field list, not pending collection entries. This can
// help bound a stream accumulator; it is not a byte-size or memory bound.
func (c StructChanges[T]) ChangeCount() int {
	n := 0
	for _, f := range c.fields {
		if f != nil {
			n += f.changeCount()
		}
	}
	return n
}

// Fields yields changed field names in schema order. It inspects only the fixed
// field list, never collection contents. The iterator is reusable.
func (c StructChanges[T]) Fields() iter.Seq[string] {
	return func(yield func(string) bool) {
		schema := structSchemaFor[T]()
		for i, f := range c.fields {
			if f != nil && !f.empty() && !yield(schema.fields[i].Name) {
				return
			}
		}
	}
}

// Before returns the initial snapshot of the batch.
func (c StructChanges[T]) Before() StructSnapshot[T] {
	return StructSnapshot[T]{root: c.before, version: c.beforeVersion}
}

// After returns the final snapshot, retaining the composed changes from Before.
func (c StructChanges[T]) After() StructSnapshot[T] {
	return StructSnapshot[T]{root: c.after, base: c.before, fields: c.fields, delta: c.valid, version: c.afterVersion}
}

// Then composes a subsequent batch. It rejects a gap or out-of-order batch:
// next.Before must match this batch's actual After root and observed version,
// not a reconstructed equal value. Versions alone cannot establish lineage
// across graphs. A zero accumulator accepts any first batch.
// Work is proportional to the fixed field
// count and incoming touched keys plus persistent-map path copying.
func (c StructChanges[T]) Then(next StructChanges[T]) (StructChanges[T], error) {
	if !c.valid {
		return next, nil
	}
	if !next.valid {
		return c, nil
	}
	if c.after != next.before || c.afterVersion != next.beforeVersion {
		return StructChanges[T]{}, fmt.Errorf("reco: struct changes are not contiguous")
	}
	fields := slices.Clone(c.fields)
	if len(fields) == 0 {
		fields = make([]structFieldChange, len(next.fields))
	}
	for i, f := range next.fields {
		if f == nil {
			continue
		}
		if fields[i] != nil {
			fields[i] = fields[i].then(f)
		} else {
			fields[i] = f
		}
	}
	return StructChanges[T]{before: c.before, after: next.after, fields: fields, beforeVersion: c.beforeVersion, afterVersion: next.afterVersion, valid: true}, nil
}

// normalized restores original roots for net-zero fields and rebases nested
// map/set delta metadata once, after all edits have been accumulated.
func (c StructChanges[T]) normalized() StructSnapshot[T] {
	value := c.After().Value()
	v := reflect.ValueOf(&value).Elem()
	before := reflect.ValueOf(c.Before().Value())
	fields := slices.Clone(c.fields)
	for i, f := range fields {
		if f == nil {
			continue
		}
		if f.empty() {
			v.Field(i).Set(before.Field(i))
			fields[i] = nil
		} else {
			setStructField(v.Field(i), f.normalizedValue())
		}
	}
	return StructSnapshot[T]{root: &value, base: c.before, fields: fields, delta: true, version: c.afterVersion}
}

func (s StructSnapshot[T]) recoNormalize(previous any) any {
	prev, _ := previous.(StructSnapshot[T])
	c := s.ChangesSince(prev)
	if c.Len() == 0 {
		return prev
	}
	return c.normalized()
}

// StructData declares a mutable record data node and validates T's schema.
func StructData[T any](className NodeClassName) Node[StructSnapshot[T]] {
	structSchemaFor[T]()
	return Data[StructSnapshot[T]](className)
}

// ApplyStructDelta applies an atomic field batch inside tx. Multiple calls
// compose against staged values and retain the transaction's original base.
func ApplyStructDelta[T any](tx *Tx, node Node[StructSnapshot[T]], d StructDelta[T]) {
	tx.mustData(node.def)
	cur, _ := tx.current(node.def).value.(StructSnapshot[T])
	next := cur.WithDelta(d)
	if _, pending := tx.writes[node.def]; pending {
		base, _ := tx.g.nodes[node.def].value.(StructSnapshot[T])
		combined, err := cur.ChangesSince(base).Then(next.ChangesSince(cur))
		if err != nil {
			panic(err)
		}
		// Keep composition cheap between edits. Commit normalizes nested field
		// metadata once, rather than materializing the accumulated delta here.
		next = combined.After()
	}
	tx.writes[node.def] = next
}

type structSchema struct{ fields []reflect.StructField }

var structSchemas sync.Map // reflect.Type -> *structSchema

func structSchemaFor[T any]() *structSchema {
	t := typeOf[T]()
	if s, ok := structSchemas.Load(t); ok {
		return s.(*structSchema)
	}
	if t.Kind() != reflect.Struct {
		panic(fmt.Sprintf("reco: struct schema must be a struct, got %s", t))
	}
	s := &structSchema{}
	for i := range t.NumField() {
		f := t.Field(i)
		if !f.IsExported() || f.Anonymous {
			panic(fmt.Sprintf("reco: struct field %s must be exported and non-embedded", f.Name))
		}
		s.fields = append(s.fields, f)
	}
	actual, _ := structSchemas.LoadOrStore(t, s)
	return actual.(*structSchema)
}

func setStructField(field reflect.Value, value any) {
	if value == nil {
		field.SetZero()
	} else {
		field.Set(reflect.ValueOf(value))
	}
}

type structFieldChange interface {
	empty() bool
	changeCount() int
	then(structFieldChange) structFieldChange
	normalizedValue() any
}

type structDiffer interface{ recoStructDiff(any) structFieldChange }

func diffStructField(typ reflect.Type, before, after any) structFieldChange {
	if valuesEqual(before, after) {
		return nil
	}
	// Interface fields are atomic: their dynamic value types can change.
	if typ.Kind() != reflect.Interface {
		if d, ok := after.(structDiffer); ok {
			return d.recoStructDiff(before)
		}
	}
	return scalarStructChange{before: before, after: after, changed: true}
}

type scalarStructChange struct {
	before, after any
	changed       bool
}

func (c scalarStructChange) empty() bool { return !c.changed }
func (c scalarStructChange) changeCount() int {
	if c.changed {
		return 1
	}
	return 0
}
func (c scalarStructChange) normalizedValue() any { return c.after }
func (c scalarStructChange) then(next structFieldChange) structFieldChange {
	after := next.(scalarStructChange).after
	return scalarStructChange{before: c.before, after: after, changed: !valuesEqual(c.before, after)}
}
