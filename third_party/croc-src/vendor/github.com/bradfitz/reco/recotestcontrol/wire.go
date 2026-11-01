package recotestcontrol

import (
	"net/netip"
	"time"

	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/types/tkatype"
)

// tailcfg's client-facing PeerChange uses omitempty on collections. The
// server must instead distinguish nil (no change) from empty (clear). Likewise
// omitzero on pointers to keys/timestamps calls their IsZero methods, dropping
// explicit zero values. Use omitempty for those pointers: only nil is omitted.
// Shadow those fields, retaining the rest of the public schema and its tags.
type peerChangeWire struct {
	*tailcfg.PeerChange
	Endpoints    []netip.AddrPort           `json:",omitzero"`
	CapMap       tailcfg.NodeCapMap         `json:",omitzero"`
	KeySignature tkatype.MarshaledSignature `json:",omitzero"`
	Key          *key.NodePublic            `json:",omitempty"`
	DiscoKey     *key.DiscoPublic           `json:",omitempty"`
	KeyExpiry    *time.Time                 `json:",omitempty"`
	LastSeen     *time.Time                 `json:",omitempty"`
}

type mapResponseWire struct {
	*tailcfg.MapResponse
	PeersChangedPatch []*peerChangeWire `json:",omitempty"`
}

func mapResponseForWire(r *tailcfg.MapResponse) any {
	if r == nil || len(r.PeersChangedPatch) == 0 {
		return r
	}
	w := mapResponseWire{MapResponse: r, PeersChangedPatch: make([]*peerChangeWire, len(r.PeersChangedPatch))}
	for i, p := range r.PeersChangedPatch {
		if p != nil {
			w.PeersChangedPatch[i] = &peerChangeWire{
				PeerChange: p, Endpoints: p.Endpoints, CapMap: p.CapMap, KeySignature: p.KeySignature,
				Key: p.Key, DiscoKey: p.DiscoKey, KeyExpiry: p.KeyExpiry, LastSeen: p.LastSeen,
			}
		}
	}
	return w
}
