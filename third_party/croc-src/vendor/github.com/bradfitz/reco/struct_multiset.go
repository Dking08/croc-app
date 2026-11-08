package reco

import "iter"

// MultisetFieldChanges yields a multiset field's net count changes, including
// after StructChanges.Then coalescing. It visits only changed keys, never
// re-diffing the endpoint collections. Zero counts denote absence.
func MultisetFieldChanges[T any, K comparable](field StructField[T, MultisetSnapshot[K]], c StructChanges[T]) iter.Seq[MultisetChange[K]] {
	field.check()
	return func(yield func(MultisetChange[K]) bool) {
		if len(c.fields) == 0 || c.fields[field.index] == nil {
			return
		}
		entries := c.fields[field.index].(multisetStructChange[K]).entries
		if entries == nil {
			return
		}
		it := entries.Iterator()
		for !it.Done() {
			_, change, _ := it.Next()
			if !yield(MultisetChange[K]{Key: change.Key, Before: change.Before, After: change.After}) {
				return
			}
		}
	}
}

// Reuse map delta composition while keeping the normalized field's concrete
// type MultisetSnapshot (not its backing MapSnapshot).
type multisetStructChange[K comparable] struct{ mapStructChange[K, int64] }

func (s MultisetSnapshot[K]) recoStructDiff(previous any) structFieldChange {
	c := s.counts.recoStructDiff(previous.(MultisetSnapshot[K]).counts).(mapStructChange[K, int64])
	return multisetStructChange[K]{c}
}

func (c multisetStructChange[K]) then(next structFieldChange) structFieldChange {
	n := next.(multisetStructChange[K])
	return multisetStructChange[K]{c.mapStructChange.then(n.mapStructChange).(mapStructChange[K, int64])}
}

func (c multisetStructChange[K]) normalizedValue() any {
	return MultisetSnapshot[K]{counts: c.mapStructChange.normalizedValue().(MapSnapshot[K, int64])}
}
