package cmd

import (
	"bytes"
	"testing"
	"time"
)

func TestBenchmarkCmd_Initialized(t *testing.T) {
	if benchmarkCmd == nil {
		t.Fatal("benchmarkCmd is nil")
	}
	if benchmarkCmd.Use != "benchmark" {
		t.Errorf("expected Use 'benchmark', got %s", benchmarkCmd.Use)
	}
	if benchmarkCmd.RunE == nil {
		t.Error("RunE not set")
	}
}

func TestBenchmarkCmd_HasAllFlags(t *testing.T) {
	flags := []struct {
		name      string
		shorthand string
	}{
		{"requests", "n"},
		{"interval", ""},
		{"url", ""},
		{"method", ""},
		{"timeout", ""},
		{"output", "o"},
	}

	for _, f := range flags {
		t.Run(f.name, func(t *testing.T) {
			flag := benchmarkCmd.Flags().Lookup(f.name)
			if flag == nil {
				t.Errorf("flag %s not found", f.name)
				return
			}
			if f.shorthand != "" && flag.Shorthand != f.shorthand {
				t.Errorf("expected shorthand %s, got %s", f.shorthand, flag.Shorthand)
			}
		})
	}
}

func TestBenchmarkCmd_DefaultValues(t *testing.T) {
	// Check default values
	if benchmarkRequests != 100 {
		t.Errorf("expected default requests 100, got %d", benchmarkRequests)
	}
	if benchmarkInterval != 10*time.Millisecond {
		t.Errorf("expected default interval 10ms, got %v", benchmarkInterval)
	}
	if benchmarkMethod != "GET" {
		t.Errorf("expected default method GET, got %s", benchmarkMethod)
	}
	if benchmarkTimeout != 30*time.Second {
		t.Errorf("expected default timeout 30s, got %v", benchmarkTimeout)
	}
	if benchmarkOutput != "text" {
		t.Errorf("expected default output 'text', got %s", benchmarkOutput)
	}
}

func TestBenchmarkCmd_FlagDescriptions(t *testing.T) {
	flags := []string{"requests", "interval", "url", "method", "timeout", "output"}

	for _, name := range flags {
		t.Run(name, func(t *testing.T) {
			flag := benchmarkCmd.Flags().Lookup(name)
			if flag == nil {
				t.Fatalf("flag %s not found", name)
			}
			if flag.Usage == "" {
				t.Errorf("flag %s has no usage description", name)
			}
		})
	}
}

func TestBenchmarkCmd_IsSubcommand(t *testing.T) {
	// Verify benchmark is a subcommand of root
	found := false
	for _, cmd := range rootCmd.Commands() {
		if cmd == benchmarkCmd {
			found = true
			break
		}
	}
	if !found {
		t.Error("benchmark command not added to root")
	}
}

func TestBenchmarkCmd_ShortHelp(t *testing.T) {
	if benchmarkCmd.Short == "" {
		t.Error("short help is empty")
	}
	if len(benchmarkCmd.Short) > 80 {
		t.Error("short help should be brief (< 80 chars)")
	}
}

func TestBenchmarkCmd_LongHelp(t *testing.T) {
	if benchmarkCmd.Long == "" {
		t.Error("long help is empty")
	}
	if !bytes.Contains([]byte(benchmarkCmd.Long), []byte("latency")) {
		t.Error("long help should mention 'latency'")
	}
	if !bytes.Contains([]byte(benchmarkCmd.Long), []byte("open-loop")) {
		t.Error("long help should mention 'open-loop'")
	}
}

func TestBenchmarkCmd_Examples(t *testing.T) {
	if benchmarkCmd.Long == "" {
		t.Skip("no long help to check for examples")
	}
	if !bytes.Contains([]byte(benchmarkCmd.Long), []byte("Examples:")) {
		t.Error("long help should include examples")
	}
}
