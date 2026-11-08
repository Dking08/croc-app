[![Go Reference](https://pkg.go.dev/badge/github.com/bradfitz/reco.svg)](https://pkg.go.dev/github.com/bradfitz/reco)

# reco

> **EXPERIMENTAL RESEARCH PROTOTYPE — DO NOT USE.**
> Nothing to see here; move along. This is unfinished research, not a supported
> library. Do not depend on it. APIs and behavior may change or disappear without
> notice. There are no compatibility or correctness guarantees.

Reco (reactive computation) explores typed, incremental dataflow graphs in Go.
Declare mutable inputs and derived values; transactions update inputs, affected
computations run, and subscribers receive changes. Persistent sets, maps, and
typed records let incremental operators process small deltas without copying or
rescanning entire collections. Local evaluation is synchronous; an experimental
WebSocket transport connects explicitly bound graphs across processes.

See [DESIGN.md](DESIGN.md) for details and open questions.
