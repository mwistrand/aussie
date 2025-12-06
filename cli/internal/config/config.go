package config

import (
	"os"
	"path/filepath"

	"github.com/pelletier/go-toml/v2"
)

// Config represents the Aussie CLI configuration
type Config struct {
	Host   string `toml:"host"`
	ApiKey string `toml:"api_key,omitempty"`
}

// DefaultConfig returns a config with default values
func DefaultConfig() *Config {
	return &Config{
		Host: "http://localhost:8080",
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

	return filepath.Join(home, ".aussie"), nil
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
