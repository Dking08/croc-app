package reco

import (
	"iter"

	"github.com/benbjohnson/immutable"
)

// MapFieldChanges yields a map field's net entry changes, even when c combines
// many events. It never re-diffs the endpoints. Entries have explicit before/
// after presence bits, preserving nil and zero values. Order is unspecified.
func MapFieldChanges[T any, K comparable, V any](field StructField[T, MapSnapshot[K, V]], c StructChanges[T]) iter.Seq[MapChange[K, V]] {
	field.check()
	return func(yield func(MapChange[K, V]) bool) {
		if len(c.fields) == 0 || c.fields[field.index] == nil {
			return
		}
		changes := c.fields[field.index].(mapStructChange[K, V]).entries
		if changes == nil {
			return
		}
		it := changes.Iterator()
		for !it.Done() {
			_, change, _ := it.Next()
			if !yield(change) {
				return
			}
		}
	}
}

// SetFieldChanges yields a set field's net membership changes, even when c
// combines many events. It never re-diffs the endpoints. Order is unspecified.
func SetFieldChanges[T any, K comparable](field StructField[T, SetSnapshot[K]], c StructChanges[T]) iter.Seq[SetChange[K]] {
	field.check()
	return func(yield func(SetChange[K]) bool) {
		if len(c.fields) == 0 || c.fields[field.index] == nil {
			return
		}
		changes := c.fields[field.index].(setStructChange[K]).entries
		if changes == nil {
			return
		}
		it := changes.Iterator()
		for !it.Done() {
			_, change, _ := it.Next()
			if !yield(change) {
				return
			}
		}
	}
}

type mapStructChange[K comparable, V any] struct {
	before, after MapSnapshot[K, V]
	entries       *immutable.Map[K, MapChange[K, V]]
}

func (s MapSnapshot[K, V]) recoStructDiff(previous any) structFieldChange {
	before := previous.(MapSnapshot[K, V])
	c := mapStructChange[K, V]{before: before, after: s}
	for change := range s.ChangesSince(before) {
		if c.entries == nil {
			c.entries = immutable.NewMap[K, MapChange[K, V]](newComparableHasher[K]())
		}
		c.entries = c.entries.Set(change.Key, change)
	}
	return c
}

func (c mapStructChange[K, V]) empty() bool { return c.entries == nil || c.entries.Len() == 0 }

func (c mapStructChange[K, V]) changeCount() int {
	if c.entries == nil {
		return 0
	}
	return c.entries.Len()
}

func (c mapStructChange[K, V]) then(next structFieldChange) structFieldChange {
	n := next.(mapStructChange[K, V])
	out := c
	out.after = n.after
	if n.entries == nil {
		return out
	}
	it := n.entries.Iterator()
	for !it.Done() {
		key, change, _ := it.Next()
		if out.entries == nil {
			out.entries = immutable.NewMap[K, MapChange[K, V]](newComparableHasher[K]())
		}
		if first, ok := out.entries.Get(key); ok {
			change.Before, change.BeforeValid = first.Before, first.BeforeValid
		}
		if change.BeforeValid == change.AfterValid && (!change.BeforeValid || mapValuesEqual(change.Before, change.After)) {
			out.entries = out.entries.Delete(key)
		} else {
			out.entries = out.entries.Set(key, change)
		}
	}
	return out
}

func (c mapStructChange[K, V]) normalizedValue() any {
	if c.empty() {
		return c.before
	}
	s := c.after
	s.base, s.hasDelta = c.before.items, true
	s.changes = make([]MapChange[K, V], 0, c.entries.Len())
	it := c.entries.Iterator()
	for !it.Done() {
		_, change, _ := it.Next()
		s.changes = append(s.changes, change)
	}
	return s
}

type setStructChange[K comparable] struct {
	before, after SetSnapshot[K]
	entries       *immutable.Map[K, SetChange[K]]
}

func (s SetSnapshot[K]) recoStructDiff(previous any) structFieldChange {
	before := previous.(SetSnapshot[K])
	c := setStructChange[K]{before: before, after: s}
	for change := range s.ChangesSince(before) {
		if c.entries == nil {
			c.entries = immutable.NewMap[K, SetChange[K]](newComparableHasher[K]())
		}
		c.entries = c.entries.Set(change.Key, change)
	}
	return c
}

func (c setStructChange[K]) empty() bool { return c.entries == nil || c.entries.Len() == 0 }

func (c setStructChange[K]) changeCount() int {
	if c.entries == nil {
		return 0
	}
	return c.entries.Len()
}

func (c setStructChange[K]) then(next structFieldChange) structFieldChange {
	n := next.(setStructChange[K])
	out := c
	out.after = n.after
	if n.entries == nil {
		return out
	}
	it := n.entries.Iterator()
	for !it.Done() {
		key, change, _ := it.Next()
		if out.entries == nil {
			out.entries = immutable.NewMap[K, SetChange[K]](newComparableHasher[K]())
		}
		if first, ok := out.entries.Get(key); ok && first.Present != change.Present {
			out.entries = out.entries.Delete(key)
		} else {
			out.entries = out.entries.Set(key, change)
		}
	}
	return out
}

func (c setStructChange[K]) normalizedValue() any {
	if c.empty() {
		return c.before
	}
	s := c.after
	s.base, s.hasDelta = c.before.items, true
	s.changes = make([]SetChange[K], 0, c.entries.Len())
	it := c.entries.Iterator()
	for !it.Done() {
		_, change, _ := it.Next()
		s.changes = append(s.changes, change)
	}
	return s
}
