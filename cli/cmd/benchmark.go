package cmd

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/benchmark"
	"github.com/aussie/cli/internal/config"
)

var (
	benchmarkRequests int
	benchmarkInterval time.Duration
	benchmarkURL      string
	benchmarkMethod   string
	benchmarkTimeout  time.Duration
	benchmarkOutput   string
)

var benchmarkCmd = &cobra.Command{
	Use:   "benchmark",
	Short: "Run latency benchmark through Aussie gateway",
	Long: `Run an authenticated latency benchmark through the Aussie gateway.

This command measures the latency distribution for authenticated requests
flowing through Aussie. It uses open-loop load testing to avoid coordinated
omission - requests are sent at fixed intervals regardless of response time.

The benchmark flow:
  1. Authenticate with existing session/token
  2. Send requests at fixed intervals to the target endpoint
  3. Measure latency from request start to response
  4. Report statistics (percentiles, histogram, etc.)

Prerequisites:
  - Run 'aussie login' to authenticate before benchmarking
  - Ensure the target endpoint is registered with Aussie

Examples:
  # Benchmark an endpoint with defaults (100 requests, 10ms interval)
  aussie benchmark --url http://localhost:1234/my-service/api/health

  # Custom number of requests and interval
  aussie benchmark --url http://localhost:1234/my-service/api/health -n 500 --interval 5ms

  # Output as JSON for automation
  aussie benchmark --url http://localhost:1234/my-service/api/health -o json

  # Use POST method
  aussie benchmark --url http://localhost:1234/my-service/api/ping --method POST`,
	RunE: runBenchmark,
}

func init() {
	rootCmd.AddCommand(benchmarkCmd)

	benchmarkCmd.Flags().IntVarP(&benchmarkRequests, "requests", "n", 100,
		"Total number of requests to send")
	benchmarkCmd.Flags().DurationVar(&benchmarkInterval, "interval", 10*time.Millisecond,
		"Interval between starting new requests (open-loop)")
	benchmarkCmd.Flags().StringVar(&benchmarkURL, "url", "",
		"Target URL to benchmark (required)")
	_ = benchmarkCmd.MarkFlagRequired("url")
	benchmarkCmd.Flags().StringVar(&benchmarkMethod, "method", "GET",
		"HTTP method to use")
	benchmarkCmd.Flags().DurationVar(&benchmarkTimeout, "timeout", 30*time.Second,
		"Timeout for each request")
	benchmarkCmd.Flags().StringVarP(&benchmarkOutput, "output", "o", "text",
		"Output format: text, json")
}

func runBenchmark(cmd *cobra.Command, args []string) error {
	// Load configuration
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override host with --server flag if provided
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	// Get authentication token
	token, err := auth.GetAuthToken(cfg.ApiKey)
	if err != nil {
		return fmt.Errorf("authentication required: %w\nRun 'aussie login' to authenticate", err)
	}

	// Check benchmark permission
	if err := checkBenchmarkPermission(cfg.Host, token); err != nil {
		return err
	}

	// Check token expiry
	if creds, err := auth.LoadCredentials(); err == nil {
		estimatedDuration := time.Duration(benchmarkRequests-1) * benchmarkInterval
		if creds.TimeRemaining() < estimatedDuration {
			fmt.Fprintf(os.Stderr, "Warning: Token expires in %s, benchmark may take ~%s\n",
				creds.TimeRemaining().Round(time.Second),
				estimatedDuration.Round(time.Second))
			fmt.Fprintf(os.Stderr, "Consider running 'aussie login' to refresh your token.\n\n")
		}
	}

	// Validate inputs
	if benchmarkRequests <= 0 {
		return fmt.Errorf("requests must be positive")
	}
	if benchmarkInterval <= 0 {
		return fmt.Errorf("interval must be positive")
	}

	// Validate output format
	outputFormat := strings.ToLower(benchmarkOutput)
	if outputFormat != benchmark.OutputFormatText && outputFormat != benchmark.OutputFormatJSON {
		return fmt.Errorf("invalid output format: %s (use 'text' or 'json')", benchmarkOutput)
	}

	// Create benchmark configuration
	benchCfg := benchmark.Config{
		URL:           benchmarkURL,
		Method:        strings.ToUpper(benchmarkMethod),
		TotalRequests: benchmarkRequests,
		Interval:      benchmarkInterval,
		Timeout:       benchmarkTimeout,
		AuthToken:     token,
	}

	// Print benchmark info (for text format only)
	if outputFormat == benchmark.OutputFormatText {
		estimatedDuration := benchCfg.EstimatedDuration()
		fmt.Printf("Starting benchmark...\n")
		fmt.Printf("  Target:    %s\n", benchmarkURL)
		fmt.Printf("  Method:    %s\n", benchCfg.Method)
		fmt.Printf("  Requests:  %d\n", benchmarkRequests)
		fmt.Printf("  Interval:  %s\n", benchmarkInterval)
		fmt.Printf("  Estimated: ~%s\n", estimatedDuration.Round(time.Millisecond))
		fmt.Println()
	}

	// Create runner
	runner := benchmark.NewRunner(benchCfg)

	// Set up context with cancellation
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Handle interrupt signal
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigChan
		if outputFormat == benchmark.OutputFormatText {
			fmt.Println("\nInterrupted, waiting for in-flight requests...")
		}
		cancel()
	}()

	// Run benchmark
	summary, err := runner.Run(ctx)
	if err != nil {
		return fmt.Errorf("benchmark failed: %w", err)
	}

	// Output results
	outputCfg := benchmark.OutputConfig{
		URL:    benchmarkURL,
		Method: benchCfg.Method,
		Format: outputFormat,
	}

	return benchmark.WriteOutput(os.Stdout, summary, outputCfg)
}

// checkBenchmarkPermission verifies the user has permission to run benchmarks.
// Returns nil if authorized, or an error if not.
func checkBenchmarkPermission(host, token string) error {
	authURL := fmt.Sprintf("%s/admin/benchmark/authorize", strings.TrimSuffix(host, "/"))

	req, err := http.NewRequest(http.MethodGet, authURL, nil)
	if err != nil {
		return fmt.Errorf("failed to create authorization request: %w", err)
	}

	req.Header.Set("Authorization", "Bearer "+token)

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to check benchmark permission: %w", err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusNoContent, http.StatusOK:
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed: run 'aussie login' to authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("permission denied: benchmark.run permission is required\nContact your platform team to request access")
	default:
		return fmt.Errorf("authorization check failed with status %d", resp.StatusCode)
	}
}
