package reco

import (
	"hash/maphash"
	"iter"
	"reflect"
	"slices"

	"github.com/benbjohnson/immutable"
)

// MapChange describes one key changed by a map transaction. Before and After
// are accompanied by validity bits so zero values remain unambiguous.
type MapChange[K comparable, V any] struct {
	Key         K
	Before      V
	BeforeValid bool
	After       V
	AfterValid  bool
}

// MapSnapshot is a stable, structurally shared snapshot of a map node value.
// Point updates copy only the HAMT path to the changed key.
// The zero value is an empty map; use WithDelta to construct or update one.
// Entry values use ValueEqualer when implemented, otherwise Go equality for
// comparable values and reflect.DeepEqual for other values. Pointer values are
// compared by identity, not by the contents of the objects they point to.
type MapSnapshot[K comparable, V any] struct {
	items    *immutable.Map[K, V]
	changes  []MapChange[K, V]
	version  Version
	base     *immutable.Map[K, V]
	hasDelta bool
}

// Get returns a value by key.
func (s MapSnapshot[K, V]) Get(k K) (V, bool) {
	if s.items == nil {
		var zero V
		return zero, false
	}
	return s.items.Get(k)
}

// Len returns the number of map entries.
func (s MapSnapshot[K, V]) Len() int {
	if s.items == nil {
		return 0
	}
	return s.items.Len()
}

// Range calls fn for each entry in unspecified order until fn returns false.
func (s MapSnapshot[K, V]) Range(fn func(K, V) bool) {
	if s.items == nil {
		return
	}
	itr := s.items.Iterator()
	for !itr.Done() {
		k, v, ok := itr.Next()
		if !ok || !fn(k, v) {
			return
		}
	}
}

// All returns an iterator over the snapshot's key/value pairs in unspecified
// order. It visits entries lazily without copying the backing map and stops
// when the range loop breaks. Each iteration starts afresh over this snapshot,
// even if the graph has since changed. A zero snapshot yields no entries.
func (s MapSnapshot[K, V]) All() iter.Seq2[K, V] {
	return s.Range
}

// Changes returns a copy of the net point mutations that produced this snapshot.
// It is not relative to an arbitrary reader's previous snapshot; use ChangesSince
// when consuming updates, including unchanged inputs and snapshot replacements.
func (s MapSnapshot[K, V]) Changes() []MapChange[K, V] {
	return slices.Clone(s.changes)
}

// Version returns the graph-local version at which this snapshot was observed.
func (s MapSnapshot[K, V]) Version() Version {
	return s.version
}

// RecoValueEqual implements ValueEqualer using persistent-root identity.
func (s MapSnapshot[K, V]) RecoValueEqual(other any) bool {
	o, ok := other.(MapSnapshot[K, V])
	return ok && s.items == o.items
}

// RecoWithVersion implements VersionedValue without mutating this snapshot.
func (s MapSnapshot[K, V]) RecoWithVersion(version Version) any {
	s.version = version
	return s
}

// Collapse repeated writes by key without scanning untouched entries. Reuse
// the old root when a transaction undoes all its own changes.
func (s MapSnapshot[K, V]) recoNormalize(previous any) any {
	prev, _ := previous.(MapSnapshot[K, V])
	if !s.hasDelta || s.base != prev.items {
		return s
	}
	seen := make(map[K]bool)
	changes := make([]MapChange[K, V], 0, len(s.changes))
	for _, c := range s.changes {
		if seen[c.Key] {
			continue
		}
		seen[c.Key] = true
		before, was := prev.Get(c.Key)
		after, now := s.Get(c.Key)
		if was == now && (!was || mapValuesEqual(before, after)) {
			continue
		}
		changes = append(changes, MapChange[K, V]{Key: c.Key, Before: before, BeforeValid: was, After: after, AfterValid: now})
	}
	if len(changes) == 0 {
		return prev
	}
	s.changes = changes
	return s
}

// SetChange describes one key changed by a set transaction.
type SetChange[K comparable] struct {
	Key     K
	Present bool
}

// SetSnapshot is a stable, structurally shared snapshot of a set node value.
// The zero value is an empty set; use WithDelta to construct or update one.
type SetSnapshot[K comparable] struct {
	items   *immutable.Map[K, struct{}]
	changes []SetChange[K]
	version Version
	// base identifies the snapshot to which changes apply. An arbitrary
	// replacement via Set has no delta and requires a full reconciliation.
	base     *immutable.Map[K, struct{}]
	hasDelta bool
}

