package benchmark

import (
	"math"
	"sort"
	"sync"
	"time"
)

// Stats collects latency measurements during a benchmark run.
// It is safe for concurrent use.
type Stats struct {
	mu        sync.Mutex
	latencies []time.Duration
	errors    int64
	successes int64
	startTime time.Time
	endTime   time.Time
}

// NewStats creates a new Stats instance.
func NewStats() *Stats {
	return &Stats{
		latencies: make([]time.Duration, 0, 1000),
		startTime: time.Now(),
	}
}

// RecordSuccess records a successful request latency.
func (s *Stats) RecordSuccess(latency time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.latencies = append(s.latencies, latency)
	s.successes++
}

// RecordError records a failed request latency.
func (s *Stats) RecordError(latency time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.latencies = append(s.latencies, latency)
	s.errors++
}

// Finalize marks the end of the benchmark run.
func (s *Stats) Finalize() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.endTime = time.Now()
}

// Summary computes and returns the statistics summary.
func (s *Stats) Summary() StatsSummary {
	s.mu.Lock()
	defer s.mu.Unlock()

	summary := StatsSummary{
		SuccessCount: s.successes,
		ErrorCount:   s.errors,
		RequestCount: s.successes + s.errors,
	}

	if summary.RequestCount > 0 {
		summary.ErrorRate = float64(s.errors) / float64(summary.RequestCount)
	}

	if s.endTime.IsZero() {
		summary.Duration = time.Since(s.startTime)
	} else {
		summary.Duration = s.endTime.Sub(s.startTime)
	}

	if summary.Duration > 0 {
		summary.RequestsPerSec = float64(summary.RequestCount) / summary.Duration.Seconds()
	}

	if len(s.latencies) == 0 {
		return summary
	}

	// Sort latencies for percentile calculation
	sorted := make([]time.Duration, len(s.latencies))
	copy(sorted, s.latencies)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })

	summary.Min = sorted[0]
	summary.Max = sorted[len(sorted)-1]
	summary.Mean = calculateMean(sorted)
	summary.StdDev = calculateStdDev(sorted, summary.Mean)
	summary.P50 = percentile(sorted, 50)
	summary.P90 = percentile(sorted, 90)
	summary.P95 = percentile(sorted, 95)
	summary.P99 = percentile(sorted, 99)
	summary.Histogram = generateHistogram(sorted)

	return summary
}

// StatsSummary contains computed statistics from a benchmark run.
type StatsSummary struct {
	RequestCount   int64
	SuccessCount   int64
	ErrorCount     int64
	ErrorRate      float64
	Min            time.Duration
	Max            time.Duration
	Mean           time.Duration
	StdDev         time.Duration
	P50            time.Duration
	P90            time.Duration
	P95            time.Duration
	P99            time.Duration
	Duration       time.Duration
	RequestsPerSec float64
	Histogram      []HistogramBucket
}

// HistogramBucket represents a bucket in the latency histogram.
type HistogramBucket struct {
	Label      string
	LowerBound time.Duration
	UpperBound time.Duration
	Count      int64
	Percentage float64
}

// calculateMean computes the mean of sorted durations.
func calculateMean(sorted []time.Duration) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	var sum int64
	for _, d := range sorted {
		sum += int64(d)
	}
	return time.Duration(sum / int64(len(sorted)))
}

// calculateStdDev computes the standard deviation.
func calculateStdDev(sorted []time.Duration, mean time.Duration) time.Duration {
	if len(sorted) < 2 {
		return 0
	}
	var sumSquares float64
	meanFloat := float64(mean)
	for _, d := range sorted {
		diff := float64(d) - meanFloat
		sumSquares += diff * diff
	}
	variance := sumSquares / float64(len(sorted))
	return time.Duration(math.Sqrt(variance))
}

// percentile calculates the p-th percentile from sorted data.
func percentile(sorted []time.Duration, p float64) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	if len(sorted) == 1 {
		return sorted[0]
	}
	// Use nearest-rank method
	rank := (p / 100) * float64(len(sorted)-1)
	lower := int(rank)
	upper := lower + 1
	if upper >= len(sorted) {
		return sorted[len(sorted)-1]
	}
	// Linear interpolation between adjacent values
	fraction := rank - float64(lower)
	return sorted[lower] + time.Duration(fraction*float64(sorted[upper]-sorted[lower]))
}

// generateHistogram creates log-scale histogram buckets.
func generateHistogram(sorted []time.Duration) []HistogramBucket {
	if len(sorted) == 0 {
		return nil
	}

	// Log-scale bucket boundaries
	boundaries := []struct {
		label string
		lower time.Duration
		upper time.Duration
	}{
		{"< 1ms", 0, 1 * time.Millisecond},
		{"1-2ms", 1 * time.Millisecond, 2 * time.Millisecond},
		{"2-5ms", 2 * time.Millisecond, 5 * time.Millisecond},
		{"5-10ms", 5 * time.Millisecond, 10 * time.Millisecond},
		{"10-20ms", 10 * time.Millisecond, 20 * time.Millisecond},
		{"20-50ms", 20 * time.Millisecond, 50 * time.Millisecond},
		{"50-100ms", 50 * time.Millisecond, 100 * time.Millisecond},
		{"100-200ms", 100 * time.Millisecond, 200 * time.Millisecond},
		{"200-500ms", 200 * time.Millisecond, 500 * time.Millisecond},
		{"500ms-1s", 500 * time.Millisecond, 1 * time.Second},
		{"1-2s", 1 * time.Second, 2 * time.Second},
		{"2-5s", 2 * time.Second, 5 * time.Second},
		{"> 5s", 5 * time.Second, time.Duration(math.MaxInt64)},
	}

	buckets := make([]HistogramBucket, len(boundaries))
	for i, b := range boundaries {
		buckets[i] = HistogramBucket{
			Label:      b.label,
			LowerBound: b.lower,
			UpperBound: b.upper,
		}
	}

	// Count latencies in each bucket
	total := float64(len(sorted))
	for _, latency := range sorted {
		for i := range buckets {
			if latency >= buckets[i].LowerBound && latency < buckets[i].UpperBound {
				buckets[i].Count++
				break
			}
		}
	}

	// Calculate percentages
	for i := range buckets {
		buckets[i].Percentage = float64(buckets[i].Count) / total * 100
	}

	// Filter out empty buckets at the ends
	start, end := 0, len(buckets)-1
	for start < len(buckets) && buckets[start].Count == 0 {
		start++
	}
	for end > start && buckets[end].Count == 0 {
		end--
	}

	if start > end {
		return nil
	}
	return buckets[start : end+1]
}
