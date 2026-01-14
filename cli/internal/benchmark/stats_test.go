package benchmark

import (
	"sync"
	"testing"
	"time"
)

func TestNewStats(t *testing.T) {
	s := NewStats()
	if s == nil {
		t.Fatal("NewStats returned nil")
	}
	if s.latencies == nil {
		t.Error("latencies slice not initialized")
	}
	if s.startTime.IsZero() {
		t.Error("startTime not set")
	}
}

func TestRecordSuccess(t *testing.T) {
	s := NewStats()
	s.RecordSuccess(10 * time.Millisecond)
	s.RecordSuccess(20 * time.Millisecond)

	summary := s.Summary()
	if summary.SuccessCount != 2 {
		t.Errorf("expected 2 successes, got %d", summary.SuccessCount)
	}
	if summary.ErrorCount != 0 {
		t.Errorf("expected 0 errors, got %d", summary.ErrorCount)
	}
	if summary.RequestCount != 2 {
		t.Errorf("expected 2 requests, got %d", summary.RequestCount)
	}
}

func TestRecordError(t *testing.T) {
	s := NewStats()
	s.RecordError(10 * time.Millisecond)
	s.RecordError(20 * time.Millisecond)

	summary := s.Summary()
	if summary.ErrorCount != 2 {
		t.Errorf("expected 2 errors, got %d", summary.ErrorCount)
	}
	if summary.SuccessCount != 0 {
		t.Errorf("expected 0 successes, got %d", summary.SuccessCount)
	}
	if summary.ErrorRate != 1.0 {
		t.Errorf("expected error rate 1.0, got %f", summary.ErrorRate)
	}
}

func TestMixedSuccessAndError(t *testing.T) {
	s := NewStats()
	s.RecordSuccess(10 * time.Millisecond)
	s.RecordSuccess(20 * time.Millisecond)
	s.RecordSuccess(30 * time.Millisecond)
	s.RecordError(40 * time.Millisecond)

	summary := s.Summary()
	if summary.SuccessCount != 3 {
		t.Errorf("expected 3 successes, got %d", summary.SuccessCount)
	}
	if summary.ErrorCount != 1 {
		t.Errorf("expected 1 error, got %d", summary.ErrorCount)
	}
	if summary.ErrorRate != 0.25 {
		t.Errorf("expected error rate 0.25, got %f", summary.ErrorRate)
	}
}

func TestEmptyStats(t *testing.T) {
	s := NewStats()
	summary := s.Summary()

	if summary.RequestCount != 0 {
		t.Errorf("expected 0 requests, got %d", summary.RequestCount)
	}
	if summary.Min != 0 {
		t.Errorf("expected min 0, got %v", summary.Min)
	}
	if summary.Max != 0 {
		t.Errorf("expected max 0, got %v", summary.Max)
	}
	if summary.Mean != 0 {
		t.Errorf("expected mean 0, got %v", summary.Mean)
	}
	if summary.Histogram != nil {
		t.Errorf("expected nil histogram, got %v", summary.Histogram)
	}
}

func TestSingleRequest(t *testing.T) {
	s := NewStats()
	s.RecordSuccess(50 * time.Millisecond)

	summary := s.Summary()
	if summary.Min != 50*time.Millisecond {
		t.Errorf("expected min 50ms, got %v", summary.Min)
	}
	if summary.Max != 50*time.Millisecond {
		t.Errorf("expected max 50ms, got %v", summary.Max)
	}
	if summary.Mean != 50*time.Millisecond {
		t.Errorf("expected mean 50ms, got %v", summary.Mean)
	}
	if summary.P50 != 50*time.Millisecond {
		t.Errorf("expected P50 50ms, got %v", summary.P50)
	}
	// StdDev should be 0 for single value
	if summary.StdDev != 0 {
		t.Errorf("expected stddev 0, got %v", summary.StdDev)
	}
}

func TestMinMax(t *testing.T) {
	s := NewStats()
	s.RecordSuccess(100 * time.Millisecond)
	s.RecordSuccess(10 * time.Millisecond)
	s.RecordSuccess(50 * time.Millisecond)
	s.RecordSuccess(200 * time.Millisecond)
	s.RecordSuccess(5 * time.Millisecond)

	summary := s.Summary()
	if summary.Min != 5*time.Millisecond {
		t.Errorf("expected min 5ms, got %v", summary.Min)
	}
	if summary.Max != 200*time.Millisecond {
		t.Errorf("expected max 200ms, got %v", summary.Max)
	}
}

