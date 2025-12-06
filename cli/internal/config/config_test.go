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

func TestIsAuthenticated_WithApiKey(t *testing.T) {
	cfg := &Config{
		Host:   "http://localhost:8080",
		ApiKey: "test-api-key",
	}

	if !cfg.IsAuthenticated() {
		t.Error("IsAuthenticated() should return true when API key is set")
	}
}

func TestIsAuthenticated_WithoutApiKey(t *testing.T) {
	cfg := &Config{
		Host:   "http://localhost:8080",
		ApiKey: "",
	}

	if cfg.IsAuthenticated() {
		t.Error("IsAuthenticated() should return false when API key is empty")
	}
}

func TestIsAuthenticated_DefaultConfig(t *testing.T) {
	cfg := DefaultConfig()

	if cfg.IsAuthenticated() {
		t.Error("DefaultConfig().IsAuthenticated() should return false")
	}
}

func TestSave(t *testing.T) {
	// Create a temp home directory
	tmpDir := t.TempDir()
	origHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", origHome)

	cfg := &Config{
		Host:   "http://test-server:8080",
		ApiKey: "test-api-key-12345",
	}

	err := cfg.Save()
	if err != nil {
		t.Fatalf("Save() returned error: %v", err)
	}

	// Verify file was created
	savedPath := filepath.Join(tmpDir, ".aussie")
	if _, err := os.Stat(savedPath); os.IsNotExist(err) {
		t.Fatal("Save() did not create config file")
	}

	// Verify file permissions (should be 0600)
	info, _ := os.Stat(savedPath)
	if info.Mode().Perm() != 0600 {
		t.Errorf("Config file permissions = %o, want %o", info.Mode().Perm(), 0600)
	}

	// Verify content can be loaded back
	loadedCfg, err := LoadFromFile(savedPath)
	if err != nil {
		t.Fatalf("Failed to load saved config: %v", err)
	}

	if loadedCfg.Host != cfg.Host {
		t.Errorf("Loaded Host = %q, want %q", loadedCfg.Host, cfg.Host)
	}
	if loadedCfg.ApiKey != cfg.ApiKey {
		t.Errorf("Loaded ApiKey = %q, want %q", loadedCfg.ApiKey, cfg.ApiKey)
	}
}

func TestClearApiKey(t *testing.T) {
	// Create a temp home directory
	tmpDir := t.TempDir()
	origHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", origHome)

	cfg := &Config{
		Host:   "http://test-server:8080",
		ApiKey: "test-api-key-12345",
	}

	// Save initial config
	if err := cfg.Save(); err != nil {
		t.Fatalf("Save() returned error: %v", err)
	}

	// Clear the API key
	if err := cfg.ClearApiKey(); err != nil {
		t.Fatalf("ClearApiKey() returned error: %v", err)
	}

	// Verify in-memory state
	if cfg.ApiKey != "" {
		t.Errorf("After ClearApiKey(), ApiKey = %q, want empty", cfg.ApiKey)
	}
	if cfg.IsAuthenticated() {
		t.Error("After ClearApiKey(), IsAuthenticated() should return false")
	}

	// Verify persisted state
	savedPath := filepath.Join(tmpDir, ".aussie")
	loadedCfg, err := LoadFromFile(savedPath)
	if err != nil {
		t.Fatalf("Failed to load config after ClearApiKey: %v", err)
	}

	if loadedCfg.ApiKey != "" {
		t.Errorf("Loaded ApiKey after ClearApiKey = %q, want empty", loadedCfg.ApiKey)
	}

	// Host should be preserved
	if loadedCfg.Host != "http://test-server:8080" {
		t.Errorf("Loaded Host after ClearApiKey = %q, want %q", loadedCfg.Host, "http://test-server:8080")
	}
}

func TestLoadFromFile_WithApiKey(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "config.toml")

	content := `host = "http://example.com:9090"
api_key = "my-secret-key"`
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
	if cfg.ApiKey != "my-secret-key" {
		t.Errorf("LoadFromFile().ApiKey = %q, want %q", cfg.ApiKey, "my-secret-key")
	}
	if !cfg.IsAuthenticated() {
		t.Error("Config with API key should be authenticated")
	}
}