// Contains reports whether k is present.
func (s SetSnapshot[K]) Contains(k K) bool {
	if s.items == nil {
		return false
	}
	_, ok := s.items.Get(k)
	return ok
}

// Len returns the number of set entries.
func (s SetSnapshot[K]) Len() int {
	if s.items == nil {
		return 0
	}
	return s.items.Len()
}

// Range calls fn for each key in unspecified order until fn returns false.
func (s SetSnapshot[K]) Range(fn func(K) bool) {
	if s.items == nil {
		return
	}
	itr := s.items.Iterator()
	for !itr.Done() {
		k, _, ok := itr.Next()
		if !ok || !fn(k) {
			return
		}
	}
}

// All returns an iterator over the snapshot's keys in unspecified order.
// It visits keys lazily without copying the backing set and stops when the
// range loop breaks. Each iteration starts afresh over this snapshot, even if
// the graph has since changed. A zero snapshot yields no keys.
func (s SetSnapshot[K]) All() iter.Seq[K] {
	return s.Range
}

// Changes returns a copy of the net point mutations that produced this snapshot.
// It is not relative to an arbitrary reader's previous snapshot; use ChangesSince
// when consuming updates, including unchanged inputs and snapshot replacements.
func (s SetSnapshot[K]) Changes() []SetChange[K] {
	return slices.Clone(s.changes)
}

// Version returns the graph-local version at which this snapshot was observed.
func (s SetSnapshot[K]) Version() Version {
	return s.version
}

// RecoValueEqual implements ValueEqualer using persistent-root identity.
func (s SetSnapshot[K]) RecoValueEqual(other any) bool {
	o, ok := other.(SetSnapshot[K])
	return ok && s.items == o.items
}

// RecoWithVersion implements VersionedValue without mutating this snapshot.
func (s SetSnapshot[K]) RecoWithVersion(version Version) any {
	s.version = version
	return s
}

func (s SetSnapshot[K]) recoNormalize(previous any) any {
	prev, _ := previous.(SetSnapshot[K])
	if !s.hasDelta || s.base != prev.items {
		return s
	}
	seen := make(map[K]bool)
	changes := make([]SetChange[K], 0, len(s.changes))
	for _, c := range s.changes {
		if seen[c.Key] {
			continue
		}
		seen[c.Key] = true
		now := s.Contains(c.Key)
		if prev.Contains(c.Key) != now {
			changes = append(changes, SetChange[K]{Key: c.Key, Present: now})
		}
	}
	if len(changes) == 0 {
		return prev
	}
	s.changes = changes
	return s
}

// MapData creates a mutable map data node.
func MapData[K comparable, V any](className NodeClassName) Node[MapSnapshot[K, V]] {
	return Data[MapSnapshot[K, V]](className)
}

// SetData creates a mutable set data node.
func SetData[K comparable](className NodeClassName) Node[SetSnapshot[K]] {
	return Data[SetSnapshot[K]](className)
}

// MapPut upserts one map entry.
func MapPut[K comparable, V any](tx *Tx, node Node[MapSnapshot[K, V]], key K, value V) {
	tx.mustData(node.def)
	cur, _ := tx.current(node.def).value.(MapSnapshot[K, V])
	items := cur.items
	if items == nil {
		items = immutable.NewMap[K, V](newComparableHasher[K]())
	}
	before, beforeValid := items.Get(key)
	if beforeValid && mapValuesEqual(before, value) {
		return
	}
	changes := []MapChange[K, V](nil)
	base, hasDelta := cur.items, true
	if _, pending := tx.writes[node.def]; pending {
		changes = cur.changes
		base, hasDelta = cur.base, cur.hasDelta
	}
	changes = append(changes, MapChange[K, V]{
		Key:         key,
		Before:      before,
		BeforeValid: beforeValid,
		After:       value,
		AfterValid:  true,
	})
	tx.writes[node.def] = MapSnapshot[K, V]{items: items.Set(key, value), changes: changes, base: base, hasDelta: hasDelta}
}

