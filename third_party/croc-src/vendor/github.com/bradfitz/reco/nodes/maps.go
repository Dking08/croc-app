package nodes

import "github.com/bradfitz/reco"

// MapKeys contains every key in input. Value-only changes do not change the
// output or notify its subscribers. V need not be comparable.
func MapKeys[K comparable, V any](className reco.NodeClassName, input reco.Node[reco.MapSnapshot[K, V]]) reco.Node[reco.SetSnapshot[K]] {
	return reco.Operator(className, []reco.Dependency{input}, func() reco.Compute[reco.SetSnapshot[K]] {
		var previous reco.MapSnapshot[K, V]
		var out reco.SetSnapshot[K]
		return func(eval reco.Eval) reco.Result[reco.SetSnapshot[K]] {
			current := reco.Input(eval, input).Value()
			var delta reco.SetDelta[K]
			for c := range current.ChangesSince(previous) {
				if c.BeforeValid == c.AfterValid {
					continue
				}
				if c.AfterValid {
					delta.Add = append(delta.Add, c.Key)
				} else {
					delta.Remove = append(delta.Remove, c.Key)
				}
			}
			previous = current
			out = out.WithDelta(delta)
			return reco.OK(out)
		}
	})
}

// MapValues contains the distinct values in input. V must be comparable; values
// use Go equality, like set keys. Nil and zero values are members, not deletions.
// A value remains present until its last contributing map entry is removed or
// changed. Value swaps and transfers between keys in one transaction are atomic.
// Values must be reflexive (no NaNs); interface values must be dynamically
// comparable. Any custom ValueEqualer must agree with their Go equality.
func MapValues[K, V comparable](className reco.NodeClassName, input reco.Node[reco.MapSnapshot[K, V]]) reco.Node[reco.SetSnapshot[V]] {
	return reco.Operator(className, []reco.Dependency{input}, func() reco.Compute[reco.SetSnapshot[V]] {
		var previous reco.MapSnapshot[K, V]
		var out countedSet[V]
		return func(eval reco.Eval) reco.Result[reco.SetSnapshot[V]] {
			current := reco.Input(eval, input).Value()
			net := make(map[V]int)
			for c := range current.ChangesSince(previous) {
				if c.BeforeValid {
					net[c.Before]--
				}
				if c.AfterValid {
					net[c.After]++
				}
			}
			previous = current
			return reco.OK(out.apply(net, func(n int) bool { return n > 0 }))
		}
	})
}
