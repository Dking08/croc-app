package netcheck

import (
	"testing"

	"tailscale.com/tailcfg"
)

func TestMakeProbePlanNilState(t *testing.T) {
	dm := &tailcfg.DERPMap{
		Regions: map[int]*tailcfg.DERPRegion{
			1: {
				RegionID:   1,
				RegionCode: "test",
				Nodes: []*tailcfg.DERPNode{
					{
						Name:     "1a",
						RegionID: 1,
						HostName: "derp.example.com",
						IPv4:     "192.0.2.1",
					},
				},
			},
		},
	}

	// Must not panic when ifState is nil
	planInitial := makeProbePlanInitial(dm, nil)
	if len(planInitial) == 0 {
		t.Errorf("makeProbePlanInitial with nil ifState returned empty plan")
	}

	plan := makeProbePlan(dm, nil, nil, 0)
	if len(plan) == 0 {
		t.Errorf("makeProbePlan with nil ifState returned empty plan")
	}
}
