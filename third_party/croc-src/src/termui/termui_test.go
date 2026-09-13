package termui

import (
	"bytes"
	"io"
	"os"
	"strings"
	"testing"
)

func TestProgressTheme(t *testing.T) {
	plain := progressTheme(false)
	if plain.Saucer != "█" || plain.BarStart != "|" || plain.BarEnd != "|" {
		t.Fatalf("plain progress theme = %#v; want default bar theme", plain)
	}

	colored := progressTheme(true)
	if colored.BarStartFilled != "|[cyan]" {
		t.Fatalf("colored progress start = %q; want cyan", colored.BarStartFilled)
	}
	if colored.SaucerHead != "█[reset]" || colored.BarEndFilled != "[reset]|" {
		t.Fatalf("colored progress theme does not reset its filled section: %#v", colored)
	}
}

func TestProgressDescription(t *testing.T) {
	t.Setenv("COLUMNS", "100")
	if got := ProgressDescription("", "  croc.txt  ", false); got != "  croc.txt  " {
		t.Fatalf("plain progress description = %q", got)
	}
	if got := ProgressDescription("Hashing ", "croc.txt", true); got != "Hashing "+Bold+"croc.txt"+Reset {
		t.Fatalf("styled progress description = %q", got)
	}
	if got := ProgressDescription("", "   ", true); got != "   " {
		t.Fatalf("blank progress description = %q", got)
	}
}

func TestFitProgressDescriptionUsesDisplayWidth(t *testing.T) {
	t.Setenv("COLUMNS", "90")
	if got := FitProgressDescription("123456789🎉abc"); got != "123456789..." {
		t.Fatalf("display-width progress description = %q; want %q", got, "123456789...")
	}
}

func TestColoredProgressUsesCyanThenGreen(t *testing.T) {
	var output strings.Builder
	bar := NewProgress(ProgressConfig{
		Max:          2,
		Description:  ProgressDescription("", "croc.txt ", true),
		Writer:       &output,
		ColorEnabled: true,
	})
	output.Reset()
	if err := bar.Add(1); err != nil {
		t.Fatalf("render active progress: %v", err)
	}
	active := output.String()
	if !strings.Contains(active, Cyan) || strings.Contains(active, "[cyan]") {
		t.Fatalf("active progress is not rendered cyan: %q", active)
	}

	output.Reset()
	if err := bar.Finish(); err != nil {
		t.Fatalf("finish progress: %v", err)
	}
	complete := output.String()
	if !strings.Contains(complete, Green) || strings.Contains(complete, Cyan) {
		t.Fatalf("completed progress is not exclusively green: %q", complete)
	}
}

func TestDeterminateProgressUsesMainTransferFormat(t *testing.T) {
	var output strings.Builder
	bar := NewProgress(ProgressConfig{
		Max:         200,
		Description: "Hashing croc.txt ",
		Writer:      &output,
	})
	output.Reset()
	if err := bar.Add(100); err != nil {
		t.Fatalf("render determinate progress: %v", err)
	}
	got := output.String()
	if !strings.Contains(got, " 50% |██████████          |") {
		t.Fatalf("determinate progress does not use a 20-cell bar: %q", got)
	}
	if !strings.Contains(got, "100/200 B") {
		t.Fatalf("determinate progress has no byte count: %q", got)
	}
}

func TestProgressSpinnerUsesSharedStyling(t *testing.T) {
	var output strings.Builder
	bar := NewProgress(ProgressConfig{
		Max:           -1,
		Description:   ProgressDescription("Sampling ", "croc.txt", true),
		Writer:        &output,
		ColorEnabled:  true,
		ClearOnFinish: true,
	})
	if err := bar.Add(0); err != nil {
		t.Fatalf("render progress spinner: %v", err)
	}
	got := output.String()
	if !strings.Contains(got, "Sampling "+Bold+"croc.txt"+Reset) || !strings.Contains(got, Cyan) {
		t.Fatalf("spinner does not use shared styling: %q", got)
	}
	if strings.Contains(got, "%") || strings.Contains(got, "/s") {
		t.Fatalf("spinner contains determinate metadata: %q", got)
	}
	if err := bar.Finish(); err != nil {
		t.Fatalf("finish progress spinner: %v", err)
	}
}

func TestHiddenProgressDoesNotRender(t *testing.T) {
	var output strings.Builder
	bar := NewProgress(ProgressConfig{Max: 2, Writer: &output, Hidden: true})
	if err := bar.Finish(); err != nil {
		t.Fatalf("finish hidden progress: %v", err)
	}
	if output.Len() != 0 {
		t.Fatalf("hidden progress output = %q; want none", output.String())
	}
}

