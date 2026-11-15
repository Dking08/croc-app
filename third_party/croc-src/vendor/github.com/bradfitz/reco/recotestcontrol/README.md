# recotestcontrol

Experimental test code, not a production control server or a stable API.

This is a reco-backed replacement for the public Tailscale
[`tstest/integration/testcontrol`](https://github.com/tailscale/tailscale/blob/d229a06f9a4b2340f0749288df0ac4f6de223acd/tstest/integration/testcontrol/testcontrol.go)
server. Protocol, authentication, Noise, Tailnet Lock, and test-hook code was
adapted from that revision, retaining its BSD-3-Clause notices. No private
control-server implementation is used.

This package is self-contained and in-memory: no persistent storage, browser
administration, cross-network sharing, or identity-policy engine. It depends
only on public reco APIs and the public Tailscale module. Clients must use
capability version 109 or later.

## Data flow

Each server has one fixed graph, independent of the number of HTTP streams:

```text
node map ──────────┬───────────────────┐
                   └─ packet policy ──┤
configuration ─────┬──────────┘        ├─ map-meta struct ─ subscriptions
                   └──────────────────┤                     │
user-profile map ─────────────────────┘              MapResponse encoder
```

Nodes and profiles use persistent reco maps. A small application-local
`Operator` recomputes packet policy only when its relevant inputs change.
`nodes.Struct` assembles the settled record. `SubscribeStruct` supplies an
atomic snapshot/watch, and each stream coalesces unsent changes with
`StructChanges.Then`. Encoding and network I/O happen outside graph callbacks.

The first response is full. Subsequent changes to existing peers use
`PeersChangedPatch` when the recipient's capability version supports the changed
fields: endpoints, DERP home, keys, signatures, expiry, online/last-seen status,
capability version, and capability maps. New peers and changes outside that
schema use complete `PeersChanged` entries. Removals use `PeersRemoved`; the
protocol still requires a whole `Node` for self updates.

Patch selection compares only touched peers, after recipient-specific rendering,
using the coalesced before/after values. It costs the size of those peers' own
fields, not the size of the tailnet. Mixed batches use disjoint peer IDs for
patches, replacements, and removals. No-op changes (including A -> B -> A) are
suppressed. No per-stream deep snapshot cache is needed. A lite update only
returns HTTP status; it does not render a full map. The allowlist policy also
stays unchanged on endpoint/disco updates.

The server's small wire adapter preserves explicit empty endpoints, capability
maps, and signatures. It also preserves non-nil pointers to zero keys and
timestamps, which the client-facing schema's `omitzero` tags would otherwise
omit. These operations are tested through JSON and the real control client,
over Noise with compressed map responses.

Session replacement and FIFO ping/raw-response injection remain HTTP concerns,
not graph subscriptions. Disconnecting unregisters the subscription even while
a network writer is blocked. The graph is demand-driven: last unsubscribe
releases unused derived values/caches, and reconnect rebuilds from current
in-memory leaves. No unused computation keeps running after disconnect. Pending
graph changes are bounded to 10,000 distinct changes; exceeding that closes the
stream so a client can reconnect for a fresh snapshot. This is an entry-count
bound, not a byte limit, and the explicit test-injection queue is not bounded.

## Running the upstream tests

The companion Tailscale checkout has a `!experiment.reco` constraint on its
original `testcontrol.go`. Its `reco-experiment.go` uses `experiment.reco` and
aliases `Server`, `AuthPath`, `MasqueradePair`, `AltMapStreamFunc`, and
`MapStreamWriter` to this package, and forwards `RejectRequestForPath`.

Use a local workspace containing both modules (the workspace files are ignored
here). With those build-tag changes in the Tailscale checkout:

```sh
# From this repository; substitute your actual Tailscale checkout path.
go work init . /path/to/tailscale.com
export GOWORK="$PWD/go.work"
cd /path/to/tailscale.com
GOFLAGS=-tags=experiment.reco go test -skip TestStreamingMapReqReadOnlyByVersion \
  ./tstest/integration/testcontrol ./control/tsp ./control/controlclient \
  ./tsnet ./tstest/integration ./feature/taildrop ./cmd/sniproxy \
  ./tsconsensus ./tstest/largetailnet ./tstest/membudget
```

If `go.work` already exists, use `go work use` instead of `go work init`.
`GOFLAGS` also propagates the tag to integration tests' subprocess builds.
The workspace avoids committing local replacement paths to either module.
The `control/tsp` peer-update test accepts `Peers`, `PeersChanged`, and
`PeersChangedPatch`, as permitted by the protocol, so it works with either server.

In this repository, `GOWORK=off go test ./...` verifies the public pinned
dependency independently of the workspace. Use `go test -race ./...` for
concurrency checks and `go test ./recotestcontrol -bench=PeerDelta -run='^$'`
for the incremental path benchmark.

The skipped upstream test intentionally exercises obsolete capability versions
67/68. Version-floor rejection, current-version streams, demand release, blocked
writer cancellation, and peer patches have local regressions in this package.

## Deliberate limitations

- Set public configuration fields before first use; use setters for live edits.
- Configuration setters, including per-node route/capability overrides, currently
  replace one immutable configuration value and trigger full responses. They
  are not yet fine-grained graph joins.
- Clearing DERP home/capability version to zero or online/last-seen status to
  nil requires a full peer refresh. Those transitions cannot be expressed as
  patches, and this client revision incorrectly patchifies the corresponding
  whole-node replacements. A full `Peers` list bypasses that conversion. These
  exceptional refreshes are covered by a real-client regression test.
- Streams with `ModifyFirstMapResponse` opt out of server-side peer patches:
  customized initial peers need not match the graph's before values. Their
  touched peers continue to use whole-node replacements.
- A signed-node address or membership change may rebuild the explicit packet
  filter. Endpoint/disco changes do not. Filter rules are whole-value wire fields.
- The shared record is not a production per-recipient authorization graph.
  Peer personalization happens in the encoder for touched entries.
- There is no distributed graph, durable state, session resumption, asynchronous
  graph scheduler, or production authentication/policy enforcement.
- VM/browser-specific upstream packages can be compile-checked locally; running
  those environments needs their own infrastructure.

The existing reco primitives were sufficient for this version; no new core API
was required. Useful next steps are incremental joins for per-node overrides
and byte-bounded backpressure.
