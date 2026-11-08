package recotestcontrol

import (
	"context"
	"math/rand/v2"
	"net/http"
	"sync"
	"time"

	"github.com/bradfitz/reco"
	"tailscale.com/tailcfg"
	"tailscale.com/util/must"
)

// mapWatch is a bounded, coalescing mailbox, not a node subscription registry.
// Reco owns dependency tracking and delivers settled changes. HTTP sessions
// retain only the unsent net batch and one wake-up signal.
type mapWatch struct {
	mu      sync.Mutex
	pending reco.StructChanges[mapMeta]
	wake    chan struct{}
	ctx     context.Context
	done    <-chan struct{}
	cancel  context.CancelFunc
}

func newMapWatch() *mapWatch {
	ctx, cancel := context.WithCancel(context.Background())
	return &mapWatch{wake: make(chan struct{}, 1), ctx: ctx, done: ctx.Done(), cancel: cancel}
}
func (m *mapWatch) signal() {
	select {
	case m.wake <- struct{}{}:
	default:
	}
}
func (m *mapWatch) close() { m.cancel() }
func (m *mapWatch) onChange(ev reco.StructEvent[mapMeta]) {
	m.mu.Lock()
	defer m.mu.Unlock()
	select {
	case <-m.done:
		return
	default:
	}
	m.pending = must.Get(m.pending.Then(ev.Changes))
	// Bound distinct pending changes (not bytes). A disconnected client can
	// reconnect for a fresh snapshot. Repeated edits of one key coalesce.
	if m.pending.ChangeCount() > 10000 {
		m.pending = reco.StructChanges[mapMeta]{}
		m.close()
	}
	m.signal()
}
func (m *mapWatch) take() reco.StructChanges[mapMeta] {
	m.mu.Lock()
	defer m.mu.Unlock()
	c := m.pending
	m.pending = reco.StructChanges[mapMeta]{}
	return c
}

func (s *Server) serveMapStream(w http.ResponseWriter, r *http.Request, req *tailcfg.MapRequest, nodeID tailcfg.NodeID) {
	watch := newMapWatch()
	s.mu.Lock()
	s.ensureGraphLocked()
	initial, sub, err := reco.SubscribeStruct(s.graph, metaNode, reco.SubscribeOptions{}, watch.onChange)
	if err != nil {
		s.mu.Unlock()
		watch.close()
		http.Error(w, err.Error(), 500)
		return
	}
	if breakSameNodeMapResponseStreams(req) {
		if old := s.sessions[nodeID]; old != nil {
			old.close()
		}
		if s.sessions == nil {
			s.sessions = make(map[tailcfg.NodeID]*mapWatch)
		}
		s.sessions[nodeID] = watch
	}
	s.condLocked().Broadcast()
	s.mu.Unlock()
	// Release demand even if encoding or a network write is blocked. A mailbox
	// overflow can cancel under the graph lock, so unsubscribe asynchronously.
	released := make(chan struct{})
	context.AfterFunc(watch.ctx, func() {
		sub.Unsubscribe()
		watch.take()
		close(released)
	})
	stopCancel := context.AfterFunc(r.Context(), watch.close)
	defer func() {
		stopCancel()
		watch.close()
		<-released
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.sessions[nodeID] == watch {
			delete(s.sessions, nodeID)
		}
	}()

	streaming := req.Stream && !req.ReadOnly
	compress := req.Compress != ""
	timer := time.NewTicker(50*time.Second + rand.N(8*time.Second))
	defer timer.Stop()
	first := true
	w.WriteHeader(200)
	for {
		select {
		case <-r.Context().Done():
			return
		case <-watch.done:
			return
		default:
		}
		if streaming {
			if raw, ok := s.takeRawMapMessage(req.NodeKey); ok {
				if s.sendMapMsg(w, compress, raw) != nil {
					return
				}
				continue
			}
		}
		var res *tailcfg.MapResponse
		if first {
			res = fullResponse(req, initial.Value().Value())
			initial = reco.Snapshot[reco.StructSnapshot[mapMeta]]{}
			if res == nil {
				return
			}
		} else if changes := watch.take(); changes.Len() != 0 {
			res = deltaResponse(req, changes, s.ModifyFirstMapResponse == nil)
			if res == nil {
				return
			}
		}
		if s.canGenerateAutomaticMapResponseFor(req.NodeKey) && res != nil && (first || !emptyDelta(res)) {
			if first && s.ModifyFirstMapResponse != nil {
				s.ModifyFirstMapResponse(res, req)
			}
			if s.sendMapMsg(w, compress, res) != nil {
				return
			}
		}
		first = false
		if !streaming {
			return
		}
		// Drain changes that arrived during encoding, without a lost wake-up.
		select {
		case <-r.Context().Done():
			return
		case <-watch.done:
			return
		case <-watch.wake:
		case <-timer.C:
			if s.sendMapMsg(w, compress, keepAliveMsg) != nil {
				return
			}
		}
	}
}
