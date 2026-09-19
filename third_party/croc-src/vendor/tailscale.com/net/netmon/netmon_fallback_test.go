package netmon

import (
	"errors"
	"os"
	"testing"
)

func TestIsPermissionError(t *testing.T) {
	tests := []struct {
		err  error
		want bool
	}{
		{nil, false},
		{os.ErrPermission, true},
		{errors.New("route ip+net: netlinkrib: permission denied"), true},
		{errors.New("operation not permitted"), true},
		{errors.New("random network timeout"), false},
	}

	for _, tt := range tests {
		got := isPermissionError(tt.err)
		if got != tt.want {
			t.Errorf("isPermissionError(%v) = %v, want %v", tt.err, got, tt.want)
		}
	}
}

func TestNewStaticHasValidState(t *testing.T) {
	m := NewStatic()
	if m == nil {
		t.Fatal("NewStatic returned nil")
	}
	st := m.InterfaceState()
	if st == nil {
		t.Fatal("m.InterfaceState() returned nil")
	}
	if !st.AnyInterfaceUp() {
		t.Errorf("st.AnyInterfaceUp() = false, want true")
	}
	if !st.HaveV4 {
		t.Errorf("st.HaveV4 = false, want true")
	}
	if st.InterfaceIPs == nil {
		t.Errorf("st.InterfaceIPs is nil")
	}
	if st.Interface == nil {
		t.Errorf("st.Interface is nil")
	}
}

func TestInterfaceStateNilSafety(t *testing.T) {
	m := &Monitor{}
	st := m.InterfaceState()
	if st == nil {
		t.Fatal("InterfaceState() returned nil on zero monitor")
	}
	if !st.AnyInterfaceUp() {
		t.Errorf("st.AnyInterfaceUp() = false, want true")
	}
}
