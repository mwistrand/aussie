package benchmark

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"time"
)

const (
	// OutputFormatText is human-readable text output.
	OutputFormatText = "text"
	// OutputFormatJSON is JSON output.
	OutputFormatJSON = "json"
)

// OutputConfig contains output formatting configuration.
type OutputConfig struct {
	URL    string
	Method string
	Format string
}

// WriteOutput writes the benchmark results to the writer in the specified format.
func WriteOutput(w io.Writer, summary *StatsSummary, cfg OutputConfig) error {
	switch cfg.Format {
	case OutputFormatJSON:
		return writeJSON(w, summary, cfg)
	case OutputFormatText:
		fallthrough
	default:
		return writeText(w, summary, cfg)
	}
}

// JSONOutput represents the JSON output structure.
type JSONOutput struct {
	Target      string          `json:"target"`
	Method      string          `json:"method"`
	Summary     JSONSummary     `json:"summary"`
	Latency     JSONLatency     `json:"latency"`
	Percentiles JSONPercentiles `json:"percentiles"`
	Histogram   []JSONBucket    `json:"histogram"`
}

// JSONSummary contains the summary section of JSON output.
type JSONSummary struct {
	TotalRequests  int64   `json:"total_requests"`
	SuccessCount   int64   `json:"success_count"`
	ErrorCount     int64   `json:"error_count"`
	ErrorRate      float64 `json:"error_rate"`
	DurationMs     float64 `json:"duration_ms"`
	RequestsPerSec float64 `json:"requests_per_sec"`
}

// JSONLatency contains latency statistics in JSON output.
type JSONLatency struct {
	MinMs    float64 `json:"min_ms"`
	MaxMs    float64 `json:"max_ms"`
	MeanMs   float64 `json:"mean_ms"`
	StdDevMs float64 `json:"stddev_ms"`
}

// JSONPercentiles contains percentile values in JSON output.
type JSONPercentiles struct {
	P50Ms float64 `json:"p50_ms"`
	P90Ms float64 `json:"p90_ms"`
	P95Ms float64 `json:"p95_ms"`
	P99Ms float64 `json:"p99_ms"`
}

// JSONBucket represents a histogram bucket in JSON output.
type JSONBucket struct {
	Label      string  `json:"label"`
	Count      int64   `json:"count"`
	Percentage float64 `json:"percentage"`
}

func writeJSON(w io.Writer, summary *StatsSummary, cfg OutputConfig) error {
	output := JSONOutput{
		Target: cfg.URL,
		Method: cfg.Method,
		Summary: JSONSummary{
			TotalRequests:  summary.RequestCount,
			SuccessCount:   summary.SuccessCount,
			ErrorCount:     summary.ErrorCount,
			ErrorRate:      summary.ErrorRate,
			DurationMs:     float64(summary.Duration) / float64(time.Millisecond),
			RequestsPerSec: summary.RequestsPerSec,
		},
		Latency: JSONLatency{
			MinMs:    float64(summary.Min) / float64(time.Millisecond),
			MaxMs:    float64(summary.Max) / float64(time.Millisecond),
			MeanMs:   float64(summary.Mean) / float64(time.Millisecond),
			StdDevMs: float64(summary.StdDev) / float64(time.Millisecond),
		},
		Percentiles: JSONPercentiles{
			P50Ms: float64(summary.P50) / float64(time.Millisecond),
			P90Ms: float64(summary.P90) / float64(time.Millisecond),
			P95Ms: float64(summary.P95) / float64(time.Millisecond),
			P99Ms: float64(summary.P99) / float64(time.Millisecond),
		},
		Histogram: make([]JSONBucket, len(summary.Histogram)),
	}

	for i, bucket := range summary.Histogram {
		output.Histogram[i] = JSONBucket{
			Label:      bucket.Label,
			Count:      bucket.Count,
			Percentage: bucket.Percentage,
		}
	}

	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "  ")
	return encoder.Encode(output)
}

