package auth

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// StoredCredentials represents the locally stored authentication credentials.
type StoredCredentials struct {
	// Token is the JWT token received from the IdP/translation layer.
	Token string `json:"token"`

	// ServerOrigin is the exact origin this credential may authenticate to.
	ServerOrigin string `json:"server_origin"`

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

type storedAPIKey struct {
	ServerOrigin string `json:"server_origin"`
	APIKey       string `json:"api_key"`
}

func apiKeyPath(origin string) (string, error) {
	dir, err := CredentialsDir()
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256([]byte(origin))
	return filepath.Join(dir, "api-keys", fmt.Sprintf("%x.json", digest)), nil
}

// StoreAPIKey stores a legacy API key in the private, origin-bound credential area.
func StoreAPIKey(apiKey, host string) error {
	if apiKey == "" {
		return fmt.Errorf("API key must not be empty")
	}
	origin, err := CanonicalOrigin(host)
	if err != nil {
		return err
	}
	path, err := apiKeyPath(origin)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return fmt.Errorf("failed to create API key directory: %w", err)
	}
	data, err := json.Marshal(storedAPIKey{ServerOrigin: origin, APIKey: apiKey})
	if err != nil {
		return fmt.Errorf("failed to marshal API key: %w", err)
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".api-key-*")
	if err != nil {
		return fmt.Errorf("failed to create API key file: %w", err)
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)
	if err := tmp.Chmod(0600); err != nil {
		tmp.Close()
		return fmt.Errorf("failed to secure API key file: %w", err)
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return fmt.Errorf("failed to write API key: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("failed to close API key file: %w", err)
	}
	if err := os.Rename(tmpPath, path); err != nil {
		return fmt.Errorf("failed to install API key: %w", err)
	}
	return nil
}

func loadAPIKey(host string) (string, error) {
	origin, err := CanonicalOrigin(host)
	if err != nil {
		return "", err
	}
	path, err := apiKeyPath(origin)
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	var stored storedAPIKey
	if err := json.Unmarshal(data, &stored); err != nil {
		return "", fmt.Errorf("failed to parse API key: %w", err)
	}
	if stored.ServerOrigin != origin || stored.APIKey == "" {
		return "", fmt.Errorf("stored API key is not bound to this server")
	}
	return stored.APIKey, nil
}

// HasAPIKeyForHost reports whether a private API key is stored for the origin.
func HasAPIKeyForHost(host string) bool {
	_, err := loadAPIKey(host)
	return err == nil
}

// ClearAPIKey removes the origin-bound API key, if present.
func ClearAPIKey(host string) error {
	origin, err := CanonicalOrigin(host)
	if err != nil {
		return err
	}
	path, err := apiKeyPath(origin)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to remove API key: %w", err)
	}
	return nil
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

	// Rename a private temporary file so a repository cannot redirect writes through a symlink.
	tmp, err := os.CreateTemp(dir, ".credentials-*")
	if err != nil {
		return fmt.Errorf("failed to create credentials file: %w", err)
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)
	if err := tmp.Chmod(0600); err != nil {
		tmp.Close()
		return fmt.Errorf("failed to secure credentials file: %w", err)
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return fmt.Errorf("failed to write credentials: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("failed to close credentials file: %w", err)
	}
	if err := os.Rename(tmpPath, path); err != nil {
		return fmt.Errorf("failed to install credentials: %w", err)
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
			return nil, fmt.Errorf("not authenticated: run 'aussie login' to authenticate")
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
		return nil, fmt.Errorf("credentials expired: run 'aussie login' to re-authenticate")
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

// CanonicalOrigin returns the origin to which a credential may be sent.
func CanonicalOrigin(raw string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme == "" || u.Hostname() == "" || u.User != nil || u.Path != "" && u.Path != "/" || u.RawQuery != "" || u.Fragment != "" {
		return "", fmt.Errorf("server must be an origin URL")
	}
	host := strings.ToLower(u.Hostname())
	if u.Scheme != "https" && !(u.Scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "::1")) {
		return "", fmt.Errorf("server credentials require HTTPS (HTTP is allowed only for localhost)")
	}
	port := u.Port()
	if (u.Scheme == "https" && port == "443") || (u.Scheme == "http" && port == "80") {
		port = ""
	}
	if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	if port != "" {
		host += ":" + port
	}
	return strings.ToLower(u.Scheme) + "://" + host, nil
}

// GetAuthTokenForHost refuses stored credentials unless their origin matches exactly.
func GetAuthTokenForHost(apiKey, host string) (string, error) {
	origin, err := CanonicalOrigin(host)
	if err != nil {
		return "", err
	}
	creds, credsErr := LoadCredentials()
	if credsErr == nil {
		if creds.ServerOrigin != origin {
			return "", fmt.Errorf("stored credentials belong to a different server; run 'aussie login'")
		}
		if apiKey != "" {
			if err := StoreAPIKey(apiKey, host); err != nil {
				return "", err
			}
		}
		return creds.Token, nil
	}
	if apiKey != "" {
		if err := StoreAPIKey(apiKey, host); err != nil {
			return "", err
		}
		return apiKey, nil
	}
	if stored, err := loadAPIKey(host); err == nil {
		return stored, nil
	}
	return "", fmt.Errorf("not authenticated: run 'aussie login' to authenticate")
}
