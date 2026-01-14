package benchmark

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestConfigValidate(t *testing.T) {
	tests := []struct {
		name    string
		config  Config
		wantErr bool
	}{
		{
			name: "valid config",
			config: Config{
				URL:           "http://localhost:8080/test",
				Method:        "GET",
				TotalRequests: 10,
				Interval:      10 * time.Millisecond,
				Timeout:       30 * time.Second,
			},
			wantErr: false,
		},
		{
			name: "missing URL",
			config: Config{
				Method:        "GET",
				TotalRequests: 10,
				Interval:      10 * time.Millisecond,
				Timeout:       30 * time.Second,
			},
			wantErr: true,
		},
		{
			name: "missing method",
			config: Config{
				URL:           "http://localhost:8080/test",
				TotalRequests: 10,
				Interval:      10 * time.Millisecond,
				Timeout:       30 * time.Second,
			},
			wantErr: true,
		},
		{
			name: "zero requests",
			config: Config{
				URL:           "http://localhost:8080/test",
				Method:        "GET",
				TotalRequests: 0,
				Interval:      10 * time.Millisecond,
				Timeout:       30 * time.Second,
			},
			wantErr: true,
		},
		{
			name: "negative requests",
			config: Config{
				URL:           "http://localhost:8080/test",
				Method:        "GET",
				TotalRequests: -1,
				Interval:      10 * time.Millisecond,
				Timeout:       30 * time.Second,
			},
			wantErr: true,
		},
		{
			name: "zero interval",
			config: Config{
				URL:           "http://localhost:8080/test",
				Method:        "GET",
				TotalRequests: 10,
				Interval:      0,
				Timeout:       30 * time.Second,
			},
			wantErr: true,
		},
		{
			name: "zero timeout",
			config: Config{
				URL:           "http://localhost:8080/test",
				Method:        "GET",
				TotalRequests: 10,
				Interval:      10 * time.Millisecond,
				Timeout:       0,
			},
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.config.Validate()
			if (err != nil) != tt.wantErr {
				t.Errorf("Validate() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestNewRunner(t *testing.T) {
	cfg := Config{
		URL:           "http://localhost:8080/test",
		Method:        "GET",
		TotalRequests: 10,
		Interval:      10 * time.Millisecond,
		Timeout:       30 * time.Second,
	}

	runner := NewRunner(cfg)
	if runner == nil {
		t.Fatal("NewRunner returned nil")
	}
	if runner.client == nil {
		t.Error("client not initialized")
	}
	if runner.stats == nil {
		t.Error("stats not initialized")
	}
}

func TestRequestResult_IsSuccess(t *testing.T) {
	tests := []struct {
		name   string
		result RequestResult
		want   bool
	}{
		{
			name:   "success 200",
			result: RequestResult{StatusCode: 200},
			want:   true,
		},
		{
			name:   "success 204",
			result: RequestResult{StatusCode: 204},
			want:   true,
		},
		{
			name:   "success 201",
			result: RequestResult{StatusCode: 201},
			want:   true,
		},
		{
			name:   "error 400",
			result: RequestResult{StatusCode: 400},
			want:   false,
		},
		{
			name:   "error 500",
			result: RequestResult{StatusCode: 500},
			want:   false,
		},
		{
			name:   "error with err set",
			result: RequestResult{StatusCode: 200, Error: context.DeadlineExceeded},
			want:   false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := tt.result.IsSuccess(); got != tt.want {
				t.Errorf("IsSuccess() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestRunnerRun_BasicSuccess(t *testing.T) {
	// Create test server that returns 204
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	cfg := Config{
		URL:           server.URL,
		Method:        "GET",
		TotalRequests: 5,
		Interval:      10 * time.Millisecond,
		Timeout:       5 * time.Second,
	}

	runner := NewRunner(cfg)
	ctx := context.Background()

	summary, err := runner.Run(ctx)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	if summary.RequestCount != 5 {
		t.Errorf("expected 5 requests, got %d", summary.RequestCount)
	}
	if summary.SuccessCount != 5 {
		t.Errorf("expected 5 successes, got %d", summary.SuccessCount)
	}
	if summary.ErrorCount != 0 {
		t.Errorf("expected 0 errors, got %d", summary.ErrorCount)
	}
}

func TestRunnerRun_RecordsErrors(t *testing.T) {
	// Create test server that returns 500
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	cfg := Config{
		URL:           server.URL,
		Method:        "GET",
		TotalRequests: 3,
		Interval:      10 * time.Millisecond,
		Timeout:       5 * time.Second,
	}

	runner := NewRunner(cfg)
	ctx := context.Background()

	summary, err := runner.Run(ctx)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	if summary.ErrorCount != 3 {
		t.Errorf("expected 3 errors, got %d", summary.ErrorCount)
	}
	if summary.ErrorRate != 1.0 {
		t.Errorf("expected error rate 1.0, got %f", summary.ErrorRate)
	}
}

func TestRunnerRun_SetsAuthHeader(t *testing.T) {
	var receivedToken string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedToken = r.Header.Get("Authorization")
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	cfg := Config{
		URL:           server.URL,
		Method:        "GET",
		TotalRequests: 1,
		Interval:      10 * time.Millisecond,
		Timeout:       5 * time.Second,
		AuthToken:     "test-token-123",
	}

	runner := NewRunner(cfg)
	ctx := context.Background()

	_, err := runner.Run(ctx)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	expected := "Bearer test-token-123"
	if receivedToken != expected {
		t.Errorf("expected auth header %q, got %q", expected, receivedToken)
	}
}

func TestRunnerRun_SetsCustomHeaders(t *testing.T) {
	var receivedHeaders http.Header
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedHeaders = r.Header.Clone()
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	cfg := Config{
		URL:           server.URL,
		Method:        "GET",
		TotalRequests: 1,
		Interval:      10 * time.Millisecond,
		Timeout:       5 * time.Second,
		Headers: map[string]string{
			"X-Custom-Header": "custom-value",
			"X-Another":       "another-value",
		},
	}

	runner := NewRunner(cfg)
	ctx := context.Background()

	_, err := runner.Run(ctx)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	if receivedHeaders.Get("X-Custom-Header") != "custom-value" {
		t.Error("X-Custom-Header not set correctly")
	}
	if receivedHeaders.Get("X-Another") != "another-value" {
		t.Error("X-Another not set correctly")
	}
}

func TestRunnerRun_OpenLoopTiming(t *testing.T) {
	var requestTimes []time.Time
	var mu atomic.Int64
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Simulate slow response
		time.Sleep(50 * time.Millisecond)
		mu.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	interval := 20 * time.Millisecond
	cfg := Config{
		URL:           server.URL,
		Method:        "GET",
		TotalRequests: 5,
		Interval:      interval,
		Timeout:       5 * time.Second,
	}

	runner := NewRunner(cfg)
	ctx := context.Background()

	start := time.Now()
	summary, err := runner.Run(ctx)
	elapsed := time.Since(start)

	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	// With open-loop, all 5 requests should be sent in approximately 4 intervals (80ms)
	// even though each request takes 50ms (which would be 250ms with closed-loop)
	// Allow some tolerance for scheduling
	expectedSendTime := time.Duration(cfg.TotalRequests-1) * interval
	expectedTotalTime := expectedSendTime + 50*time.Millisecond // + last request latency

	// The total time should be closer to 130ms (4*20ms + 50ms) than 250ms (5*50ms)
	if elapsed > expectedTotalTime+100*time.Millisecond {
		t.Errorf("elapsed time %v suggests closed-loop behavior, expected around %v", elapsed, expectedTotalTime)
	}

	_ = requestTimes
	if summary.RequestCount != 5 {
		t.Errorf("expected 5 requests, got %d", summary.RequestCount)
	}
}

func TestRunnerRun_ContextCancellation(t *testing.T) {
	requestCount := atomic.Int64{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestCount.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	cfg := Config{
		URL:           server.URL,
		Method:        "GET",
		TotalRequests: 100, // More than we'll actually send
		Interval:      10 * time.Millisecond,
		Timeout:       5 * time.Second,
	}

	runner := NewRunner(cfg)
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	summary, err := runner.Run(ctx)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	// Should have sent fewer than 100 requests due to cancellation
	if summary.RequestCount >= 100 {
		t.Errorf("expected fewer than 100 requests due to cancellation, got %d", summary.RequestCount)
	}
	if summary.RequestCount == 0 {
		t.Error("expected at least some requests to be sent")
	}
}

func TestRunnerRun_NetworkError(t *testing.T) {
	// Use an invalid URL that will fail
	cfg := Config{
		URL:           "http://localhost:1", // Port 1 should refuse connections
		Method:        "GET",
		TotalRequests: 2,
		Interval:      10 * time.Millisecond,
		Timeout:       1 * time.Second,
	}

	runner := NewRunner(cfg)
	ctx := context.Background()

	summary, err := runner.Run(ctx)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	// All requests should be errors
	if summary.ErrorCount != 2 {
		t.Errorf("expected 2 errors, got %d", summary.ErrorCount)
	}
	if summary.SuccessCount != 0 {
		t.Errorf("expected 0 successes, got %d", summary.SuccessCount)
	}
}

func TestRunnerRun_MixedResponses(t *testing.T) {
	requestNum := atomic.Int64{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		num := requestNum.Add(1)
		if num%2 == 0 {
			w.WriteHeader(http.StatusInternalServerError)
		} else {
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer server.Close()

	cfg := Config{
		URL:           server.URL,
		Method:        "GET",
		TotalRequests: 10,
		Interval:      5 * time.Millisecond,
		Timeout:       5 * time.Second,
	}

	runner := NewRunner(cfg)
	ctx := context.Background()

	summary, err := runner.Run(ctx)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	// 5 successes (odd requests), 5 errors (even requests)
	if summary.SuccessCount != 5 {
		t.Errorf("expected 5 successes, got %d", summary.SuccessCount)
	}
	if summary.ErrorCount != 5 {
		t.Errorf("expected 5 errors, got %d", summary.ErrorCount)
	}
	if summary.ErrorRate != 0.5 {
		t.Errorf("expected error rate 0.5, got %f", summary.ErrorRate)
	}
}

func TestRunnerRun_InvalidConfig(t *testing.T) {
	cfg := Config{
		URL:           "",
		Method:        "GET",
		TotalRequests: 10,
		Interval:      10 * time.Millisecond,
		Timeout:       5 * time.Second,
	}

	runner := NewRunner(cfg)
	ctx := context.Background()

	_, err := runner.Run(ctx)
	if err == nil {
		t.Error("expected error for invalid config")
	}
}

func TestRunnerRun_HTTPMethod(t *testing.T) {
	methods := []string{"GET", "POST", "PUT", "DELETE", "PATCH"}

	for _, method := range methods {
		t.Run(method, func(t *testing.T) {
			var receivedMethod string
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				receivedMethod = r.Method
				w.WriteHeader(http.StatusNoContent)
			}))
			defer server.Close()

			cfg := Config{
				URL:           server.URL,
				Method:        method,
				TotalRequests: 1,
				Interval:      10 * time.Millisecond,
				Timeout:       5 * time.Second,
			}

			runner := NewRunner(cfg)
			ctx := context.Background()

			_, err := runner.Run(ctx)
			if err != nil {
				t.Fatalf("Run() error = %v", err)
			}

			if receivedMethod != method {
				t.Errorf("expected method %s, got %s", method, receivedMethod)
			}
		})
	}
}

func TestConfigEstimatedDuration(t *testing.T) {
	tests := []struct {
		name     string
		config   Config
		expected time.Duration
	}{
		{
			name: "10 requests at 10ms",
			config: Config{
				TotalRequests: 10,
				Interval:      10 * time.Millisecond,
			},
			expected: 90 * time.Millisecond, // (10-1) * 10ms
		},
		{
			name: "100 requests at 5ms",
			config: Config{
				TotalRequests: 100,
				Interval:      5 * time.Millisecond,
			},
			expected: 495 * time.Millisecond, // (100-1) * 5ms
		},
		{
			name: "1 request",
			config: Config{
				TotalRequests: 1,
				Interval:      10 * time.Millisecond,
			},
			expected: 0, // (1-1) * 10ms
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := tt.config.EstimatedDuration()
			if got != tt.expected {
				t.Errorf("EstimatedDuration() = %v, expected %v", got, tt.expected)
			}
		})
	}
}

func TestRunnerRun_LatencyRecording(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(10 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	cfg := Config{
		URL:           server.URL,
		Method:        "GET",
		TotalRequests: 3,
		Interval:      5 * time.Millisecond,
		Timeout:       5 * time.Second,
	}

	runner := NewRunner(cfg)
	ctx := context.Background()

	summary, err := runner.Run(ctx)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	// Latencies should be at least 10ms (the sleep duration)
	if summary.Min < 10*time.Millisecond {
		t.Errorf("expected min latency >= 10ms, got %v", summary.Min)
	}
}
