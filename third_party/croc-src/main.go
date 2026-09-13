package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/schollz/croc/v11/src/cli"
	"github.com/schollz/croc/v11/src/utils"
)

var runCLIContext = cli.RunContext

func main() {
	// "github.com/pkg/profile"
	// go func() {
	// 	for {
	// 		f, err := os.Create("croc.pprof")
	// 		if err != nil {
	// 			panic(err)
	// 		}
	// 		runtime.GC() // get up-to-date statistics
	// 		if err := pprof.WriteHeapProfile(f); err != nil {
	// 			panic(err)
	// 		}
	// 		f.Close()
	// 		time.Sleep(3 * time.Second)
	// 		fmt.Println("wrote profile")
	// 	}
	// }()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	errCh := make(chan error, 1)
	go func() {
		errCh <- runCLIContext(ctx)
	}()

	var err error
	select {
	case err = <-errCh:
	case <-ctx.Done():
		// Stop intercepting signals after the first cancellation. This lets a
		// second Ctrl-C use the operating system's default immediate behavior
		// while the first still gets a bounded graceful-shutdown window.
		stop()
		// Commands get a chance to close connections and remove temporary files.
		// The longer backstop is retained for croc ssh, which may need to restore
		// terminal state, notify guests, and stop its child process.
		select {
		case err = <-errCh:
		case <-time.After(5 * time.Second):
		}
	}
	utils.RemoveMarkedFiles()
	if err != nil && !errors.Is(err, context.Canceled) {
		fmt.Println(err)
		os.Exit(1)
	}
}