func TestMean(t *testing.T) {
	s := NewStats()
	// 10 + 20 + 30 + 40 = 100, mean = 25
	s.RecordSuccess(10 * time.Millisecond)
	s.RecordSuccess(20 * time.Millisecond)
	s.RecordSuccess(30 * time.Millisecond)
	s.RecordSuccess(40 * time.Millisecond)

	summary := s.Summary()
	if summary.Mean != 25*time.Millisecond {
		t.Errorf("expected mean 25ms, got %v", summary.Mean)
	}
}

func TestPercentiles(t *testing.T) {
	s := NewStats()
	// Create 100 values: 1ms, 2ms, 3ms, ..., 100ms
	for i := 1; i <= 100; i++ {
		s.RecordSuccess(time.Duration(i) * time.Millisecond)
	}

	summary := s.Summary()

	// P50 should be around 50ms
	if summary.P50 < 49*time.Millisecond || summary.P50 > 51*time.Millisecond {
		t.Errorf("expected P50 around 50ms, got %v", summary.P50)
	}

	// P90 should be around 90ms
	if summary.P90 < 89*time.Millisecond || summary.P90 > 91*time.Millisecond {
		t.Errorf("expected P90 around 90ms, got %v", summary.P90)
	}

	// P95 should be around 95ms
	if summary.P95 < 94*time.Millisecond || summary.P95 > 96*time.Millisecond {
		t.Errorf("expected P95 around 95ms, got %v", summary.P95)
	}

	// P99 should be around 99ms
	if summary.P99 < 98*time.Millisecond || summary.P99 > 100*time.Millisecond {
		t.Errorf("expected P99 around 99ms, got %v", summary.P99)
	}
}

func TestStdDev(t *testing.T) {
	s := NewStats()
	// All same values should have stddev 0
	s.RecordSuccess(50 * time.Millisecond)
	s.RecordSuccess(50 * time.Millisecond)
	s.RecordSuccess(50 * time.Millisecond)
	s.RecordSuccess(50 * time.Millisecond)

	summary := s.Summary()
	if summary.StdDev != 0 {
		t.Errorf("expected stddev 0 for identical values, got %v", summary.StdDev)
	}
}

func TestStdDevNonZero(t *testing.T) {
	s := NewStats()
	// Values: 0, 100 - mean is 50, stddev should be 50
	s.RecordSuccess(0)
	s.RecordSuccess(100 * time.Millisecond)

	summary := s.Summary()
	// StdDev of [0, 100] with mean 50 is sqrt((50^2 + 50^2)/2) = sqrt(2500) = 50
	expected := 50 * time.Millisecond
	tolerance := 1 * time.Millisecond
	if summary.StdDev < expected-tolerance || summary.StdDev > expected+tolerance {
		t.Errorf("expected stddev around 50ms, got %v", summary.StdDev)
	}
}

func TestHistogramBuckets(t *testing.T) {
	s := NewStats()
	// Add values in different buckets
	s.RecordSuccess(500 * time.Microsecond)  // < 1ms
	s.RecordSuccess(1500 * time.Microsecond) // 1-2ms
	s.RecordSuccess(3 * time.Millisecond)    // 2-5ms
	s.RecordSuccess(7 * time.Millisecond)    // 5-10ms

	summary := s.Summary()
	if len(summary.Histogram) == 0 {
		t.Fatal("expected non-empty histogram")
	}

	// Verify we have 4 buckets with 1 each
	totalCount := int64(0)
	for _, bucket := range summary.Histogram {
		totalCount += bucket.Count
	}
	if totalCount != 4 {
		t.Errorf("expected 4 total count, got %d", totalCount)
	}
}

func TestHistogramPercentages(t *testing.T) {
	s := NewStats()
	// 2 requests in one bucket, 2 in another = 50% each
	s.RecordSuccess(500 * time.Microsecond)
	s.RecordSuccess(600 * time.Microsecond)
	s.RecordSuccess(3 * time.Millisecond)
	s.RecordSuccess(4 * time.Millisecond)

	summary := s.Summary()

	// Find buckets with values
	for _, bucket := range summary.Histogram {
		if bucket.Count == 2 {
			if bucket.Percentage != 50.0 {
				t.Errorf("expected 50%% for bucket %s, got %.1f%%", bucket.Label, bucket.Percentage)
			}
		}
	}
}

