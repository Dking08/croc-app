package reco

import (
	"errors"
	"fmt"
	"iter"
	"math"
)

// ErrMultisetCount reports a negative count or an int64 count overflow.
var ErrMultisetCount = errors.New("reco: multiset count out of range")

// MultisetEntry assigns a key's multiplicity. Count must be nonnegative;
// zero removes the key. Counts are per key, not a bounded collection total.
type MultisetEntry[K comparable] struct {
	Key   K
	Count int64
}

// MultisetDelta is an atomic clear, remove, put, then adjust batch.
// Remove deletes all occurrences. Repeated Put keys use the last assignment.
// Adjust adds one signed net change per key; it does not assign a count.
// Every Put count and every final adjusted count must be in [0, math.MaxInt64].
// Clear plus Put replaces the multiset. Input slices/maps are not retained.
//
// Additive adjustments are not idempotent: replaying +1 adds another occurrence.
// Remote delivery still needs base/version checks; this is not a CRDT counter.
type MultisetDelta[K comparable] struct {
	Clear  bool
	Remove []K
	Put    []MultisetEntry[K]
	Adjust map[K]int64
}

// MultisetChange is a net multiplicity change. Zero denotes absence.
// Before and After are nonnegative; After-Before is the signed adjustment.
type MultisetChange[K comparable] struct {
	Key           K
	Before, After int64
}

// MultisetSnapshot is an immutable counted set, backed by a persistent map.
// The zero value is empty. Only positive counts are stored; removing one
// occurrence does not remove a key until its last occurrence disappears.
// Keys use Go equality and must be reflexive (no NaNs), as for SetSnapshot.
// Interface keys must be dynamically comparable.
type MultisetSnapshot[K comparable] struct {
	counts MapSnapshot[K, int64]
}

// Count returns k's multiplicity, or zero if absent.
func (s MultisetSnapshot[K]) Count(k K) int64 { n, _ := s.counts.Get(k); return n }

// Len returns the number of DISTINCT keys, not the sum of their counts.
func (s MultisetSnapshot[K]) Len() int { return s.counts.Len() }

// Range visits each distinct key and its positive count in unspecified order,
// stopping when fn returns false. It does not repeat a key Count times.
func (s MultisetSnapshot[K]) Range(fn func(K, int64) bool) { s.counts.Range(fn) }

// All lazily iterates distinct key/count pairs, as Range does.
func (s MultisetSnapshot[K]) All() iter.Seq2[K, int64] { return s.Range }

// Counts returns an immutable map view without copying. Updating the returned
// map does not modify the multiset. There is no unchecked map-to-multiset cast.
func (s MultisetSnapshot[K]) Counts() MapSnapshot[K, int64] { return s.counts }

// Version returns the graph-local observation version.
func (s MultisetSnapshot[K]) Version() Version { return s.counts.Version() }

// RecoValueEqual implements ValueEqualer using persistent-root identity.
func (s MultisetSnapshot[K]) RecoValueEqual(other any) bool {
	o, ok := other.(MultisetSnapshot[K])
	return ok && s.counts.RecoValueEqual(o.counts)
}

// RecoWithVersion implements VersionedValue without modifying this snapshot.
func (s MultisetSnapshot[K]) RecoWithVersion(v Version) any {
	s.counts.version = v
	return s
}

func (s MultisetSnapshot[K]) recoNormalize(previous any) any {
	prev, _ := previous.(MultisetSnapshot[K])
	s.counts = s.counts.recoNormalize(prev.counts).(MapSnapshot[K, int64])
	return s
}

func (s MultisetSnapshot[K]) recoAccumulate(original, previous any) any {
	base, _ := original.(MultisetSnapshot[K])
	prev, _ := previous.(MultisetSnapshot[K])
	s.counts = s.counts.recoAccumulate(base.counts, prev.counts).(MapSnapshot[K, int64])
	return s
}