// MapDelete removes one map entry.
func MapDelete[K comparable, V any](tx *Tx, node Node[MapSnapshot[K, V]], key K) {
	tx.mustData(node.def)
	cur, _ := tx.current(node.def).value.(MapSnapshot[K, V])
	if cur.items == nil {
		return
	}
	before, ok := cur.items.Get(key)
	if !ok {
		return
	}
	changes := []MapChange[K, V](nil)
	base, hasDelta := cur.items, true
	if _, pending := tx.writes[node.def]; pending {
		changes = cur.changes
		base, hasDelta = cur.base, cur.hasDelta
	}
	changes = append(changes, MapChange[K, V]{
		Key:         key,
		Before:      before,
		BeforeValid: true,
	})
	tx.writes[node.def] = MapSnapshot[K, V]{items: cur.items.Delete(key), changes: changes, base: base, hasDelta: hasDelta}
}

// SetUpsert adds one set key.
func SetUpsert[K comparable](tx *Tx, node Node[SetSnapshot[K]], key K) {
	tx.mustData(node.def)
	cur, _ := tx.current(node.def).value.(SetSnapshot[K])
	if cur.Contains(key) {
		return
	}
	items := cur.items
	if items == nil {
		items = immutable.NewMap[K, struct{}](newComparableHasher[K]())
	}
	changes := []SetChange[K](nil)
	base, hasDelta := cur.items, true
	if _, pending := tx.writes[node.def]; pending {
		changes = cur.changes
		base, hasDelta = cur.base, cur.hasDelta
	}
	changes = append(changes, SetChange[K]{Key: key, Present: true})
	tx.writes[node.def] = SetSnapshot[K]{items: items.Set(key, struct{}{}), changes: changes, base: base, hasDelta: hasDelta}
}

// SetDelete removes one set key.
func SetDelete[K comparable](tx *Tx, node Node[SetSnapshot[K]], key K) {
	tx.mustData(node.def)
	cur, _ := tx.current(node.def).value.(SetSnapshot[K])
	if cur.items == nil || !cur.Contains(key) {
		return
	}
	changes := []SetChange[K](nil)
	base, hasDelta := cur.items, true
	if _, pending := tx.writes[node.def]; pending {
		changes = cur.changes
		base, hasDelta = cur.base, cur.hasDelta
	}
	changes = append(changes, SetChange[K]{Key: key})
	tx.writes[node.def] = SetSnapshot[K]{items: cur.items.Delete(key), changes: changes, base: base, hasDelta: hasDelta}
}

// SetDelta is an atomic clear, remove, then add batch. Add wins when a key is
// also removed. The enclosing transaction may batch changes to other nodes.
type SetDelta[K comparable] struct {
	Clear  bool
	Remove []K
	Add    []K
}

// ApplySetDelta applies d inside tx. Clear enumerates existing members so the
// prototype's point-change subscribers also receive every removal.
func ApplySetDelta[K comparable](tx *Tx, node Node[SetSnapshot[K]], d SetDelta[K]) {
	tx.mustData(node.def)
	if d.Clear {
		cur, _ := tx.current(node.def).value.(SetSnapshot[K])
		cur.Range(func(k K) bool {
			SetDelete(tx, node, k)
			return true
		})
	}
	for _, k := range d.Remove {
		SetDelete(tx, node, k)
	}
	for _, k := range d.Add {
		SetUpsert(tx, node, k)
	}
}

type comparableHasher[K comparable] struct {
	seed maphash.Seed
}

func newComparableHasher[K comparable]() *comparableHasher[K] {
	return &comparableHasher[K]{seed: maphash.MakeSeed()}
}

func (h *comparableHasher[K]) Hash(key K) uint32 {
	v := maphash.Comparable(h.seed, key)
	return uint32(v) ^ uint32(v>>32)
}

func (*comparableHasher[K]) Equal(a, b K) bool { return a == b }

// Map entry equality must preserve comparable identities: downstream nodes
// may use values as set keys. Deep-equal but distinct pointers are not the same
// key. Keep custom equality hooks and support non-comparable map values too.
func mapValuesEqual(a, b any) bool {
	if equaler, ok := a.(ValueEqualer); ok {
		return equaler.RecoValueEqual(b)
	}
	if reflect.ValueOf(a).Comparable() && reflect.ValueOf(b).Comparable() {
		return a == b
	}
	return reflect.DeepEqual(a, b)
}