func TestConcurrentRecording(t *testing.T) {
	s := NewStats()
	var wg sync.WaitGroup
	numGoroutines := 100
	requestsPerGoroutine := 100

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < requestsPerGoroutine; j++ {
				s.RecordSuccess(time.Duration(j) * time.Millisecond)
			}
		}()
	}

	wg.Wait()
	summary := s.Summary()

	expectedCount := int64(numGoroutines * requestsPerGoroutine)
	if summary.RequestCount != expectedCount {
		t.Errorf("expected %d requests, got %d", expectedCount, summary.RequestCount)
	}
	if summary.SuccessCount != expectedCount {
		t.Errorf("expected %d successes, got %d", expectedCount, summary.SuccessCount)
	}
}

func TestFinalize(t *testing.T) {
	s := NewStats()
	s.RecordSuccess(10 * time.Millisecond)
	time.Sleep(50 * time.Millisecond)
	s.Finalize()

	summary := s.Summary()
	if summary.Duration < 50*time.Millisecond {
		t.Errorf("expected duration at least 50ms, got %v", summary.Duration)
	}
}

func TestRequestsPerSec(t *testing.T) {
	s := NewStats()
	// Record requests
	for i := 0; i < 10; i++ {
		s.RecordSuccess(1 * time.Millisecond)
	}
	time.Sleep(100 * time.Millisecond)
	s.Finalize()

	summary := s.Summary()
	// Should be approximately 100 req/sec (10 requests in ~100ms)
	// But allow wide tolerance since timing is imprecise
	if summary.RequestsPerSec <= 0 {
		t.Error("expected positive requests per second")
	}
}

func TestAllErrors(t *testing.T) {
	s := NewStats()
	s.RecordError(10 * time.Millisecond)
	s.RecordError(20 * time.Millisecond)
	s.RecordError(30 * time.Millisecond)

	summary := s.Summary()
	if summary.ErrorRate != 1.0 {
		t.Errorf("expected error rate 1.0, got %f", summary.ErrorRate)
	}
	if summary.SuccessCount != 0 {
		t.Errorf("expected 0 successes, got %d", summary.SuccessCount)
	}
	if summary.ErrorCount != 3 {
		t.Errorf("expected 3 errors, got %d", summary.ErrorCount)
	}
	// Even with errors, latency stats should be computed
	if summary.Min != 10*time.Millisecond {
		t.Errorf("expected min 10ms, got %v", summary.Min)
	}
}

func TestCalculateMeanEmpty(t *testing.T) {
	result := calculateMean([]time.Duration{})
	if result != 0 {
		t.Errorf("expected 0 for empty slice, got %v", result)
	}
}

func TestPercentileEmpty(t *testing.T) {
	result := percentile([]time.Duration{}, 50)
	if result != 0 {
		t.Errorf("expected 0 for empty slice, got %v", result)
	}
}

func TestPercentileSingleValue(t *testing.T) {
	sorted := []time.Duration{100 * time.Millisecond}

	p50 := percentile(sorted, 50)
	if p50 != 100*time.Millisecond {
		t.Errorf("expected 100ms for single value P50, got %v", p50)
	}

	p99 := percentile(sorted, 99)
	if p99 != 100*time.Millisecond {
		t.Errorf("expected 100ms for single value P99, got %v", p99)
	}
}

func TestGenerateHistogramEmpty(t *testing.T) {
	result := generateHistogram([]time.Duration{})
	if result != nil {
		t.Errorf("expected nil for empty slice, got %v", result)
	}
}

func TestGenerateHistogramFiltersEmptyBuckets(t *testing.T) {
	// All values in single bucket
	sorted := []time.Duration{
		3 * time.Millisecond,
		4 * time.Millisecond,
	}
	buckets := generateHistogram(sorted)

	// Should only have the 2-5ms bucket
	if len(buckets) != 1 {
		t.Errorf("expected 1 bucket, got %d", len(buckets))
	}
	if buckets[0].Label != "2-5ms" {
		t.Errorf("expected '2-5ms' bucket, got %s", buckets[0].Label)
	}
	if buckets[0].Count != 2 {
		t.Errorf("expected count 2, got %d", buckets[0].Count)
	}
}

func TestLargeLatencyBucket(t *testing.T) {
	s := NewStats()
	s.RecordSuccess(6 * time.Second) // > 5s bucket

	summary := s.Summary()
	if len(summary.Histogram) == 0 {
		t.Fatal("expected histogram with >5s bucket")
	}

	lastBucket := summary.Histogram[len(summary.Histogram)-1]
	if lastBucket.Label != "> 5s" {
		t.Errorf("expected '> 5s' bucket, got %s", lastBucket.Label)
	}
	if lastBucket.Count != 1 {
		t.Errorf("expected count 1, got %d", lastBucket.Count)
	}
}
