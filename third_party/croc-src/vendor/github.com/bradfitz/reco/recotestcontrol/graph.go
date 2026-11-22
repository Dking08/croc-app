package recotestcontrol

import (
	"maps"
	"net/netip"
	"slices"

	"github.com/bradfitz/reco"
	"github.com/bradfitz/reco/nodes"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/types/opt"
	"tailscale.com/util/must"
)

// One fixed DAG per server. Stream lifetimes own subscriptions, not node
// definitions. The record preserves collection deltas all the way to the wire
// encoder; no deep snapshot comparisons or per-stream peer cache is needed.
type mapMeta struct {
	Nodes    reco.MapSnapshot[key.NodePublic, *tailcfg.Node]
	Profiles reco.MapSnapshot[tailcfg.UserID, tailcfg.UserProfile]
	Config   *renderConfig
	Policy   *packetPolicy
}

var (
	nodeData     = reco.MapData[key.NodePublic, *tailcfg.Node]("control.nodes")
	profilesNode = reco.MapData[tailcfg.UserID, tailcfg.UserProfile]("control.profiles")
	configNode   = reco.Data[*renderConfig]("control.config")
	metaNode     = nodes.Struct[mapMeta]("control.map-meta", struct {
		Nodes    reco.Node[reco.MapSnapshot[key.NodePublic, *tailcfg.Node]]
		Profiles reco.Node[reco.MapSnapshot[tailcfg.UserID, tailcfg.UserProfile]]
		Config   reco.Node[*renderConfig]
		Policy   reco.Node[*packetPolicy]
	}{nodeData, profilesNode, configNode, policyNode})
	nodesField    = reco.Field[mapMeta, reco.MapSnapshot[key.NodePublic, *tailcfg.Node]]("Nodes")
	profilesField = reco.Field[mapMeta, reco.MapSnapshot[tailcfg.UserID, tailcfg.UserProfile]]("Profiles")
	configField   = reco.Field[mapMeta, *renderConfig]("Config")
	policyField   = reco.Field[mapMeta, *packetPolicy]("Policy")
)

// renderConfig is immutable once published. Global policy changes may require
// a full refresh; ordinary node mutations never clone or scan these tables.
type renderConfig struct {
	DERPMap                                *tailcfg.DERPMap
	DNSConfig                              *tailcfg.DNSConfig
	SSHPolicy                              *tailcfg.SSHPolicy
	MagicDNSDomain                         string
	CollectServices                        opt.Bool
	PeerRelayGrants, AllOnline, allExpired bool
	nodeSubnetRoutes                       map[key.NodePublic][]netip.Prefix
	nodeUnsignedPeerAPIOnly                map[key.NodePublic]bool
	nodeCapMaps                            map[key.NodePublic]tailcfg.NodeCapMap
	peerIsJailed                           map[key.NodePublic]map[key.NodePublic]bool
	masquerades                            map[key.NodePublic]map[key.NodePublic]netip.Addr
	globalAppCaps                          tailcfg.PeerCapMap
	tkaInfo                                *tailcfg.TKAInfo
}

func (c *renderConfig) RecoValueEqual(other any) bool { return c == other }

// All mutation helpers below require s.mu. Graph callbacks must never take
// s.mu: they only accumulate immutable batches in a stream-local mailbox.
func (s *Server) ensureGraphLocked() {
	if s.graph != nil {
		return
	}
	s.graph = reco.NewGraphWithOptions(reco.GraphOptions{DemandDriven: true})
	must.Do(s.graph.Register(metaNode))
	must.Do(s.graph.Update(func(tx *reco.Tx) error {
		reco.Set(tx, nodeData, s.nodes)
		reco.Set(tx, profilesNode, reco.MapSnapshot[tailcfg.UserID, tailcfg.UserProfile]{})
		reco.Set(tx, configNode, s.renderConfigLocked())
		return nil
	}))
}

func (s *Server) putNodeLocked(n *tailcfg.Node) {
	if n.Key.IsZero() {
		panic("zero node key")
	}
	s.ensureGraphLocked()
	s.nodes = s.nodes.WithDelta(reco.MapDelta[key.NodePublic, *tailcfg.Node]{
		Put: []reco.MapEntry[key.NodePublic, *tailcfg.Node]{{Key: n.Key, Value: n.Clone()}},
	})
	must.Do(s.graph.Update(func(tx *reco.Tx) error { reco.Set(tx, nodeData, s.nodes); return nil }))
}

func (s *Server) deleteNodeLocked(k key.NodePublic) {
	s.ensureGraphLocked()
	s.nodes = s.nodes.WithDelta(reco.MapDelta[key.NodePublic, *tailcfg.Node]{Remove: []key.NodePublic{k}})
	must.Do(s.graph.Update(func(tx *reco.Tx) error { reco.Set(tx, nodeData, s.nodes); return nil }))
}

// retireNodeKeyLocked publishes retirement and the replacement together. The
// wire identity is NodeID, so removing the old public key alone would wrongly
// remove the newly rotated peer from an already-connected client.
func (s *Server) retireNodeKeyLocked(old, replacement key.NodePublic) {
	n := s.nodeLocked(replacement)
	s.nodes = s.nodes.WithDelta(reco.MapDelta[key.NodePublic, *tailcfg.Node]{
		Remove: []key.NodePublic{old},
		Put:    []reco.MapEntry[key.NodePublic, *tailcfg.Node]{{Key: replacement, Value: n}},
	})
	must.Do(s.graph.Update(func(tx *reco.Tx) error { reco.Set(tx, nodeData, s.nodes); return nil }))
}

func (s *Server) publishConfigLocked() {
	s.ensureGraphLocked()
	c := s.renderConfigLocked()
	must.Do(s.graph.Update(func(tx *reco.Tx) error { reco.Set(tx, configNode, c); return nil }))
}

func (s *Server) renderConfigLocked() *renderConfig {
	c := &renderConfig{
		DERPMap: s.DERPMap.Clone(), DNSConfig: s.DNSConfig.Clone(), SSHPolicy: s.SSHPolicy.Clone(),
		MagicDNSDomain: s.MagicDNSDomain, CollectServices: s.CollectServices,
		PeerRelayGrants: s.PeerRelayGrants, AllOnline: s.AllOnline, allExpired: s.allExpired,
		nodeSubnetRoutes:        cloneMap(s.nodeSubnetRoutes, slices.Clone[[]netip.Prefix]),
		nodeUnsignedPeerAPIOnly: maps.Clone(s.nodeUnsignedPeerAPIOnly),
		nodeCapMaps:             cloneMap(s.nodeCapMaps, func(m tailcfg.NodeCapMap) tailcfg.NodeCapMap { return cloneMap(m, slices.Clone[[]tailcfg.RawMessage]) }),
		peerIsJailed:            cloneMap(s.peerIsJailed, maps.Clone[map[key.NodePublic]bool]),
		masquerades:             cloneMap(s.masquerades, maps.Clone[map[key.NodePublic]netip.Addr]),
		globalAppCaps:           cloneMap(s.globalAppCaps, slices.Clone[[]tailcfg.RawMessage]),
	}
	if s.tkaStorage != nil {
		if heads, err := s.tkaStorage.Heads(); err == nil && len(heads) == 1 {
			c.tkaInfo = &tailcfg.TKAInfo{Head: heads[0].Hash().String()}
		}
	}
	return c
}

func cloneMap[K comparable, V any](m map[K]V, clone func(V) V) map[K]V {
	if m == nil {
		return nil
	}
	r := make(map[K]V, len(m))
	for k, v := range m {
		r[k] = clone(v)
	}
	return r
}
