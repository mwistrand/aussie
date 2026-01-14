package benchmark

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestWriteOutput_TextFormat(t *testing.T) {
	summary := &StatsSummary{
		RequestCount:   100,
		SuccessCount:   95,
		ErrorCount:     5,
		ErrorRate:      0.05,
		Min:            5 * time.Millisecond,
		Max:            200 * time.Millisecond,
		Mean:           25 * time.Millisecond,
		StdDev:         15 * time.Millisecond,
		P50:            20 * time.Millisecond,
		P90:            50 * time.Millisecond,
		P95:            75 * time.Millisecond,
		P99:            150 * time.Millisecond,
		Duration:       1 * time.Second,
		RequestsPerSec: 100.0,
		Histogram: []HistogramBucket{
			{Label: "5-10ms", Count: 30, Percentage: 30.0},
			{Label: "10-20ms", Count: 40, Percentage: 40.0},
			{Label: "20-50ms", Count: 25, Percentage: 25.0},
			{Label: "50-100ms", Count: 5, Percentage: 5.0},
		},
	}

	cfg := OutputConfig{
		URL:    "http://localhost:8080/test",
		Method: "GET",
		Format: OutputFormatText,
	}

	var buf bytes.Buffer
	err := WriteOutput(&buf, summary, cfg)
	if err != nil {
		t.Fatalf("WriteOutput() error = %v", err)
	}

	output := buf.String()

	// Check header
	if !strings.Contains(output, "Benchmark Results") {
		t.Error("missing 'Benchmark Results' header")
	}
	if !strings.Contains(output, "Target: http://localhost:8080/test") {
		t.Error("missing target URL")
	}
	if !strings.Contains(output, "Method: GET") {
		t.Error("missing method")
	}

	// Check summary
	if !strings.Contains(output, "Total Requests:    100") {
		t.Error("missing total requests")
	}
	if !strings.Contains(output, "Successful:        95") {
		t.Error("missing successful count")
	}
	if !strings.Contains(output, "Failed:            5") {
		t.Error("missing failed count")
	}

	// Check latency
	if !strings.Contains(output, "Min:") {
		t.Error("missing min latency")
	}
	if !strings.Contains(output, "Max:") {
		t.Error("missing max latency")
	}
	if !strings.Contains(output, "Mean:") {
		t.Error("missing mean latency")
	}

	// Check percentiles
	if !strings.Contains(output, "P50:") {
		t.Error("missing P50")
	}
	if !strings.Contains(output, "P90:") {
		t.Error("missing P90")
	}
	if !strings.Contains(output, "P95:") {
		t.Error("missing P95")
	}
	if !strings.Contains(output, "P99:") {
		t.Error("missing P99")
	}

	// Check histogram
	if !strings.Contains(output, "Histogram") {
		t.Error("missing histogram section")
	}
	if !strings.Contains(output, "#") {
		t.Error("missing histogram bars")
	}
}

