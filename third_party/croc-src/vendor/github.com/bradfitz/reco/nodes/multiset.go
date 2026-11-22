package nodes

import (
	"fmt"
	"math"

	"github.com/bradfitz/reco"
)

// SumMultisets adds multiplicities from two or more input multisets. This is
// addition, NOT the alternative bag-union operation that takes maximum counts.
// Repeated inputs contribute repeatedly. Changes across inputs are combined
// atomically; moving a contribution between inputs does not notify subscribers.
// A count above MaxInt64 produces a Result error wrapping reco.ErrMultisetCount;
// the operator recovers when later inputs have a representable sum. After
// initialization, work visits only changed inputs and their delta keys, not the
// whole input list or unchanged collection entries. Failed evaluations retain
// unapplied inputs and retry them on later changes until the sum succeeds.
func SumMultisets[K comparable](name reco.NodeClassName, inputs ...reco.Node[reco.MultisetSnapshot[K]]) reco.Node[reco.MultisetSnapshot[K]] {
	if len(inputs) < 2 {
		panic("nodes: SumMultisets needs at least two inputs")
	}
	deps, multiplicities := countInputs(inputs)
	return reco.Operator(name, deps, func() reco.Compute[reco.MultisetSnapshot[K]] {
		previous := make(map[reco.Node[reco.MultisetSnapshot[K]]]reco.MultisetSnapshot[K], len(multiplicities))
		var pending map[reco.Node[reco.MultisetSnapshot[K]]]reco.MultisetSnapshot[K]
		var out reco.MultisetSnapshot[K]
		return func(e reco.Eval) reco.Result[reco.MultisetSnapshot[K]] {
			// Record ALL changed inputs before any error can return: ChangedInputs
			// advances even on failure. Only a successful sum advances previous.
			if pending == nil {
				pending = make(map[reco.Node[reco.MultisetSnapshot[K]]]reco.MultisetSnapshot[K])
			}
			for dep := range e.ChangedInputs() {
				pending[dep.(reco.Node[reco.MultisetSnapshot[K]])] = reco.MultisetSnapshot[K]{}
			}
			net := make(map[K]int64)
			for n := range pending {
				dep := reco.Input(e, n)
				if dep.Err() != nil {
					return reco.Result[reco.MultisetSnapshot[K]]{Err: dep.Err()}
				}
				cur := dep.Value()
				pending[n] = cur
				weight := int64(multiplicities[n])
				for c := range cur.ChangesSince(previous[n]) {
					diff := c.After - c.Before
					// Counts are nonnegative, and the previous total fits int64.
					// If the new total also fits, neither the positive nor negative
					// contributions can exceed that range, in any iteration order.
					if diff > math.MaxInt64/weight || diff < math.MinInt64/weight {
						return reco.Result[reco.MultisetSnapshot[K]]{Err: fmt.Errorf("%w: summing %v", reco.ErrMultisetCount, c.Key)}
					}
					diff *= weight
					v := net[c.Key]
					if diff > 0 && v > math.MaxInt64-diff || diff < 0 && v < math.MinInt64-diff {
						return reco.Result[reco.MultisetSnapshot[K]]{Err: fmt.Errorf("%w: summing %v", reco.ErrMultisetCount, c.Key)}
					}
					net[c.Key] = v + diff
				}
			}
			next, err := out.WithDelta(reco.MultisetDelta[K]{Adjust: net})
			if err != nil {
				return reco.Result[reco.MultisetSnapshot[K]]{Err: err}
			}
			out = next
			for n, cur := range pending {
				previous[n] = cur
			}
			// Drop the table too: iterating a sparse table left over from initial
			// activation or error recovery must not cost O(all inputs) per edit.
			pending = nil
			return reco.OK(out)
		}
	})
}

// Distinct projects a multiset into its set of positive-count keys. Only zero
// crossings propagate: 2 -> 1 changes the multiset, not this output. It examines
// changed counts only, except for initial input or arbitrary replacements.
func Distinct[K comparable](name reco.NodeClassName, input reco.Node[reco.MultisetSnapshot[K]]) reco.Node[reco.SetSnapshot[K]] {
	return reco.Operator(name, []reco.Dependency{input}, func() reco.Compute[reco.SetSnapshot[K]] {
		var previous reco.MultisetSnapshot[K]
		var out reco.SetSnapshot[K]
		return func(e reco.Eval) reco.Result[reco.SetSnapshot[K]] {
			dep := reco.Input(e, input)
			if dep.Err() != nil {
				return reco.Result[reco.SetSnapshot[K]]{Err: dep.Err()}
			}
			cur := dep.Value()
			var d reco.SetDelta[K]
			for c := range cur.ChangesSince(previous) {
				if c.Before == 0 {
					d.Add = append(d.Add, c.Key)
				} else if c.After == 0 {
					d.Remove = append(d.Remove, c.Key)
				}
			}
			out, previous = out.WithDelta(d), cur
			return reco.OK(out)
		}
	})
}

