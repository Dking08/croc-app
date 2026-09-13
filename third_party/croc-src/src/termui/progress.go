package termui

import (
	"bytes"
	"io"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/rivo/uniseg"
	"github.com/schollz/progressbar/v3"
	"golang.org/x/term"
)

const (
	progressBarWidth  = 20
	progressMetaWidth = 78
	minDescription    = 12
	defaultTermWidth  = 80
)

// ProgressConfig describes a CLI progress indicator using croc's shared
// terminal presentation. A negative Max creates an indeterminate spinner.
type ProgressConfig struct {
	Max           int64
	Description   string
	Writer        io.Writer
	ColorEnabled  bool
	Hidden        bool
	ClearOnFinish bool
	Throttle      time.Duration
	OnCompletion  func()
}

// NewProgress creates either croc's standard determinate progress bar or its
// matching indeterminate spinner.
func NewProgress(config ProgressConfig) *progressbar.ProgressBar {
	writer := config.Writer
	if writer == nil {
		writer = io.Discard
	}

	if config.Max < 0 {
		options := []progressbar.Option{
			progressbar.OptionSetWriter(writer),
			progressbar.OptionSetDescription(config.Description),
			progressbar.OptionSetRenderBlankState(true),
			progressbar.OptionSetVisibility(!config.Hidden),
			progressbar.OptionShowBytes(false),
			progressbar.OptionEnableColorCodes(config.ColorEnabled),
			progressbar.OptionSpinnerType(14),
			progressbar.OptionSetSpinnerChangeInterval(100 * time.Millisecond),
			progressbar.OptionShowElapsedTimeOnFinish(),
		}
		if config.ColorEnabled {
			options = append(options, progressbar.OptionSetSpinnerColorCode("cyan"))
		}
		if config.ClearOnFinish {
			options = append(options, progressbar.OptionClearOnFinish())
		}
		if config.OnCompletion != nil {
			options = append(options, progressbar.OptionOnCompletion(config.OnCompletion))
		}
		return progressbar.NewOptions64(-1, options...)
	}

	options := []progressbar.Option{
		progressbar.OptionSetWidth(progressBarWidth),
		progressbar.OptionSetDescription(config.Description),
		progressbar.OptionSetRenderBlankState(true),
		progressbar.OptionShowBytes(true),
		progressbar.OptionShowCount(),
		progressbar.OptionSetWriter(progressWriter(writer, config.ColorEnabled)),
		progressbar.OptionSetVisibility(!config.Hidden),
		progressbar.OptionEnableColorCodes(config.ColorEnabled),
		progressbar.OptionSetTheme(progressTheme(config.ColorEnabled)),
	}
	if config.ClearOnFinish {
		options = append(options, progressbar.OptionClearOnFinish())
	}
	if config.Throttle > 0 {
		options = append(options, progressbar.OptionThrottle(config.Throttle))
	}
	if config.OnCompletion != nil {
		options = append(options, progressbar.OptionOnCompletion(config.OnCompletion))
	}
	return progressbar.NewOptions64(config.Max, options...)
}

// ProgressDescription styles only the filename portion and gives it enough
// room to remain recognizable when the complete progress line is narrowed.
// The action should include any desired separator, such as "Hashing ".
func ProgressDescription(action, filename string, colorEnabled bool) string {
	filenameWidth := max(progressDescriptionWidth()-uniseg.StringWidth(action), minDescription)
	filename = truncateDisplayWidth(filename, filenameWidth)
	return action + styleProgressFilename(filename, colorEnabled)
}

// FitProgressDescription reserves room for the progress bar's variable
// metadata and truncates the description by terminal display width.
func FitProgressDescription(description string) string {
	return truncateDisplayWidth(description, progressDescriptionWidth())
}

func progressDescriptionWidth() int {
	width, _, err := term.GetSize(int(os.Stderr.Fd()))
	if err != nil || width <= 0 {
		width, _, err = term.GetSize(int(os.Stdout.Fd()))
	}
	if err != nil || width <= 0 {
		if envColumns, convErr := strconv.Atoi(os.Getenv("COLUMNS")); convErr == nil && envColumns > 0 {
			width = envColumns
		} else {
			width = defaultTermWidth
		}
	}

	return max(width-progressMetaWidth, minDescription)
}

func truncateDisplayWidth(value string, width int) string {
	if uniseg.StringWidth(value) <= width {
		return value
	}
	if width <= 3 {
		return displayWidthPrefix(value, width)
	}
	return displayWidthPrefix(value, width-3) + "..."
}

func displayWidthPrefix(value string, width int) string {
	var result strings.Builder
	used := 0
	graphemes := uniseg.NewGraphemes(value)
	for graphemes.Next() {
		cluster := graphemes.Str()
		clusterWidth := uniseg.StringWidth(cluster)
		if used+clusterWidth > width {
			break
		}
		result.WriteString(cluster)
		used += clusterWidth
	}
	return result.String()
}

func styleProgressFilename(description string, colorEnabled bool) string {
	if !colorEnabled {
		return description
	}
	filename := strings.Trim(description, " ")
	if filename == "" {
		return description
	}
	leading := description[:len(description)-len(strings.TrimLeft(description, " "))]
	trailing := description[len(strings.TrimRight(description, " ")):]
	return leading + Filename(filename, true) + trailing
}

func progressTheme(colorEnabled bool) progressbar.Theme {
	if !colorEnabled {
		return progressbar.ThemeDefault
	}
	return progressbar.Theme{
		Saucer:         "█",
		SaucerHead:     "█[reset]",
		SaucerPadding:  " ",
		BarStart:       "|",
		BarStartFilled: "|[cyan]",
		BarEnd:         "|",
		BarEndFilled:   "[reset]|",
	}
}

func progressWriter(output io.Writer, colorEnabled bool) io.Writer {
	if !colorEnabled {
		return output
	}
	return &completionColorWriter{output: output}
}

// completionColorWriter keeps progress cyan while it is active, then changes
// the final 100% render to green.
type completionColorWriter struct {
	output io.Writer
}

func (w *completionColorWriter) Write(p []byte) (int, error) {
	if bytes.Contains(p, []byte("100% |"+Cyan)) {
		p = bytes.ReplaceAll(p, []byte(Cyan), []byte(Green))
	}
	n, err := w.output.Write(p)
	if n == len(p) {
		return len(p), err
	}
	return n, err
}