// WithDelta applies d atomically. An invalid count returns s and an error
// wrapping ErrMultisetCount, without applying ANY part of d. Underflow is an
// error, not a clamped deletion. Net-zero updates reuse the original root.
// Point edits do touched-key work plus HAMT path copying; Clear enumerates
// existing keys to report removals. Changed snapshots have Version zero.
func (s MultisetSnapshot[K]) WithDelta(d MultisetDelta[K]) (MultisetSnapshot[K], error) {
	touched := make(map[K]int64, len(d.Remove)+len(d.Put)+len(d.Adjust))
	for _, k := range d.Remove {
		touched[k] = 0
	}
	for _, e := range d.Put {
		if e.Count < 0 {
			return s, fmt.Errorf("%w: negative Put count for %v", ErrMultisetCount, e.Key)
		}
		touched[e.Key] = e.Count
	}
	for k, diff := range d.Adjust {
		before, ok := touched[k]
		if !ok && !d.Clear {
			before = s.Count(k)
		}
		if diff < -before || diff > 0 && before > math.MaxInt64-diff {
			return s, fmt.Errorf("%w: adjusting %v by %d from %d", ErrMultisetCount, k, diff, before)
		}
		touched[k] = before + diff
	}
	md := MapDelta[K, int64]{Clear: d.Clear}
	for k, n := range touched {
		if n == 0 {
			md.Remove = append(md.Remove, k)
		} else {
			md.Put = append(md.Put, MapEntry[K, int64]{Key: k, Value: n})
		}
	}
	return MultisetSnapshot[K]{counts: s.counts.WithDelta(md)}, nil
}

// ChangesSince yields one net change per touched key in unspecified order.
// Identical roots yield nothing, adjacent deltas take O(delta) work plus map
// traversal, and skipped bases/replacements may require O(N) reconciliation.
func (s MultisetSnapshot[K]) ChangesSince(previous MultisetSnapshot[K]) iter.Seq[MultisetChange[K]] {
	return func(yield func(MultisetChange[K]) bool) {
		for c := range s.counts.ChangesSince(previous.counts) {
			if !yield(MultisetChange[K]{Key: c.Key, Before: c.Before, After: c.After}) {
				return
			}
		}
	}
}

// Changes returns a copy of this snapshot's last net count changes, not changes
// relative to an arbitrary reader. No-op edits retain old metadata, so prefer
// ChangesSince when consuming updates.
func (s MultisetSnapshot[K]) Changes() []MultisetChange[K] {
	var changes []MultisetChange[K]
	for _, c := range s.counts.changes {
		changes = append(changes, MultisetChange[K]{Key: c.Key, Before: c.Before, After: c.After})
	}
	return changes
}

// MultisetData declares a mutable counted-set leaf. Use ApplyMultisetDelta or
// Set with a validated snapshot. Ordinary SubscribeSnapshot observes its changes.
func MultisetData[K comparable](name NodeClassName) Node[MultisetSnapshot[K]] {
	return Data[MultisetSnapshot[K]](name)
}

// ApplyMultisetDelta applies one validated batch to the staged value. On error
// it leaves this call's staged value unchanged; return the error from Update
// to abort the entire transaction. Each call must leave valid counts, even if
// a subsequent call would undo it. Multiple calls retain the transaction's
// original delta base and normalize net-zero changes at commit.
func ApplyMultisetDelta[K comparable](tx *Tx, node Node[MultisetSnapshot[K]], d MultisetDelta[K]) error {
	tx.mustData(node.def)
	cur, _ := tx.current(node.def).value.(MultisetSnapshot[K])
	next, err := cur.WithDelta(d)
	if err != nil {
		return err
	}
	if next.RecoValueEqual(cur) {
		return nil
	}
	if _, pending := tx.writes[node.def]; pending {
		owned, ok := tx.multisetWrites[node.def].(MultisetSnapshot[K])
		reuse := ok && owned.counts.items == cur.counts.items
		if reuse {
			cur = owned // Preserve the transaction's history even after a same-root Set.
		}
		base, _ := tx.g.nodes[node.def].value.(MultisetSnapshot[K])
		if cur.counts.items != base.counts.items {
			if cur.counts.hasDelta && cur.counts.base == base.counts.items {
				changes := cur.counts.changes
				if !reuse {
					changes = append([]MapChange[K, int64](nil), changes...)
				}
				// Amortized append, not a copy of every earlier edit per call.
				next.counts.changes = append(changes, next.counts.changes...)
				next.counts.base = base.counts.items
			} else {
				// A caller-supplied unrelated replacement needs reconciliation.
				next.counts.hasDelta = false
			}
		}
	}
	tx.writes[node.def] = next
	if tx.multisetWrites == nil {
		tx.multisetWrites = make(map[*nodeDef]any)
	}
	tx.multisetWrites[node.def] = next
	return nil
}
