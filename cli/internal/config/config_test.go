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
	expected := filepath.Join(home, ".aussierc")

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
	// Save current directory and HOME
	origDir, _ := os.Getwd()
	origHome := os.Getenv("HOME")
	tmpDir := t.TempDir()
	os.Chdir(tmpDir)
	os.Setenv("HOME", tmpDir)
	defer func() {
		os.Chdir(origDir)
		os.Setenv("HOME", origHome)
	}()

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
	// Save current directory and HOME
	origDir, _ := os.Getwd()
	origHome := os.Getenv("HOME")
	tmpDir := t.TempDir()
	os.Chdir(tmpDir)
	os.Setenv("HOME", tmpDir)
	defer func() {
		os.Chdir(origDir)
		os.Setenv("HOME", origHome)
	}()

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
	savedPath := filepath.Join(tmpDir, ".aussierc")
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
	savedPath := filepath.Join(tmpDir, ".aussierc")
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

// AuthMode tests

func TestAuthMode_IsValid(t *testing.T) {
	tests := []struct {
		mode AuthMode
		want bool
	}{
		{AuthModeBrowser, true},
		{AuthModeDeviceCode, true},
		{AuthModeCLICallback, true},
		{AuthMode(""), false},
		{AuthMode("invalid"), false},
		{AuthMode("BROWSER"), false}, // Case sensitive
	}

	for _, tt := range tests {
		t.Run(string(tt.mode), func(t *testing.T) {
			if got := tt.mode.IsValid(); got != tt.want {
				t.Errorf("AuthMode(%q).IsValid() = %v, want %v", tt.mode, got, tt.want)
			}
		})
	}
}

func TestAuthMode_String(t *testing.T) {
	tests := []struct {
		mode AuthMode
		want string
	}{
		{AuthModeBrowser, "browser"},
		{AuthModeDeviceCode, "device_code"},
		{AuthModeCLICallback, "cli_callback"},
	}

	for _, tt := range tests {
		t.Run(tt.want, func(t *testing.T) {
			if got := tt.mode.String(); got != tt.want {
				t.Errorf("AuthMode.String() = %q, want %q", got, tt.want)
			}
		})
	}
}

// AuthConfig tests

func TestAuthConfig_GetMode_Default(t *testing.T) {
	cfg := AuthConfig{}

	if got := cfg.GetMode(); got != AuthModeBrowser {
		t.Errorf("AuthConfig{}.GetMode() = %q, want %q", got, AuthModeBrowser)
	}
}

func TestAuthConfig_GetMode_Configured(t *testing.T) {
	tests := []struct {
		name string
		mode AuthMode
		want AuthMode
	}{
		{"browser", AuthModeBrowser, AuthModeBrowser},
		{"device_code", AuthModeDeviceCode, AuthModeDeviceCode},
		{"cli_callback", AuthModeCLICallback, AuthModeCLICallback},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := AuthConfig{Mode: tt.mode}
			if got := cfg.GetMode(); got != tt.want {
				t.Errorf("AuthConfig{Mode: %q}.GetMode() = %q, want %q", tt.mode, got, tt.want)
			}
		})
	}
}

func TestAuthConfig_GetMode_InvalidFallsBackToDefault(t *testing.T) {
	cfg := AuthConfig{Mode: AuthMode("invalid")}

	if got := cfg.GetMode(); got != AuthModeBrowser {
		t.Errorf("AuthConfig{Mode: \"invalid\"}.GetMode() = %q, want %q (default)", got, AuthModeBrowser)
	}
}

// Config with Auth section tests

func TestLoadFromFile_WithAuthConfig(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "config.toml")

	content := `host = "http://example.com:9090"

[auth]
login_url = "https://sso.example.com/auth/login"
logout_url = "https://sso.example.com/auth/logout"
refresh_url = "https://sso.example.com/auth/refresh"
mode = "device_code"
auto_refresh = true
refresh_before_expiry = "5m"`

	if err := os.WriteFile(configPath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}

	cfg, err := LoadFromFile(configPath)
	if err != nil {
		t.Fatalf("LoadFromFile() returned error: %v", err)
	}

	// Check auth config
	if cfg.Auth.LoginURL != "https://sso.example.com/auth/login" {
		t.Errorf("Auth.LoginURL = %q, want %q", cfg.Auth.LoginURL, "https://sso.example.com/auth/login")
	}
	if cfg.Auth.LogoutURL != "https://sso.example.com/auth/logout" {
		t.Errorf("Auth.LogoutURL = %q, want %q", cfg.Auth.LogoutURL, "https://sso.example.com/auth/logout")
	}
	if cfg.Auth.RefreshURL != "https://sso.example.com/auth/refresh" {
		t.Errorf("Auth.RefreshURL = %q, want %q", cfg.Auth.RefreshURL, "https://sso.example.com/auth/refresh")
	}
	if cfg.Auth.Mode != AuthModeDeviceCode {
		t.Errorf("Auth.Mode = %q, want %q", cfg.Auth.Mode, AuthModeDeviceCode)
	}
	if !cfg.Auth.AutoRefresh {
		t.Error("Auth.AutoRefresh = false, want true")
	}
	if cfg.Auth.RefreshBeforeExpiry != "5m" {
		t.Errorf("Auth.RefreshBeforeExpiry = %q, want %q", cfg.Auth.RefreshBeforeExpiry, "5m")
	}
}

