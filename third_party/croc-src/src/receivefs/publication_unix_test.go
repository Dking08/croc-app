//go:build !windows

package receivefs

import (
	"os"
	"syscall"
	"testing"
)

func TestPublicationProbeTreatsLinkPolicyErrorsAsUnsupported(t *testing.T) {
	root, err := OpenRoot(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer root.Close()
	for _, cause := range []syscall.Errno{syscall.EACCES, syscall.EPERM} {
		t.Run(cause.Error(), func(t *testing.T) {
			supported, err := root.probePublishNew("nested", func(string, string) error { return &os.LinkError{Op: "link", Err: cause} })
			if supported || err != nil {
				t.Fatalf("probe: %v, %v", supported, err)
			}
		})
	}
}
