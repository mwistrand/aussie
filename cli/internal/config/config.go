package config

import (
	"os"
	"path/filepath"
	"runtime"

	"github.com/pelletier/go-toml/v2"
)

// Config represents the Aussie CLI configuration
type Config struct {
	Host string `toml:"host"`
}

// DefaultConfig returns a config with default values
func DefaultConfig() *Config {
	return &Config{
		Host: "http://localhost:8080",
	}
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
// This works across all platforms (Unix, macOS, Windows)
func GlobalConfigPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}

	// On Windows, use .aussie in the user's home directory
	// On Unix/macOS, use .aussie in the home directory
	filename := ".aussie"
	if runtime.GOOS == "windows" {
		// On Windows, we could also use AppData, but .aussie in home is simpler
		// and matches the Unix convention
		filename = ".aussie"
	}

	return filepath.Join(home, filename), nil
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