func TestLoadFromFile_WithAuthConfig_BrowserMode(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "config.toml")

	content := `host = "http://example.com:9090"

[auth]
login_url = "https://sso.example.com/auth/login"
mode = "browser"`

	if err := os.WriteFile(configPath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}

	cfg, err := LoadFromFile(configPath)
	if err != nil {
		t.Fatalf("LoadFromFile() returned error: %v", err)
	}

	if cfg.Auth.GetMode() != AuthModeBrowser {
		t.Errorf("Auth.GetMode() = %q, want %q", cfg.Auth.GetMode(), AuthModeBrowser)
	}
}

func TestLoadFromFile_WithAuthConfig_CLICallbackMode(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "config.toml")

	content := `host = "http://example.com:9090"

[auth]
login_url = "https://sso.example.com/auth/login"
mode = "cli_callback"`

	if err := os.WriteFile(configPath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}

	cfg, err := LoadFromFile(configPath)
	if err != nil {
		t.Fatalf("LoadFromFile() returned error: %v", err)
	}

	if cfg.Auth.GetMode() != AuthModeCLICallback {
		t.Errorf("Auth.GetMode() = %q, want %q", cfg.Auth.GetMode(), AuthModeCLICallback)
	}
}

func TestLoadFromFile_WithAuthConfig_NoMode(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "config.toml")

	content := `host = "http://example.com:9090"

[auth]
login_url = "https://sso.example.com/auth/login"`

	if err := os.WriteFile(configPath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}

	cfg, err := LoadFromFile(configPath)
	if err != nil {
		t.Fatalf("LoadFromFile() returned error: %v", err)
	}

	// Should default to browser mode
	if cfg.Auth.GetMode() != AuthModeBrowser {
		t.Errorf("Auth.GetMode() = %q, want %q (default)", cfg.Auth.GetMode(), AuthModeBrowser)
	}
}

func TestSave_WithAuthConfig(t *testing.T) {
	tmpDir := t.TempDir()
	origHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", origHome)

	cfg := &Config{
		Host: "http://test-server:8080",
		Auth: AuthConfig{
			LoginURL:    "https://sso.example.com/login",
			LogoutURL:   "https://sso.example.com/logout",
			Mode:        AuthModeDeviceCode,
			AutoRefresh: true,
		},
	}

	if err := cfg.Save(); err != nil {
		t.Fatalf("Save() returned error: %v", err)
	}

	// Load and verify
	savedPath := filepath.Join(tmpDir, ".aussierc")
	loadedCfg, err := LoadFromFile(savedPath)
	if err != nil {
		t.Fatalf("Failed to load saved config: %v", err)
	}

	if loadedCfg.Auth.LoginURL != cfg.Auth.LoginURL {
		t.Errorf("Loaded Auth.LoginURL = %q, want %q", loadedCfg.Auth.LoginURL, cfg.Auth.LoginURL)
	}
	if loadedCfg.Auth.LogoutURL != cfg.Auth.LogoutURL {
		t.Errorf("Loaded Auth.LogoutURL = %q, want %q", loadedCfg.Auth.LogoutURL, cfg.Auth.LogoutURL)
	}
	if loadedCfg.Auth.Mode != cfg.Auth.Mode {
		t.Errorf("Loaded Auth.Mode = %q, want %q", loadedCfg.Auth.Mode, cfg.Auth.Mode)
	}
	if loadedCfg.Auth.AutoRefresh != cfg.Auth.AutoRefresh {
		t.Errorf("Loaded Auth.AutoRefresh = %v, want %v", loadedCfg.Auth.AutoRefresh, cfg.Auth.AutoRefresh)
	}
}

func TestLoad_LocalConfigOverridesGlobalAuth(t *testing.T) {
	origDir, _ := os.Getwd()
	origHome := os.Getenv("HOME")

	// Create separate directories for home and working directory
	homeDir := t.TempDir()
	workDir := t.TempDir()

	os.Setenv("HOME", homeDir)
	os.Chdir(workDir)
	defer func() {
		os.Chdir(origDir)
		os.Setenv("HOME", origHome)
	}()

	// Create global config in home directory
	globalContent := `host = "http://global:8080"

[auth]
login_url = "https://global.example.com/login"
mode = "browser"`
	if err := os.WriteFile(filepath.Join(homeDir, ".aussierc"), []byte(globalContent), 0644); err != nil {
		t.Fatalf("Failed to write global config: %v", err)
	}

	// Create local config in working directory that overrides the auth section
	localContent := `[auth]
login_url = "https://local.example.com/login"
mode = "device_code"`
	if err := os.WriteFile(filepath.Join(workDir, ".aussierc"), []byte(localContent), 0644); err != nil {
		t.Fatalf("Failed to write local config: %v", err)
	}

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() returned error: %v", err)
	}

	// Local mode should override global
	if cfg.Auth.GetMode() != AuthModeDeviceCode {
		t.Errorf("Auth.GetMode() = %q, want %q (local override)", cfg.Auth.GetMode(), AuthModeDeviceCode)
	}

	// Local login_url should override global (entire section is replaced)
	if cfg.Auth.LoginURL != "https://local.example.com/login" {
		t.Errorf("Auth.LoginURL = %q, want %q (from local)", cfg.Auth.LoginURL, "https://local.example.com/login")
	}

	// Host should still be from global since local doesn't override it
	if cfg.Host != "http://global:8080" {
		t.Errorf("Host = %q, want %q (from global)", cfg.Host, "http://global:8080")
	}
}
