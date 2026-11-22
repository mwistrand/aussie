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

	// TokenURL is the OAuth authorization-code exchange endpoint.
	TokenURL string `toml:"token_url,omitempty"`

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

	globalHost string
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

// TrustedHost returns the host selected by the trusted global configuration.
func (c *Config) TrustedHost() string {
	return c.globalHost
}

// Load loads global credentials and project-local non-secret configuration.
func Load() (*Config, error) {
	cfg := DefaultConfig()

	// Try global config first (lower precedence)
	globalPath, err := GlobalConfigPath()
	if err == nil {
		if data, err := os.ReadFile(globalPath); err == nil {
			if err := toml.Unmarshal(data, cfg); err != nil {
				return nil, err
			}
			cfg.globalHost = cfg.Host
		}
	}

	// Project files may select the server and UX, but never credential destinations.
	localPath := LocalConfigPath()
	if data, err := os.ReadFile(localPath); err == nil {
		apiKey := cfg.ApiKey
		apiKeyHost := cfg.Host
		var project projectConfig
		if err := toml.Unmarshal(data, &project); err != nil {
			return nil, err
		}
		if project.Host != "" {
			cfg.Host = project.Host
		}
		if project.Auth.Mode != "" {
			cfg.Auth.Mode = project.Auth.Mode
		}
		if cfg.Host == apiKeyHost {
			cfg.ApiKey = apiKey
		} else {
			cfg.ApiKey = ""
		}
	}

	return cfg, nil
}

// projectConfig is the subset that an untrusted checkout may control.
type projectConfig struct {
	Host string `toml:"host"`
	Auth struct {
		Mode AuthMode `toml:"mode,omitempty"`
	} `toml:"auth,omitempty"`
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

	data, err := toml.Marshal(persistedConfig{Host: c.Host, Auth: c.Auth})
	if err != nil {
		return err
	}

	return os.WriteFile(path, data, 0600)
}

type persistedConfig struct {
	Host string     `toml:"host"`
	Auth AuthConfig `toml:"auth,omitempty"`
}

// ClearApiKey removes the API key from the configuration and saves it
func (c *Config) ClearApiKey() error {
	globalPath, err := GlobalConfigPath()
	if err != nil {
		return err
	}

	globalCfg, err := LoadFromFile(globalPath)
	if err == nil {
		if err := globalCfg.Save(); err != nil {
			return err
		}
	} else if os.IsNotExist(err) {
		if err := c.Save(); err != nil {
			return err
		}
	} else {
		return err
	}

	c.ApiKey = ""
	return nil
}
