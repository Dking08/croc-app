package nodes

import "github.com/bradfitz/reco"

// Map projects each input map entry to fn(key, value), preserving its key.
// fn must be pure and depend only on its arguments. It runs once per added or
// changed entry, never on deletions or unchanged entries. Initial input and
// arbitrary replacements may require enumeration. Equal output values suppress
// downstream changes using reco's map-value equality rules.
func Map[K comparable, V, W any](name reco.NodeClassName, input reco.Node[reco.MapSnapshot[K, V]], fn func(K, V) W) reco.Node[reco.MapSnapshot[K, W]] {
	if fn == nil {
		panic("nodes: nil Map function")
	}
	return reco.Operator(name, []reco.Dependency{input}, func() reco.Compute[reco.MapSnapshot[K, W]] {
		var previous reco.MapSnapshot[K, V]
		var out reco.MapSnapshot[K, W]
		return func(e reco.Eval) reco.Result[reco.MapSnapshot[K, W]] {
			dep := reco.Input(e, input)
			if dep.Err() != nil {
				return reco.Result[reco.MapSnapshot[K, W]]{Err: dep.Err()}
			}
			cur := dep.Value()
			var d reco.MapDelta[K, W]
			for c := range cur.ChangesSince(previous) {
				if c.AfterValid {
					d.Put = append(d.Put, reco.MapEntry[K, W]{Key: c.Key, Value: fn(c.Key, c.After)})
				} else {
					d.Remove = append(d.Remove, c.Key)
				}
			}
			out, previous = out.WithDelta(d), cur
			return reco.OK(out)
		}
	})
}

// Index groups input KEYS by group(key, value), producing one set per group.
// Every entry belongs to exactly one group, including a zero-valued group.
// Empty groups are omitted. Value edits within the same group leave the output
// unchanged. group must be pure; it runs for each changed entry's before/after
// value (at most twice per delta key), not for unchanged entries. A move between
// groups updates both sets atomically without copying either whole bucket.
func Index[K comparable, V any, G comparable](name reco.NodeClassName, input reco.Node[reco.MapSnapshot[K, V]], group func(K, V) G) reco.Node[reco.MapSnapshot[G, reco.SetSnapshot[K]]] {
	if group == nil {
		panic("nodes: nil Index function")
	}
	return reco.Operator(name, []reco.Dependency{input}, func() reco.Compute[reco.MapSnapshot[G, reco.SetSnapshot[K]]] {
		var previous reco.MapSnapshot[K, V]
		var out reco.MapSnapshot[G, reco.SetSnapshot[K]]
		return func(e reco.Eval) reco.Result[reco.MapSnapshot[G, reco.SetSnapshot[K]]] {
			dep := reco.Input(e, input)
			if dep.Err() != nil {
				return reco.Result[reco.MapSnapshot[G, reco.SetSnapshot[K]]]{Err: dep.Err()}
			}
			cur := dep.Value()
			d := make(map[G]reco.SetDelta[K])
			for c := range cur.ChangesSince(previous) {
				var a, b G
				if c.BeforeValid {
					a = group(c.Key, c.Before)
				}
				if c.AfterValid {
					b = group(c.Key, c.After)
				}
				if c.BeforeValid && c.AfterValid && a == b {
					continue
				}
				if c.BeforeValid {
					setEdge(d, a, c.Key, false)
				}
				if c.AfterValid {
					setEdge(d, b, c.Key, true)
				}
			}
			out, previous = applySetBuckets(out, d), cur
			return reco.OK(out)
		}
	})
}

// InvertSets reverses a set-valued index: out[v] contains k iff input[k]
// contains v. Empty input sets contribute nothing; empty output buckets are
// omitted. It processes changed outer keys and their nested membership deltas,
// not whole buckets. Nested replacements/skipped delta bases may require
// scanning the affected sets. Transfers and overlapping memberships are atomic.
func InvertSets[K, V comparable](name reco.NodeClassName, input reco.Node[reco.MapSnapshot[K, reco.SetSnapshot[V]]]) reco.Node[reco.MapSnapshot[V, reco.SetSnapshot[K]]] {
	return reco.Operator(name, []reco.Dependency{input}, func() reco.Compute[reco.MapSnapshot[V, reco.SetSnapshot[K]]] {
		var previous reco.MapSnapshot[K, reco.SetSnapshot[V]]
		var out reco.MapSnapshot[V, reco.SetSnapshot[K]]
		return func(e reco.Eval) reco.Result[reco.MapSnapshot[V, reco.SetSnapshot[K]]] {
			dep := reco.Input(e, input)
			if dep.Err() != nil {
				return reco.Result[reco.MapSnapshot[V, reco.SetSnapshot[K]]]{Err: dep.Err()}
			}
			cur := dep.Value()
			d := make(map[V]reco.SetDelta[K])
			for c := range cur.ChangesSince(previous) {
				for edge := range c.After.ChangesSince(c.Before) {
					setEdge(d, edge.Key, c.Key, edge.Present)
				}
			}
			out, previous = applySetBuckets(out, d), cur
			return reco.OK(out)
		}
	})
}

func setEdge[K, V comparable](d map[K]reco.SetDelta[V], k K, v V, present bool) {
	b := d[k]
	if present {
		b.Add = append(b.Add, v)
	} else {
		b.Remove = append(b.Remove, v)
	}
	d[k] = b
}

func applySetBuckets[K, V comparable](m reco.MapSnapshot[K, reco.SetSnapshot[V]], changes map[K]reco.SetDelta[V]) reco.MapSnapshot[K, reco.SetSnapshot[V]] {
	var d reco.MapDelta[K, reco.SetSnapshot[V]]
	for k, change := range changes {
		set, _ := m.Get(k)
		set = set.WithDelta(change)
		if set.Len() == 0 {
			d.Remove = append(d.Remove, k)
		} else {
			d.Put = append(d.Put, reco.MapEntry[K, reco.SetSnapshot[V]]{Key: k, Value: set})
		}
	}
	return m.WithDelta(d)
}
