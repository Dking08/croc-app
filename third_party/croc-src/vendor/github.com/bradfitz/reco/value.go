package reco

// ValueEqualer optionally replaces reflect.DeepEqual for a custom value type.
// RecoValueEqual must be a pure equivalence test, handle a different concrete
// type by returning false, and compare semantic values rather than versions or
// delta metadata. Immutable values can compare persistent-root identities.
// Implement this on the published value type (not only on its pointer type if
// the graph stores values). Scalar values without this hook use reflect.DeepEqual;
// map entries use Go equality when comparable, otherwise reflect.DeepEqual.
type ValueEqualer interface {
	RecoValueEqual(other any) bool
}

// VersionedValue optionally stamps an observed graph version into a custom
// value. RecoWithVersion must return the SAME concrete type and must not mutate
// the receiver or previously published snapshots. This hook is independent of
// ValueEqualer; most custom values need neither hook. Snapshot.Version remains
// available even when the value does not implement VersionedValue.
type VersionedValue interface {
	RecoWithVersion(Version) any
}
