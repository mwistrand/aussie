package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDefaultConfig(t *testing.T) {
	cfg := DefaultConfig()

	if cfg == nil {
		t.Fatal("DefaultConfig() returned nil")
	}

	if cfg.Host != "http://localhost:8080" {
		t.Errorf("DefaultConfig().Host = %q, want %q", cfg.Host, "http://localhost:8080")
	}
}

func TestLocalConfigPath(t *testing.T) {
	path := LocalConfigPath()

	if path != ".aussierc" {
		t.Errorf("LocalConfigPath() = %q, want %q", path, ".aussierc")
	}
}

func TestGlobalConfigPath(t *testing.T) {
	path, err := GlobalConfigPath()

	if err != nil {
		t.Fatalf("GlobalConfigPath() returned error: %v", err)
	}

	home, _ := os.UserHomeDir()
	expected := filepath.Join(home, ".aussie")

	if path != expected {
		t.Errorf("GlobalConfigPath() = %q, want %q", path, expected)
	}
}

func TestLoadFromFile_ValidConfig(t *testing.T) {
	// Create a temporary config file
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "config.toml")

	content := `host = "http://example.com:9090"`
	if err := os.WriteFile(configPath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}

	cfg, err := LoadFromFile(configPath)

	if err != nil {
		t.Fatalf("LoadFromFile() returned error: %v", err)
	}

	if cfg.Host != "http://example.com:9090" {
		t.Errorf("LoadFromFile().Host = %q, want %q", cfg.Host, "http://example.com:9090")
	}
}

func TestLoadFromFile_NonExistent(t *testing.T) {
	_, err := LoadFromFile("/nonexistent/path/config.toml")

	if err == nil {
		t.Error("LoadFromFile() should return error for non-existent file")
	}
}

func TestLoadFromFile_InvalidTOML(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "invalid.toml")

	// Write invalid TOML content
	content := `host = "unclosed string`
	if err := os.WriteFile(configPath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}

	_, err := LoadFromFile(configPath)

	if err == nil {
		t.Error("LoadFromFile() should return error for invalid TOML")
	}
}

func TestLoadFromFile_EmptyFile(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "empty.toml")

	// Write empty file
	if err := os.WriteFile(configPath, []byte(""), 0644); err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}

	cfg, err := LoadFromFile(configPath)

	if err != nil {
		t.Fatalf("LoadFromFile() returned error: %v", err)
	}

	// Should use default values
	if cfg.Host != "http://localhost:8080" {
		t.Errorf("LoadFromFile().Host = %q, want default %q", cfg.Host, "http://localhost:8080")
	}
}

func TestLoad_NoConfigFiles(t *testing.T) {
	// Save current directory
	origDir, _ := os.Getwd()
	tmpDir := t.TempDir()
	os.Chdir(tmpDir)
	defer os.Chdir(origDir)

	cfg, err := Load()

	if err != nil {
		t.Fatalf("Load() returned error: %v", err)
	}

	// Should use default values when no config files exist
	if cfg.Host != "http://localhost:8080" {
		t.Errorf("Load().Host = %q, want default %q", cfg.Host, "http://localhost:8080")
	}
}

func TestLoad_LocalConfigOverridesGlobal(t *testing.T) {
	// Save current directory
	origDir, _ := os.Getwd()
	tmpDir := t.TempDir()
	os.Chdir(tmpDir)
	defer os.Chdir(origDir)

	// Create local config
	localContent := `host = "http://local:8080"`
	if err := os.WriteFile(".aussierc", []byte(localContent), 0644); err != nil {
		t.Fatalf("Failed to write local config: %v", err)
	}

	cfg, err := Load()

	if err != nil {
		t.Fatalf("Load() returned error: %v", err)
	}

	if cfg.Host != "http://local:8080" {
		t.Errorf("Load().Host = %q, want %q", cfg.Host, "http://local:8080")
	}
}

func TestConfig_PartialOverride(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "partial.toml")

	// Write config that only sets host
	content := `host = "http://custom:3000"`
	if err := os.WriteFile(configPath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}

	cfg, err := LoadFromFile(configPath)

	if err != nil {
		t.Fatalf("LoadFromFile() returned error: %v", err)
	}

	if cfg.Host != "http://custom:3000" {
		t.Errorf("LoadFromFile().Host = %q, want %q", cfg.Host, "http://custom:3000")
	}
}