// CountBy counts input map entries by group(key, value). Every entry contributes
// one, including entries with zero/nil values. group must be pure and is called
// for each delta entry's before/after values (at most twice per changed key).
// Counts aggregate across the whole transaction before publication, so moving
// entries within/between groups does not produce transient removals. Zero-count
// groups disappear. Point edits never enumerate all members of a group.
func CountBy[K comparable, V any, G comparable](name reco.NodeClassName, input reco.Node[reco.MapSnapshot[K, V]], group func(K, V) G) reco.Node[reco.MultisetSnapshot[G]] {
	if group == nil {
		panic("nodes: nil CountBy function")
	}
	return reco.Operator(name, []reco.Dependency{input}, func() reco.Compute[reco.MultisetSnapshot[G]] {
		var previous reco.MapSnapshot[K, V]
		var out reco.MultisetSnapshot[G]
		return func(e reco.Eval) reco.Result[reco.MultisetSnapshot[G]] {
			dep := reco.Input(e, input)
			if dep.Err() != nil {
				return reco.Result[reco.MultisetSnapshot[G]]{Err: dep.Err()}
			}
			cur := dep.Value()
			net := make(map[G]int64)
			for c := range cur.ChangesSince(previous) {
				if c.BeforeValid {
					net[group(c.Key, c.Before)]--
				}
				if c.AfterValid {
					net[group(c.Key, c.After)]++
				}
			}
			next, err := out.WithDelta(reco.MultisetDelta[G]{Adjust: net})
			if err != nil {
				return reco.Result[reco.MultisetSnapshot[G]]{Err: err}
			}
			out, previous = next, cur
			return reco.OK(out)
		}
	})
}

// GroupCounts counts set memberships by an independently keyed grouping map.
// For every k present in BOTH inputs, each member v of members[k] contributes
// one to out[groups[k]].Count(v). A zero-valued group is a real group; a missing
// group contributes nothing. Empty output groups are omitted. In particular,
// it counts contributing keys, not just distinct members across those keys.
//
// Both inputs are read at the same settled transaction boundary. A grouping
// change moves that key's entire set; a set change within an unchanged group
// visits only its membership delta. Work is proportional to changed outer keys
// and affected membership edges, plus persistent-map traversal, NOT all keys
// in a group. Replacements/skipped bases may require affected-set enumeration.
// One delta per output group combines all additions/removals before publication.
//
// This is a grouped count, not a Cartesian join: it does not build pairs of all
// members in two groups. Count per (owner, role), for example, rather than per
// (viewer node, peer node) to preserve a factored visibility representation.
func GroupCounts[K, G, V comparable](name reco.NodeClassName, members reco.Node[reco.MapSnapshot[K, reco.SetSnapshot[V]]], groups reco.Node[reco.MapSnapshot[K, G]]) reco.Node[reco.MapSnapshot[G, reco.MultisetSnapshot[V]]] {
	return reco.Operator(name, []reco.Dependency{members, groups}, func() reco.Compute[reco.MapSnapshot[G, reco.MultisetSnapshot[V]]] {
		var previousMembers reco.MapSnapshot[K, reco.SetSnapshot[V]]
		var previousGroups reco.MapSnapshot[K, G]
		var out reco.MapSnapshot[G, reco.MultisetSnapshot[V]]
		return func(e reco.Eval) reco.Result[reco.MapSnapshot[G, reco.MultisetSnapshot[V]]] {
			ms, grouping := reco.Input(e, members), reco.Input(e, groups)
			if ms.Err() != nil {
				return reco.Result[reco.MapSnapshot[G, reco.MultisetSnapshot[V]]]{Err: ms.Err()}
			}
			if grouping.Err() != nil {
				return reco.Result[reco.MapSnapshot[G, reco.MultisetSnapshot[V]]]{Err: grouping.Err()}
			}
			sets, gs := ms.Value(), grouping.Value()
			keys := make(map[K]bool)
			for c := range sets.ChangesSince(previousMembers) {
				keys[c.Key] = true
			}
			for c := range gs.ChangesSince(previousGroups) {
				keys[c.Key] = true
			}
			net := make(map[G]map[V]int64)
			adjust := func(g G, v V, diff int64) {
				if net[g] == nil {
					net[g] = make(map[V]int64)
				}
				net[g][v] += diff
			}
			for k := range keys {
				old, _ := previousMembers.Get(k)
				cur, _ := sets.Get(k)
				a, was := previousGroups.Get(k)
				b, now := gs.Get(k)
				if was && now && a == b {
					for c := range cur.ChangesSince(old) {
						if c.Present {
							adjust(a, c.Key, 1)
						} else {
							adjust(a, c.Key, -1)
						}
					}
				} else {
					if was {
						for v := range old.All() {
							adjust(a, v, -1)
						}
					}
					if now {
						for v := range cur.All() {
							adjust(b, v, 1)
						}
					}
				}
			}
			var d reco.MapDelta[G, reco.MultisetSnapshot[V]]
			for g, counts := range net {
				old, _ := out.Get(g)
				next, err := old.WithDelta(reco.MultisetDelta[V]{Adjust: counts})
				if err != nil {
					return reco.Result[reco.MapSnapshot[G, reco.MultisetSnapshot[V]]]{Err: err}
				}
				if next.Len() == 0 {
					d.Remove = append(d.Remove, g)
				} else {
					d.Put = append(d.Put, reco.MapEntry[G, reco.MultisetSnapshot[V]]{Key: g, Value: next})
				}
			}
			out = out.WithDelta(d)
			previousMembers, previousGroups = sets, gs
			return reco.OK(out)
		}
	})
}
