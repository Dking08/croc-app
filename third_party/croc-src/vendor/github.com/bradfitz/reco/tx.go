package reco

import "fmt"

// Tx is a serialized per-graph transaction.
type Tx struct {
	g       *Graph
	writes  map[*nodeDef]any
	err     error
	configs map[*nodeDef]*nodeDef
	holds   []*nodeDef

	// Multiset mutation buffers are transaction-owned. Keep them separate from
	// arbitrary Set values so batching never appends into a caller's snapshot.
	multisetWrites map[*nodeDef]any
}

// Set assigns a data node within a transaction. For externally owned leaves
// bound with BindDurable, use UpdateDurable; Set makes the transaction fail.
func Set[T any](tx *Tx, node Node[T], value T) {
	tx.mustData(node.def)
	tx.writes[node.def] = value
}

func (tx *Tx) mustData(def *nodeDef) {
	tx.checkData(def)
	if tx.g.loaders[def] != nil {
		tx.err = fmt.Errorf("reco: use UpdateDurable to publish persisted changes to %s", def.className)
	}
}

func (tx *Tx) checkData(def *nodeDef) {
	if def == nil {
		panic("reco: zero node handle")
	}
	if def.kind != nodeData {
		panic(fmt.Sprintf("reco: node %s is not a data node", def.className))
	}
	if _, ok := tx.g.nodes[def]; !ok {
		panic(fmt.Sprintf("reco: node %s is not registered", def.className))
	}
}

func (tx *Tx) current(def *nodeDef) nodeValue {
	if v, ok := tx.writes[def]; ok {
		return nodeValue{value: v, valid: true}
	}
	return tx.g.nodes[def]
}
