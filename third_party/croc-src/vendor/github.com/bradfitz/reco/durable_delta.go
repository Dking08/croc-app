package reco

// Preserve the transaction's original delta base across multiple durable
// publications. Unrelated whole-value replacements retain their usual fallback
// reconciliation behavior. Never append into a published snapshot's slice.
func (s MapSnapshot[K, V]) recoAccumulate(original, previous any) any {
	base, _ := original.(MapSnapshot[K, V])
	prev, _ := previous.(MapSnapshot[K, V])
	if s.items == prev.items {
		return prev
	}
	if prev.items == base.items {
		return s
	}
	if s.hasDelta && s.base == prev.items && prev.hasDelta && prev.base == base.items {
		changes := make([]MapChange[K, V], 0, len(prev.changes)+len(s.changes))
		changes = append(changes, prev.changes...)
		s.changes = append(changes, s.changes...)
		s.base = base.items
	}
	return s
}

func (s SetSnapshot[K]) recoAccumulate(original, previous any) any {
	base, _ := original.(SetSnapshot[K])
	prev, _ := previous.(SetSnapshot[K])
	if s.items == prev.items {
		return prev
	}
	if prev.items == base.items {
		return s
	}
	if s.hasDelta && s.base == prev.items && prev.hasDelta && prev.base == base.items {
		changes := make([]SetChange[K], 0, len(prev.changes)+len(s.changes))
		changes = append(changes, prev.changes...)
		s.changes = append(changes, s.changes...)
		s.base = base.items
	}
	return s
}

func (s StructSnapshot[T]) recoAccumulate(original, previous any) any {
	base, _ := original.(StructSnapshot[T])
	prev, _ := previous.(StructSnapshot[T])
	combined, err := prev.ChangesSince(base).Then(s.ChangesSince(prev))
	if err != nil {
		panic(err)
	}
	return combined.After()
}
