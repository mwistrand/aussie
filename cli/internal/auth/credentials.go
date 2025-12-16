package auth

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// StoredCredentials represents the locally stored authentication credentials.
type StoredCredentials struct {
	// Token is the JWT token received from the IdP/translation layer.
	Token string `json:"token"`

	// ExpiresAt is when the token expires.
	ExpiresAt time.Time `json:"expires_at"`

	// Subject is the user identifier (sub claim from JWT).
	Subject string `json:"subject"`

	// Name is the user's display name.
	Name string `json:"name,omitempty"`

	// Groups are the groups the user belongs to.
	Groups []string `json:"groups"`
}

// IsExpired returns true if the credentials have expired.
func (c *StoredCredentials) IsExpired() bool {
	return time.Now().After(c.ExpiresAt)
}

// TimeRemaining returns the time remaining until expiry.
func (c *StoredCredentials) TimeRemaining() time.Duration {
	return time.Until(c.ExpiresAt)
}

// CredentialsDir returns the path to the credentials directory (~/.aussie).
func CredentialsDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("failed to get home directory: %w", err)
	}
	return filepath.Join(home, ".aussie"), nil
}

// CredentialsPath returns the path to the credentials file (~/.aussie/credentials).
func CredentialsPath() (string, error) {
	dir, err := CredentialsDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "credentials"), nil
}

// StoreCredentials saves the credentials to the local file system.
// Creates the directory if it doesn't exist and sets restrictive permissions.
func StoreCredentials(creds StoredCredentials) error {
	path, err := CredentialsPath()
	if err != nil {
		return err
	}

	// Create directory with restrictive permissions
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return fmt.Errorf("failed to create credentials directory: %w", err)
	}

	data, err := json.MarshalIndent(creds, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal credentials: %w", err)
	}

	// Write with restrictive permissions (owner read/write only)
	if err := os.WriteFile(path, data, 0600); err != nil {
		return fmt.Errorf("failed to write credentials: %w", err)
	}

	return nil
}

// LoadCredentials loads the stored credentials from the local file system.
// Returns an error if credentials don't exist, are expired, or can't be read.
func LoadCredentials() (*StoredCredentials, error) {
	path, err := CredentialsPath()
	if err != nil {
		return nil, err
	}

	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, fmt.Errorf("not authenticated: run 'aussie auth login' to authenticate")
		}
		return nil, fmt.Errorf("failed to read credentials: %w", err)
	}

	var creds StoredCredentials
	if err := json.Unmarshal(data, &creds); err != nil {
		return nil, fmt.Errorf("failed to parse credentials: %w", err)
	}

	// Check expiration
	if creds.IsExpired() {
		// Clean up expired credentials
		_ = ClearCredentials()
		return nil, fmt.Errorf("credentials expired: run 'aussie auth login' to re-authenticate")
	}

	return &creds, nil
}

// ClearCredentials removes the stored credentials file.
func ClearCredentials() error {
	path, err := CredentialsPath()
	if err != nil {
		return err
	}

	err = os.Remove(path)
	if err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to remove credentials: %w", err)
	}

	return nil
}

// HasCredentials returns true if credentials file exists.
func HasCredentials() bool {
	path, err := CredentialsPath()
	if err != nil {
		return false
	}

	_, err = os.Stat(path)
	return err == nil
}

// GetToken returns the stored token if valid, or an error if not authenticated.
func GetToken() (string, error) {
	creds, err := LoadCredentials()
	if err != nil {
		return "", err
	}
	return creds.Token, nil
}