func TestWriteOutput_JSONFormat(t *testing.T) {
	summary := &StatsSummary{
		RequestCount:   100,
		SuccessCount:   95,
		ErrorCount:     5,
		ErrorRate:      0.05,
		Min:            5 * time.Millisecond,
		Max:            200 * time.Millisecond,
		Mean:           25 * time.Millisecond,
		StdDev:         15 * time.Millisecond,
		P50:            20 * time.Millisecond,
		P90:            50 * time.Millisecond,
		P95:            75 * time.Millisecond,
		P99:            150 * time.Millisecond,
		Duration:       1 * time.Second,
		RequestsPerSec: 100.0,
		Histogram: []HistogramBucket{
			{Label: "5-10ms", Count: 30, Percentage: 30.0},
		},
	}

	cfg := OutputConfig{
		URL:    "http://localhost:8080/test",
		Method: "POST",
		Format: OutputFormatJSON,
	}

	var buf bytes.Buffer
	err := WriteOutput(&buf, summary, cfg)
	if err != nil {
		t.Fatalf("WriteOutput() error = %v", err)
	}

	var output JSONOutput
	if err := json.Unmarshal(buf.Bytes(), &output); err != nil {
		t.Fatalf("Failed to parse JSON: %v", err)
	}

	// Check target and method
	if output.Target != "http://localhost:8080/test" {
		t.Errorf("expected target 'http://localhost:8080/test', got %s", output.Target)
	}
	if output.Method != "POST" {
		t.Errorf("expected method 'POST', got %s", output.Method)
	}

	// Check summary
	if output.Summary.TotalRequests != 100 {
		t.Errorf("expected 100 total requests, got %d", output.Summary.TotalRequests)
	}
	if output.Summary.SuccessCount != 95 {
		t.Errorf("expected 95 successes, got %d", output.Summary.SuccessCount)
	}
	if output.Summary.ErrorCount != 5 {
		t.Errorf("expected 5 errors, got %d", output.Summary.ErrorCount)
	}
	if output.Summary.ErrorRate != 0.05 {
		t.Errorf("expected error rate 0.05, got %f", output.Summary.ErrorRate)
	}

	// Check latency (values in ms)
	if output.Latency.MinMs != 5.0 {
		t.Errorf("expected min 5ms, got %f", output.Latency.MinMs)
	}
	if output.Latency.MaxMs != 200.0 {
		t.Errorf("expected max 200ms, got %f", output.Latency.MaxMs)
	}
	if output.Latency.MeanMs != 25.0 {
		t.Errorf("expected mean 25ms, got %f", output.Latency.MeanMs)
	}

	// Check percentiles
	if output.Percentiles.P50Ms != 20.0 {
		t.Errorf("expected P50 20ms, got %f", output.Percentiles.P50Ms)
	}
	if output.Percentiles.P99Ms != 150.0 {
		t.Errorf("expected P99 150ms, got %f", output.Percentiles.P99Ms)
	}

	// Check histogram
	if len(output.Histogram) != 1 {
		t.Fatalf("expected 1 histogram bucket, got %d", len(output.Histogram))
	}
	if output.Histogram[0].Label != "5-10ms" {
		t.Errorf("expected label '5-10ms', got %s", output.Histogram[0].Label)
	}
}

func TestWriteOutput_DefaultToText(t *testing.T) {
	summary := &StatsSummary{
		RequestCount: 10,
	}

	cfg := OutputConfig{
		URL:    "http://localhost:8080/test",
		Method: "GET",
		Format: "invalid",
	}

	var buf bytes.Buffer
	err := WriteOutput(&buf, summary, cfg)
	if err != nil {
		t.Fatalf("WriteOutput() error = %v", err)
	}

	// Should default to text format
	if !strings.Contains(buf.String(), "Benchmark Results") {
		t.Error("should have defaulted to text format")
	}
}

func TestWriteOutput_EmptyHistogram(t *testing.T) {
	summary := &StatsSummary{
		RequestCount: 10,
		Histogram:    nil,
	}

	cfg := OutputConfig{
		URL:    "http://localhost:8080/test",
		Method: "GET",
		Format: OutputFormatText,
	}

	var buf bytes.Buffer
	err := WriteOutput(&buf, summary, cfg)
	if err != nil {
		t.Fatalf("WriteOutput() error = %v", err)
	}

	// Should not have histogram section when empty
	// Actually it still has the section header but no bars
	output := buf.String()
	if strings.Contains(output, "[#") {
		t.Error("should not have histogram bars when histogram is nil")
	}
}

func TestFormatDuration(t *testing.T) {
	tests := []struct {
		input    time.Duration
		expected string
	}{
		{0, "0"},
		{500 * time.Nanosecond, "500ns"},
		{1500 * time.Nanosecond, "1.5us"},
		{500 * time.Microsecond, "500.0us"},
		{1500 * time.Microsecond, "1.5ms"},
		{5 * time.Millisecond, "5.0ms"},
		{50 * time.Millisecond, "50.0ms"},
		{500 * time.Millisecond, "500.0ms"},
		{1 * time.Second, "1.00s"},
		{1500 * time.Millisecond, "1.50s"},
		{30 * time.Second, "30.00s"},
		{90 * time.Second, "1.5m"},
		{5 * time.Minute, "5.0m"},
	}

	for _, tt := range tests {
		t.Run(tt.expected, func(t *testing.T) {
			got := formatDuration(tt.input)
			if got != tt.expected {
				t.Errorf("formatDuration(%v) = %s, want %s", tt.input, got, tt.expected)
			}
		})
	}
}

func TestWriteHistogram_Scaling(t *testing.T) {
	buckets := []HistogramBucket{
		{Label: "< 1ms", Count: 100, Percentage: 50.0},
		{Label: "1-2ms", Count: 50, Percentage: 25.0},
		{Label: "2-5ms", Count: 50, Percentage: 25.0},
	}

	var buf bytes.Buffer
	writeHistogram(&buf, buckets, 200)

	output := buf.String()
	lines := strings.Split(strings.TrimSpace(output), "\n")

	if len(lines) != 3 {
		t.Fatalf("expected 3 histogram lines, got %d", len(lines))
	}

	// First bucket should have full bar (20 #s)
	if !strings.Contains(lines[0], strings.Repeat("#", 20)) {
		t.Error("first bucket should have full bar")
	}

	// Other buckets should have half bars (10 #s)
	if !strings.Contains(lines[1], strings.Repeat("#", 10)) {
		t.Error("second bucket should have half bar")
	}
}

