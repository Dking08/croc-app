package recotestcontrol

import (
	"net/netip"
	"slices"

	"github.com/bradfitz/reco"
	"tailscale.com/net/tsaddr"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

// A policy is a whole-value wire field. Keep its identity stable on endpoint,
// disco key, hostinfo, or signature changes; none affects the packet filter.
type packetPolicy struct{ rules []tailcfg.FilterRule }

func (p *packetPolicy) RecoValueEqual(other any) bool { return p == other }
func (p *packetPolicy) cloneRules() []tailcfg.FilterRule {
	rules := slices.Clone(p.rules)
	for i := range rules {
		r := &rules[i]
		r.SrcIPs = slices.Clone(r.SrcIPs)
		r.SrcBits = slices.Clone(r.SrcBits)
		r.IPProto = slices.Clone(r.IPProto)
		r.DstPorts = slices.Clone(r.DstPorts)
		for j := range r.DstPorts {
			if b := r.DstPorts[j].Bits; b != nil {
				r.DstPorts[j].Bits = new(*b)
			}
		}
		r.CapGrant = slices.Clone(r.CapGrant)
		for j := range r.CapGrant {
			g := &r.CapGrant[j]
			g.Dsts = slices.Clone(g.Dsts)
			g.Caps = slices.Clone(g.Caps)
			g.CapMap = cloneMap(g.CapMap, slices.Clone[[]tailcfg.RawMessage])
		}
	}
	return rules
}

var policyNode = reco.Operator("control.packet-policy", []reco.Dependency{nodeData, configNode}, func() reco.Compute[*packetPolicy] {
	var previous reco.MapSnapshot[key.NodePublic, *tailcfg.Node]
	var config *renderConfig
	var policy *packetPolicy
	return func(e reco.Eval) reco.Result[*packetPolicy] {
		current := reco.Input(e, nodeData).Value()
		c := reco.Input(e, configNode).Value()
		rebuild := policy == nil || config != c
		if !rebuild && len(c.nodeUnsignedPeerAPIOnly) != 0 {
			for change := range current.ChangesSince(previous) {
				if c.nodeUnsignedPeerAPIOnly[change.Key] {
					continue
				}
				if !change.BeforeValid || !change.AfterValid || !slices.Equal(change.Before.Addresses, change.After.Addresses) {
					rebuild = true
					break
				}
			}
		}
		previous, config = current, c
		if !rebuild {
			return reco.OK(policy)
		}
		allow := []string{"*"}
		if len(c.nodeUnsignedPeerAPIOnly) != 0 {
			allow = nil
			for k, n := range current.All() {
				if c.nodeUnsignedPeerAPIOnly[k] {
					continue
				}
				for _, a := range n.Addresses {
					allow = append(allow, a.String())
				}
			}
			slices.Sort(allow)
		}
		rules := packetFilterWithIngress(c.PeerRelayGrants, allow)
		if c.globalAppCaps != nil {
			rules = append(rules, tailcfg.FilterRule{
				SrcIPs:   []string{"*"},
				CapGrant: []tailcfg.CapGrant{{Dsts: []netip.Prefix{tsaddr.AllIPv4(), tsaddr.AllIPv6()}, CapMap: c.globalAppCaps}},
			})
		}
		policy = &packetPolicy{rules: rules}
		return reco.OK(policy)
	}
})