func writeText(w io.Writer, summary *StatsSummary, cfg OutputConfig) error {
	// Header
	fmt.Fprintln(w, "Benchmark Results")
	fmt.Fprintln(w, "=================")
	fmt.Fprintf(w, "Target: %s\n", cfg.URL)
	fmt.Fprintf(w, "Method: %s\n", cfg.Method)
	fmt.Fprintln(w)

	// Summary
	fmt.Fprintln(w, "Summary")
	fmt.Fprintln(w, "-------")
	fmt.Fprintf(w, "  Total Requests:    %d\n", summary.RequestCount)
	fmt.Fprintf(w, "  Successful:        %d (%.1f%%)\n", summary.SuccessCount, (1-summary.ErrorRate)*100)
	fmt.Fprintf(w, "  Failed:            %d (%.1f%%)\n", summary.ErrorCount, summary.ErrorRate*100)
	fmt.Fprintf(w, "  Duration:          %s\n", formatDuration(summary.Duration))
	fmt.Fprintf(w, "  Requests/sec:      %.1f\n", summary.RequestsPerSec)
	fmt.Fprintln(w)

	// Latency Distribution
	fmt.Fprintln(w, "Latency Distribution")
	fmt.Fprintln(w, "--------------------")
	fmt.Fprintf(w, "  Min:      %-12s  Max:      %s\n", formatDuration(summary.Min), formatDuration(summary.Max))
	fmt.Fprintf(w, "  Mean:     %-12s  Std Dev:  %s\n", formatDuration(summary.Mean), formatDuration(summary.StdDev))
	fmt.Fprintln(w)

	// Percentiles
	fmt.Fprintln(w, "Percentiles")
	fmt.Fprintln(w, "-----------")
	fmt.Fprintf(w, "  P50:  %-10s  P90:  %-10s  P95:  %-10s  P99:  %s\n",
		formatDuration(summary.P50),
		formatDuration(summary.P90),
		formatDuration(summary.P95),
		formatDuration(summary.P99))
	fmt.Fprintln(w)

	// Histogram
	if len(summary.Histogram) > 0 {
		fmt.Fprintln(w, "Histogram")
		fmt.Fprintln(w, "---------")
		writeHistogram(w, summary.Histogram, summary.RequestCount)
	}

	return nil
}

func writeHistogram(w io.Writer, buckets []HistogramBucket, total int64) {
	// Find max count for scaling
	maxCount := int64(0)
	for _, bucket := range buckets {
		if bucket.Count > maxCount {
			maxCount = bucket.Count
		}
	}

	barWidth := 20
	for _, bucket := range buckets {
		// Calculate bar length
		barLen := 0
		if maxCount > 0 {
			barLen = int(float64(bucket.Count) / float64(maxCount) * float64(barWidth))
		}
		if bucket.Count > 0 && barLen == 0 {
			barLen = 1 // Ensure at least 1 char for non-zero counts
		}

		bar := strings.Repeat("#", barLen) + strings.Repeat(" ", barWidth-barLen)
		fmt.Fprintf(w, "  %-12s [%s] %d (%.1f%%)\n", bucket.Label, bar, bucket.Count, bucket.Percentage)
	}
}

// formatDuration formats a duration for display.
func formatDuration(d time.Duration) string {
	if d == 0 {
		return "0"
	}

	if d < time.Microsecond {
		return fmt.Sprintf("%dns", d.Nanoseconds())
	}
	if d < time.Millisecond {
		return fmt.Sprintf("%.1fus", float64(d.Nanoseconds())/1000)
	}
	if d < time.Second {
		return fmt.Sprintf("%.1fms", float64(d.Nanoseconds())/1e6)
	}
	if d < time.Minute {
		return fmt.Sprintf("%.2fs", d.Seconds())
	}
	return fmt.Sprintf("%.1fm", d.Minutes())
}
