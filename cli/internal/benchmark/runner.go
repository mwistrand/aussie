package benchmark

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"
)

// Config contains benchmark configuration.
type Config struct {
	// URL is the target endpoint URL.
	URL string

	// Method is the HTTP method to use (GET, POST, etc.).
	Method string

	// TotalRequests is the total number of requests to send.
	TotalRequests int

	// Interval is the time between starting new requests.
	// This implements open-loop load testing to avoid coordinated omission.
	Interval time.Duration

	// Timeout is the timeout for each individual request.
	Timeout time.Duration

	// AuthToken is the bearer token for authentication.
	AuthToken string

	// Headers are additional headers to include in requests.
	Headers map[string]string
}

// Validate checks if the configuration is valid.
func (c *Config) Validate() error {
	if c.URL == "" {
		return fmt.Errorf("URL is required")
	}
	if c.Method == "" {
		return fmt.Errorf("method is required")
	}
	if c.TotalRequests <= 0 {
		return fmt.Errorf("total requests must be positive")
	}
	if c.Interval <= 0 {
		return fmt.Errorf("interval must be positive")
	}
	if c.Timeout <= 0 {
		return fmt.Errorf("timeout must be positive")
	}
	return nil
}

// RequestResult represents the outcome of a single request.
type RequestResult struct {
	StartTime  time.Time
	Latency    time.Duration
	StatusCode int
	Error      error
}

// IsSuccess returns true if the request completed successfully (2xx status).
func (r *RequestResult) IsSuccess() bool {
	return r.Error == nil && r.StatusCode >= 200 && r.StatusCode < 300
}

// Runner executes the benchmark with open-loop load generation.
type Runner struct {
	config Config
	client *http.Client
	stats  *Stats
}

// NewRunner creates a new benchmark runner.
func NewRunner(cfg Config) *Runner {
	return &Runner{
		config: cfg,
		client: &http.Client{
			Timeout: cfg.Timeout,
			Transport: &http.Transport{
				MaxIdleConns:        100,
				MaxIdleConnsPerHost: 100,
				IdleConnTimeout:     90 * time.Second,
			},
		},
		stats: NewStats(),
	}
}

// Run executes the benchmark and returns statistics.
// It uses open-loop load testing: new requests are started at fixed intervals
// regardless of whether previous requests have completed.
func (r *Runner) Run(ctx context.Context) (*StatsSummary, error) {
	if err := r.config.Validate(); err != nil {
		return nil, err
	}

	var wg sync.WaitGroup
	resultChan := make(chan RequestResult, r.config.TotalRequests)

	// Use ticker for fixed-interval request scheduling
	ticker := time.NewTicker(r.config.Interval)
	defer ticker.Stop()

	requestsSent := 0

	// Send first request immediately
	wg.Add(1)
	go func() {
		defer wg.Done()
		result := r.sendRequest(ctx)
		resultChan <- result
	}()
	requestsSent++

	// Schedule remaining requests at fixed intervals
	for requestsSent < r.config.TotalRequests {
		select {
		case <-ctx.Done():
			goto collectResults
		case <-ticker.C:
			wg.Add(1)
			go func() {
				defer wg.Done()
				result := r.sendRequest(ctx)
				resultChan <- result
			}()
			requestsSent++
		}
	}

collectResults:
	// Wait for all in-flight requests to complete
	go func() {
		wg.Wait()
		close(resultChan)
	}()

	// Collect results
	for result := range resultChan {
		if result.IsSuccess() {
			r.stats.RecordSuccess(result.Latency)
		} else {
			r.stats.RecordError(result.Latency)
		}
	}

	r.stats.Finalize()
	summary := r.stats.Summary()
	return &summary, nil
}

// sendRequest sends a single HTTP request and records the result.
func (r *Runner) sendRequest(ctx context.Context) RequestResult {
	result := RequestResult{
		StartTime: time.Now(),
	}

	req, err := http.NewRequestWithContext(ctx, r.config.Method, r.config.URL, nil)
	if err != nil {
		result.Error = err
		result.Latency = time.Since(result.StartTime)
		return result
	}

	// Set authentication header
	if r.config.AuthToken != "" {
		req.Header.Set("Authorization", "Bearer "+r.config.AuthToken)
	}

	// Set additional headers
	for key, value := range r.config.Headers {
		req.Header.Set(key, value)
	}

	resp, err := r.client.Do(req)
	result.Latency = time.Since(result.StartTime)

	if err != nil {
		result.Error = err
		return result
	}
	defer resp.Body.Close()

	// Drain the body to allow connection reuse
	_, _ = io.Copy(io.Discard, resp.Body)

	result.StatusCode = resp.StatusCode
	return result
}

// EstimatedDuration returns the estimated total duration of the benchmark.
func (c *Config) EstimatedDuration() time.Duration {
	// The benchmark takes (N-1) intervals since first request is immediate
	return time.Duration(c.TotalRequests-1) * c.Interval
}