func TestTransientProgressClearsCompletion(t *testing.T) {
	var output strings.Builder
	bar := NewProgress(ProgressConfig{
		Max:           2,
		Description:   "Checking croc.txt ",
		Writer:        &output,
		ClearOnFinish: true,
	})
	if err := bar.Add(1); err != nil {
		t.Fatalf("render transient progress: %v", err)
	}
	output.Reset()
	if err := bar.Finish(); err != nil {
		t.Fatalf("finish transient progress: %v", err)
	}
	if strings.Contains(output.String(), "100%") {
		t.Fatalf("transient progress retained completion: %q", output.String())
	}
}

func TestShouldUseColor(t *testing.T) {
	tests := []struct {
		name         string
		noColor      string
		terminalName string
		isTerminal   bool
		want         bool
	}{
		{name: "interactive terminal", terminalName: "xterm-256color", isTerminal: true, want: true},
		{name: "redirected output", terminalName: "xterm-256color", isTerminal: false},
		{name: "NO_COLOR", noColor: "1", terminalName: "xterm-256color", isTerminal: true},
		{name: "dumb terminal", terminalName: "dumb", isTerminal: true},
		{name: "dumb terminal case insensitive", terminalName: " DUMB ", isTerminal: true},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got := shouldUseColor(test.noColor, test.terminalName, test.isTerminal)
			if got != test.want {
				t.Fatalf("shouldUseColor(%q, %q, %t) = %t; want %t", test.noColor, test.terminalName, test.isTerminal, got, test.want)
			}
		})
	}
}

func TestOutputDisablesColorForPipe(t *testing.T) {
	t.Setenv("NO_COLOR", "")
	t.Setenv("TERM", "xterm-256color")

	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatalf("create pipe: %v", err)
	}
	t.Cleanup(func() {
		_ = reader.Close()
		_ = writer.Close()
	})

	output, enabled := Output(writer)
	if enabled {
		t.Fatal("color enabled for redirected output")
	}
	if output != writer {
		t.Fatalf("redirected writer = %T; want original *os.File", output)
	}

	output, enabled = Output(nil)
	if enabled || output != io.Discard {
		t.Fatalf("nil terminal output = (%T, %t); want (io.Discard, false)", output, enabled)
	}
}

func TestPromptChoices(t *testing.T) {
	const prompt = "Replace example.txt? (y/N) Receive these files? (Y/n)"

	if got := PromptChoices(prompt, false); got != prompt {
		t.Fatalf("plain prompt = %q; want %q", got, prompt)
	}

	got := PromptChoices(prompt, true)
	if got != prompt {
		t.Fatalf("styled prompt = %q; want plain %q", got, prompt)
	}
}

func TestPlainRemovesTerminalStyles(t *testing.T) {
	styled := Filename("croc.txt", true) + " " + Success("done", true)
	if got := Plain(styled); got != "croc.txt done" {
		t.Fatalf("plain text = %q; want %q", got, "croc.txt done")
	}
}

func TestLoggerWriterNormalizesAndStylesLevels(t *testing.T) {
	const input = "\x1b[0;33m[warn]\t\x1b[0mwarning\n\x1b[0;31;1m[error]\t\x1b[0mfailure\n"

	t.Run("plain", func(t *testing.T) {
		var output bytes.Buffer
		writer := &loggerWriter{output: &output}
		if _, err := writer.Write([]byte(input)); err != nil {
			t.Fatalf("write log: %v", err)
		}
		want := "[warn]\twarning\n[error]\tfailure\n"
		if output.String() != want {
			t.Fatalf("plain log = %q; want %q", output.String(), want)
		}
	})

	t.Run("color", func(t *testing.T) {
		var output bytes.Buffer
		writer := &loggerWriter{output: &output, colorEnabled: true}
		if _, err := writer.Write([]byte(input)); err != nil {
			t.Fatalf("write log: %v", err)
		}
		got := output.String()
		if !strings.Contains(got, Yellow+"[warn]"+Reset) {
			t.Fatalf("warning level is not yellow: %q", got)
		}
		if !strings.Contains(got, Red+"[error]"+Reset) {
			t.Fatalf("error level is not red: %q", got)
		}
		if stripANSI(got) != "[warn]\twarning\n[error]\tfailure\n" {
			t.Fatalf("colored log changed text: %q", got)
		}
	})
}

func TestSemanticStylesUseGitLikePalette(t *testing.T) {
	tests := []struct {
		name  string
		got   string
		style string
	}{
		{name: "routine emphasis", got: Emphasis("Sending", true), style: Bold},
		{name: "filename", got: Filename("croc", true), style: Bold},
		{name: "secret", got: Secret("trio-door-handle-upside", true), style: Yellow},
		{name: "success", got: Success("Complete", true), style: Green},
		{name: "warning", got: Warning("Overwrite", true), style: Yellow},
		{name: "error", got: Error("Failed", true), style: Red},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if !strings.HasPrefix(test.got, test.style) || !strings.HasSuffix(test.got, Reset) {
				t.Fatalf("styled text = %q; want prefix %q and reset", test.got, test.style)
			}
		})
	}
}

func stripANSI(text string) string {
	return ansiPattern.ReplaceAllString(text, "")
}
