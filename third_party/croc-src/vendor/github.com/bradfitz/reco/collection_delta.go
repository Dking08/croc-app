package reco

import (
	"iter"

	"github.com/benbjohnson/immutable"
)

// MapEntry is a whole key/value assignment, including a nil value if V allows it.
type MapEntry[K comparable, V any] struct {
	Key   K
	Value V
}

// MapDelta describes one atomic clear, remove, then put batch. Put wins over
// removal; repeated Put keys use the last entry in the slice. A nil value is
// distinct from removal. Clear plus Put replaces the entire map.
type MapDelta[K comparable, V any] struct {
	Clear  bool
	Remove []K
	Put    []MapEntry[K, V]
}

// WithDelta returns a new snapshot after applying d, without modifying s or
// any published value. Point edits copy only their immutable HAMT paths. Clear
// enumerates removals for the point-change event API. Net-zero edits return s.
// A changed result has Version zero until observed by a graph, and its Changes
// describe the net changes from s. A no-op returns s, including its old Changes;
// use ChangesSince(s) to distinguish this case. Prefer one batch per operator
// evaluation to preserve the O(delta) path through ChangesSince and SubscribeMap.
func (s SetSnapshot[K]) WithDelta(d SetDelta[K]) SetSnapshot[K] {
	next := SetSnapshot[K]{items: s.items, base: s.items, hasDelta: true}
	if d.Clear {
		for k := range s.All() {
			next.changes = append(next.changes, SetChange[K]{Key: k})
		}
		next.items = nil
	}
	for _, k := range d.Remove {
		if next.Contains(k) {
			next.items = next.items.Delete(k)
			next.changes = append(next.changes, SetChange[K]{Key: k})
		}
	}
	for _, k := range d.Add {
		if next.Contains(k) {
			continue
		}
		if next.items == nil {
			next.items = immutable.NewMap[K, struct{}](newComparableHasher[K]())
		}
		next.items = next.items.Set(k, struct{}{})
		next.changes = append(next.changes, SetChange[K]{Key: k, Present: true})
	}
	return next.recoNormalize(s).(SetSnapshot[K])
}

// WithDelta returns a new snapshot after applying d, without modifying s.
// It preserves HAMT structural sharing, normalizes repeated touched keys, and
// returns s for a net-zero change. Changed snapshots have Version zero until
// observed by a graph. Values must be treated as immutable by callers.
// Prefer one batch per operator evaluation; see SetSnapshot.WithDelta.
func (s MapSnapshot[K, V]) WithDelta(d MapDelta[K, V]) MapSnapshot[K, V] {
	next := MapSnapshot[K, V]{items: s.items, base: s.items, hasDelta: true}
	if d.Clear {
		for k, v := range s.All() {
			next.changes = append(next.changes, MapChange[K, V]{Key: k, Before: v, BeforeValid: true})
		}
		next.items = nil
	}
	for _, k := range d.Remove {
		if before, ok := next.Get(k); ok {
			next.items = next.items.Delete(k)
			next.changes = append(next.changes, MapChange[K, V]{Key: k, Before: before, BeforeValid: true})
		}
	}
	for _, entry := range d.Put {
		before, ok := next.Get(entry.Key)
		if ok && mapValuesEqual(before, entry.Value) {
			continue
		}
		if next.items == nil {
			next.items = immutable.NewMap[K, V](newComparableHasher[K]())
		}
		next.items = next.items.Set(entry.Key, entry.Value)
		next.changes = append(next.changes, MapChange[K, V]{Key: entry.Key, Before: before, BeforeValid: ok, After: entry.Value, AfterValid: true})
	}
	return next.recoNormalize(s).(MapSnapshot[K, V])
}

// ChangesSince iterates net membership changes from previous to s. Consecutive
// snapshots use their recorded delta in O(delta) work; identical backing roots
// yield nothing. Initial snapshots, skipped versions, and arbitrary replacements
// fall back to comparing memberships, which can take O(N). Versions alone are
// not used to establish lineage, since snapshots can come from different graphs.
// Each key is yielded at most once, in unspecified order.
func (s SetSnapshot[K]) ChangesSince(previous SetSnapshot[K]) iter.Seq[SetChange[K]] {
	return func(yield func(SetChange[K]) bool) {
		if s.items == previous.items {
			return
		}
		if s.hasDelta && s.base == previous.items {
			for _, c := range s.changes {
				if !yield(c) {
					return
				}
			}
			return
		}
		for k := range previous.All() {
			if !s.Contains(k) && !yield(SetChange[K]{Key: k}) {
				return
			}
		}
		for k := range s.All() {
			if !previous.Contains(k) && !yield(SetChange[K]{Key: k, Present: true}) {
				return
			}
		}
	}
}

// ChangesSince iterates net entry changes from previous to s. It uses a recorded
// delta when its base matches previous, otherwise compares the snapshots (O(N)).
// Identical roots yield nothing. Each key appears at most once, in unspecified
// order, with whole before/after values and explicit presence bits.
func (s MapSnapshot[K, V]) ChangesSince(previous MapSnapshot[K, V]) iter.Seq[MapChange[K, V]] {
	return func(yield func(MapChange[K, V]) bool) {
		if s.items == previous.items {
			return
		}
		if s.hasDelta && s.base == previous.items {
			for _, c := range s.changes {
				if !yield(c) {
					return
				}
			}
			return
		}
		for k, before := range previous.All() {
			after, ok := s.Get(k)
			if ok && mapValuesEqual(before, after) {
				continue
			}
			if !yield(MapChange[K, V]{Key: k, Before: before, BeforeValid: true, After: after, AfterValid: ok}) {
				return
			}
		}
		for k, after := range s.All() {
			if _, ok := previous.Get(k); !ok && !yield(MapChange[K, V]{Key: k, After: after, AfterValid: true}) {
				return
			}
		}
	}
}
