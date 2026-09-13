package main

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

const interruptHelperEnvironment = "CROC_INTERRUPT_HELPER"
const secondInterruptHelperEnvironment = "CROC_SECOND_INTERRUPT_HELPER"

func TestWaitingSendExitsPromptlyOnInterrupt(t *testing.T) {
	if os.Getenv(interruptHelperEnvironment) == "1" {
		basePort := 40000 + os.Getpid()%10000
		os.Args = []string{
			"croc", "--local", "--ignore-stdin", "--disable-clipboard",
			"send", "--port", strconv.Itoa(basePort), "--transfers", "1", "--text", "interrupt-test",
		}
		main()
		return
	}
	if runtime.GOOS == "windows" {
		t.Skip("os.Interrupt cannot be sent to a child process on Windows")
	}

	command := exec.Command(os.Args[0], "-test.run=^TestWaitingSendExitsPromptlyOnInterrupt$")
	command.Dir = t.TempDir()
	command.Env = append(os.Environ(), interruptHelperEnvironment+"=1", "CROC_CONFIG_DIR="+t.TempDir())
	command.Stdout = io.Discard
	stderr, err := command.StderrPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = command.Start(); err != nil {
		t.Fatal(err)
	}
	defer command.Process.Kill()

	ready := make(chan struct{})
	readDone := make(chan string, 1)
	go func() {
		var output bytes.Buffer
		scanner := bufio.NewScanner(stderr)
		var readyOnce sync.Once
		for scanner.Scan() {
			line := scanner.Text()
			output.WriteString(line)
			output.WriteByte('\n')
			if strings.Contains(line, "On the other computer, run:") {
				readyOnce.Do(func() { close(ready) })
			}
		}
		readDone <- output.String()
	}()

	select {
	case <-ready:
	case <-time.After(5 * time.Second):
		_ = command.Process.Kill()
		t.Fatalf("helper send did not become ready; stderr: %s", <-readDone)
	}
	started := time.Now()
	if err = command.Process.Signal(os.Interrupt); err != nil {
		t.Fatal(err)
	}
	waitDone := make(chan error, 1)
	go func() { waitDone <- command.Wait() }()
	deadline := 500 * time.Millisecond
	if raceDetectorEnabled {
		deadline = 2 * time.Second
	}
	select {
	case err = <-waitDone:
		elapsed := time.Since(started)
		output := <-readDone
		if err != nil {
			t.Fatalf("interrupted send exited with %v; stderr: %s", err, output)
		}
		if elapsed >= deadline {
			t.Fatalf("interrupted send took %s to exit; stderr: %s", elapsed, output)
		}
		if strings.Contains(strings.ToLower(output), "context canceled") {
			t.Fatalf("interrupted send printed a cancellation error: %s", output)
		}
	case <-time.After(deadline):
		_ = command.Process.Kill()
		t.Fatalf("interrupted send did not exit within %s; stderr: %s", deadline, <-readDone)
	}
}

func TestSecondInterruptForcesImmediateExit(t *testing.T) {
	if os.Getenv(secondInterruptHelperEnvironment) == "1" {
		runCLIContext = func(ctx context.Context) error {
			fmt.Fprintln(os.Stderr, "second-interrupt-ready")
			<-ctx.Done()
			fmt.Fprintln(os.Stderr, "first-interrupt-observed")
			select {}
		}
		main()
		return
	}
	if runtime.GOOS == "windows" {
		t.Skip("os.Interrupt cannot be sent to a child process on Windows")
	}

	command := exec.Command(os.Args[0], "-test.run=^TestSecondInterruptForcesImmediateExit$")
	command.Env = append(os.Environ(), secondInterruptHelperEnvironment+"=1")
	stderr, err := command.StderrPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = command.Start(); err != nil {
		t.Fatal(err)
	}
	defer command.Process.Kill()

	ready := make(chan struct{})
	firstObserved := make(chan struct{})
	go func() {
		scanner := bufio.NewScanner(stderr)
		for scanner.Scan() {
			switch scanner.Text() {
			case "second-interrupt-ready":
				close(ready)
			case "first-interrupt-observed":
				close(firstObserved)
			}
		}
	}()
	select {
	case <-ready:
	case <-time.After(2 * time.Second):
		t.Fatal("second-interrupt helper did not become ready")
	}
	if err = command.Process.Signal(os.Interrupt); err != nil {
		t.Fatal(err)
	}
	select {
	case <-firstObserved:
	case <-time.After(2 * time.Second):
		t.Fatal("helper did not observe the first interrupt")
	}
	// The command goroutine observes cancellation just before main restores
	// default signal handling, so allow that scheduling handoff to complete.
	time.Sleep(25 * time.Millisecond)
	started := time.Now()
	if err = command.Process.Signal(os.Interrupt); err != nil {
		t.Fatal(err)
	}
	waitDone := make(chan error, 1)
	go func() { waitDone <- command.Wait() }()
	deadline := 500 * time.Millisecond
	if raceDetectorEnabled {
		deadline = 2 * time.Second
	}
	select {
	case err = <-waitDone:
		if err == nil {
			t.Fatal("second interrupt unexpectedly allowed graceful completion")
		}
		if elapsed := time.Since(started); elapsed >= deadline {
			t.Fatalf("second interrupt took %s to force exit", elapsed)
		}
	case <-time.After(deadline):
		_ = command.Process.Kill()
		t.Fatalf("second interrupt did not force exit within %s", deadline)
	}
}
