package config

import (
	"os"
	"path/filepath"

	"github.com/pelletier/go-toml/v2"
)

// AuthMode represents the authentication mode for the CLI.
type AuthMode string

const (
	AuthModeBrowser     AuthMode = "browser"
	AuthModeDeviceCode  AuthMode = "device_code"
	AuthModeCLICallback AuthMode = "cli_callback"
)

// IsValid returns true if the mode is a recognized authentication mode.
func (m AuthMode) IsValid() bool {
	switch m {
	case AuthModeBrowser, AuthModeDeviceCode, AuthModeCLICallback:
		return true
	default:
		return false
	}
}

// String returns the string representation of the AuthMode.
func (m AuthMode) String() string {
	return string(m)
}

// AuthConfig contains authentication-related configuration.
type AuthConfig struct {
	// LoginURL is the organization's translation layer login endpoint.
	// This triggers the auth flow (SAML, OIDC, etc.).
	LoginURL string `toml:"login_url,omitempty"`

	// LogoutURL is the optional server-side logout endpoint.
	LogoutURL string `toml:"logout_url,omitempty"`

	// RefreshURL is the optional token refresh endpoint.
	RefreshURL string `toml:"refresh_url,omitempty"`

	// CallbackURL is the local callback URL for OAuth/OIDC flows.
	CallbackURL string `toml:"callback_url,omitempty"`

	// Mode is the authentication mode: browser, device_code, or cli_callback.
	// Can be overridden with --mode flag.
	Mode AuthMode `toml:"mode,omitempty"`

	// AutoRefresh enables automatic token refresh before expiry.
	AutoRefresh bool `toml:"auto_refresh,omitempty"`

	// RefreshBeforeExpiry is the duration before expiry to trigger refresh (e.g., "5m").
	RefreshBeforeExpiry string `toml:"refresh_before_expiry,omitempty"`
}

// GetMode returns the configured auth mode, defaulting to "browser" if not set.
func (a *AuthConfig) GetMode() AuthMode {
	if a.Mode != "" && a.Mode.IsValid() {
		return a.Mode
	}
	return AuthModeBrowser
}

// Config represents the Aussie CLI configuration
type Config struct {
	Host   string `toml:"host"`
	ApiKey string `toml:"api_key,omitempty"`

	// Auth contains authentication configuration for IdP-based auth.
	Auth AuthConfig `toml:"auth,omitempty"`
}

// DefaultConfig returns a config with default values
func DefaultConfig() *Config {
	return &Config{
		Host: "http://localhost:1234",
	}
}

// IsAuthenticated returns true if an API key is configured
func (c *Config) IsAuthenticated() bool {
	return c.ApiKey != ""
}

// Load loads configuration from files, with the following precedence:
// 1. Local .aussierc file (in current directory)
// 2. Global ~/.aussie config file
// 3. Default values
func Load() (*Config, error) {
	cfg := DefaultConfig()

	// Try global config first (lower precedence)
	globalPath, err := GlobalConfigPath()
	if err == nil {
		if data, err := os.ReadFile(globalPath); err == nil {
			if err := toml.Unmarshal(data, cfg); err != nil {
				return nil, err
			}
		}
	}

	// Try local config (higher precedence, overwrites global)
	localPath := LocalConfigPath()
	if data, err := os.ReadFile(localPath); err == nil {
		if err := toml.Unmarshal(data, cfg); err != nil {
			return nil, err
		}
	}

	return cfg, nil
}

// LocalConfigPath returns the path to the local config file
func LocalConfigPath() string {
	return ".aussierc"
}

// GlobalConfigPath returns the path to the global config file
// Uses ~/.aussie on all platforms for consistency
func GlobalConfigPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}

	return filepath.Join(home, ".aussierc"), nil
}

// LoadFromFile loads configuration from a specific file
func LoadFromFile(path string) (*Config, error) {
	cfg := DefaultConfig()

	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	if err := toml.Unmarshal(data, cfg); err != nil {
		return nil, err
	}

	return cfg, nil
}

// Save saves the configuration to the global config file
func (c *Config) Save() error {
	path, err := GlobalConfigPath()
	if err != nil {
		return err
	}

	data, err := toml.Marshal(c)
	if err != nil {
		return err
	}

	return os.WriteFile(path, data, 0600)
}

// ClearApiKey removes the API key from the configuration and saves it
func (c *Config) ClearApiKey() error {
	c.ApiKey = ""
	return c.Save()
}