func TestWriteHistogram_SingleItem(t *testing.T) {
	buckets := []HistogramBucket{
		{Label: "< 1ms", Count: 1, Percentage: 100.0},
	}

	var buf bytes.Buffer
	writeHistogram(&buf, buckets, 1)

	output := buf.String()
	if !strings.Contains(output, "#") {
		t.Error("single item histogram should have bar")
	}
}

func TestWriteHistogram_SmallCounts(t *testing.T) {
	// Test that small non-zero counts still get at least 1 bar character
	buckets := []HistogramBucket{
		{Label: "< 1ms", Count: 100, Percentage: 99.0},
		{Label: "1-2ms", Count: 1, Percentage: 1.0},
	}

	var buf bytes.Buffer
	writeHistogram(&buf, buckets, 101)

	output := buf.String()
	lines := strings.Split(strings.TrimSpace(output), "\n")

	// Second line should still have at least 1 # even though it's small
	if !strings.Contains(lines[1], "#") {
		t.Error("small non-zero count should still have at least 1 bar character")
	}
}

func TestJSONOutput_Serialization(t *testing.T) {
	output := JSONOutput{
		Target: "http://test",
		Method: "GET",
		Summary: JSONSummary{
			TotalRequests:  100,
			SuccessCount:   90,
			ErrorCount:     10,
			ErrorRate:      0.1,
			DurationMs:     1000.0,
			RequestsPerSec: 100.0,
		},
		Latency: JSONLatency{
			MinMs:    1.0,
			MaxMs:    100.0,
			MeanMs:   10.0,
			StdDevMs: 5.0,
		},
		Percentiles: JSONPercentiles{
			P50Ms: 8.0,
			P90Ms: 20.0,
			P95Ms: 30.0,
			P99Ms: 50.0,
		},
		Histogram: []JSONBucket{
			{Label: "test", Count: 100, Percentage: 100.0},
		},
	}

	data, err := json.Marshal(output)
	if err != nil {
		t.Fatalf("Failed to marshal: %v", err)
	}

	var decoded JSONOutput
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("Failed to unmarshal: %v", err)
	}

	if decoded.Target != output.Target {
		t.Error("target mismatch")
	}
	if decoded.Summary.TotalRequests != output.Summary.TotalRequests {
		t.Error("total requests mismatch")
	}
}

func TestWriteOutput_ZeroValues(t *testing.T) {
	summary := &StatsSummary{
		RequestCount:   0,
		SuccessCount:   0,
		ErrorCount:     0,
		ErrorRate:      0,
		Min:            0,
		Max:            0,
		Mean:           0,
		StdDev:         0,
		P50:            0,
		P90:            0,
		P95:            0,
		P99:            0,
		Duration:       0,
		RequestsPerSec: 0,
	}

	cfg := OutputConfig{
		URL:    "http://localhost:8080/test",
		Method: "GET",
		Format: OutputFormatText,
	}

	var buf bytes.Buffer
	err := WriteOutput(&buf, summary, cfg)
	if err != nil {
		t.Fatalf("WriteOutput() error = %v", err)
	}

	// Should handle zero values gracefully
	output := buf.String()
	if !strings.Contains(output, "Total Requests:    0") {
		t.Error("should show 0 requests")
	}
}

func TestWriteOutput_SuccessRate(t *testing.T) {
	summary := &StatsSummary{
		RequestCount: 100,
		SuccessCount: 75,
		ErrorCount:   25,
		ErrorRate:    0.25,
	}

	cfg := OutputConfig{
		URL:    "http://localhost:8080/test",
		Method: "GET",
		Format: OutputFormatText,
	}

	var buf bytes.Buffer
	err := WriteOutput(&buf, summary, cfg)
	if err != nil {
		t.Fatalf("WriteOutput() error = %v", err)
	}

	output := buf.String()
	// Success rate should be 75%
	if !strings.Contains(output, "75.0%") {
		t.Error("should show 75% success rate")
	}
	// Error rate should be 25%
	if !strings.Contains(output, "25.0%") {
		t.Error("should show 25% error rate")
	}
}
