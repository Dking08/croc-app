package nodes

import (
	"fmt"
	"reflect"

	"github.com/bradfitz/reco"
)

// Struct assembles a typed immutable record from a struct of field input nodes.
// T is the record schema. inputs must be a struct (or non-nil pointer to one)
// with exactly the same field names/order as T, each containing reco.Node[V]
// for the corresponding field type V. Like Func, bindings are checked once at
// declaration time. All inputs must initialize before the record is produced.
//
// Assembly copies the fixed-width record, not its referenced collections. Field
// equality respects persistent roots, and SubscribeStruct preserves nested map/
// set changes. No hand-written deep comparison or record delta mapper is needed.
// Like an inline Func, this assembles values, not input error/partial metadata;
// use Operator when the record needs an application-specific readiness policy.
func Struct[T any](className reco.NodeClassName, inputs any) reco.Node[reco.StructSnapshot[T]] {
	var zero T
	reco.NewStruct(zero) // Validate the record schema, including exported fields.
	schema := reflect.TypeOf(zero)
	t := reflect.TypeOf(inputs)
	if t != nil && t.Kind() == reflect.Pointer {
		t = t.Elem()
	}
	if t == nil || t.Kind() != reflect.Struct || t.NumField() != schema.NumField() {
		panic("nodes: Struct bindings must match all schema fields")
	}
	for i := range schema.NumField() {
		if t.Field(i).Name != schema.Field(i).Name || !t.Field(i).IsExported() {
			panic(fmt.Sprintf("nodes: Struct binding field %d must be %s", i, schema.Field(i).Name))
		}
	}
	return reco.Func(className, reco.Deps(inputs), func(_ reco.Eval, in T) reco.Result[reco.StructSnapshot[T]] {
		return reco.OK(reco.NewStruct(in))
	})
}
