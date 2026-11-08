package recotestcontrol

import (
	"bytes"
	"net/netip"
	"reflect"
	"slices"
	"time"

	"tailscale.com/tailcfg"
)

// peerPatch describes a change between two already-personalized peer records.
// It returns (nil, true) for a wire-equivalent no-op, and ok=false when any
// changed field cannot be patched at the recipient's capability version.
// Only touched peers are compared; there is no scan of the tailnet or cache
// of per-stream deep snapshots. Work includes the touched node's own fields.
func peerPatch(before, after *tailcfg.Node, version tailcfg.CapabilityVersion) (_ *tailcfg.PeerChange, ok bool) {
	if before.ID != after.ID {
		return nil, false
	}
	var patch *tailcfg.PeerChange
	p := func() *tailcfg.PeerChange {
		if patch == nil {
			patch = &tailcfg.PeerChange{NodeID: after.ID}
		}
		return patch
	}
	if before.HomeDERP != after.HomeDERP {
		if version < 33 || after.HomeDERP == 0 {
			return nil, false
		}
		p().DERPRegion = after.HomeDERP
	}
	if !slices.Equal(before.Endpoints, after.Endpoints) {
		if version < 33 {
			return nil, false
		}
		p().Endpoints = append([]netip.AddrPort{}, after.Endpoints...)
	}
	if before.Key != after.Key {
		if version < 36 {
			return nil, false
		}
		p().Key = new(after.Key)
	}
	if before.DiscoKey != after.DiscoKey {
		if version < 36 {
			return nil, false
		}
		p().DiscoKey = new(after.DiscoKey)
	}
	if !before.KeyExpiry.Equal(after.KeyExpiry) {
		if version < 36 {
			return nil, false
		}
		p().KeyExpiry = new(after.KeyExpiry)
	}
	if !equalPtr(before.Online, after.Online) {
		if version < 36 || after.Online == nil {
			return nil, false
		}
		p().Online = new(*after.Online)
	}
	if !equalTimePtr(before.LastSeen, after.LastSeen) {
		if version < 36 || after.LastSeen == nil {
			return nil, false
		}
		p().LastSeen = new(*after.LastSeen)
	}
	if !bytes.Equal(before.KeySignature, after.KeySignature) {
		if version < 40 {
			return nil, false
		}
		p().KeySignature = append([]byte{}, after.KeySignature...)
	}
	if before.Cap != after.Cap {
		if version < 54 || after.Cap == 0 {
			return nil, false
		}
		p().Cap = after.Cap
	}
	if !before.CapMap.Equal(after.CapMap) {
		if version < 74 {
			return nil, false
		}
		m := cloneMap(after.CapMap, slices.Clone[[]tailcfg.RawMessage])
		if m == nil {
			m = make(tailcfg.NodeCapMap)
		}
		p().CapMap = m
	}

	// Mask the fields handled above in a shallow copy, then reject any other
	// difference. This fails closed for new tailcfg.Node fields, unlike a
	// hand-maintained list of fields that must trigger full replacements.
	rest := *before
	rest.HomeDERP = after.HomeDERP
	rest.Endpoints = after.Endpoints
	rest.Key = after.Key
	rest.DiscoKey = after.DiscoKey
	rest.KeyExpiry = after.KeyExpiry
	rest.Online = after.Online
	rest.LastSeen = after.LastSeen
	rest.KeySignature = after.KeySignature
	rest.Cap = after.Cap
	rest.CapMap = after.CapMap
	if !reflect.DeepEqual(&rest, after) {
		return nil, false
	}
	return patch, true
}

func equalPtr[T comparable](a, b *T) bool {
	return a == b || a != nil && b != nil && *a == *b
}

func equalTimePtr(a, b *time.Time) bool {
	return a == b || a != nil && b != nil && a.Equal(*b)
}

// The current controlclient patchifies PeersChanged before applying it. For
// these unrepresentable clears that conversion is lossy, even if we send a
// complete replacement. A full Peers response bypasses that conversion. This
// exceptional refresh is not needed for endpoint/capability/signature clears.
func needsPeerRefresh(before, after *tailcfg.Node) bool {
	return before.HomeDERP != 0 && after.HomeDERP == 0 ||
		before.Cap != 0 && after.Cap == 0 ||
		before.Online != nil && after.Online == nil ||
		before.LastSeen != nil && after.LastSeen == nil
}
