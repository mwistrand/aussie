package auth

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestStoredCredentials_IsExpired(t *testing.T) {
	tests := []struct {
		name      string
		expiresAt time.Time
		want      bool
	}{
		{
			name:      "not expired",
			expiresAt: time.Now().Add(time.Hour),
			want:      false,
		},
		{
			name:      "expired",
			expiresAt: time.Now().Add(-time.Hour),
			want:      true,
		},
		{
			name:      "just expired",
			expiresAt: time.Now().Add(-time.Second),
			want:      true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			creds := StoredCredentials{
				Token:     "test-token",
				ExpiresAt: tt.expiresAt,
				Subject:   "test@example.com",
			}

			if got := creds.IsExpired(); got != tt.want {
				t.Errorf("IsExpired() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestStoredCredentials_TimeRemaining(t *testing.T) {
	expiresAt := time.Now().Add(time.Hour)
	creds := StoredCredentials{
		Token:     "test-token",
		ExpiresAt: expiresAt,
		Subject:   "test@example.com",
	}

	remaining := creds.TimeRemaining()

	// Should be close to 1 hour (within a few seconds)
	if remaining < 59*time.Minute || remaining > 61*time.Minute {
		t.Errorf("TimeRemaining() = %v, want ~1 hour", remaining)
	}
}

func TestStoreAndLoadCredentials(t *testing.T) {
	// Create a temporary directory for testing
	tmpDir, err := os.MkdirTemp("", "aussie-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// Override home directory for testing
	originalHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", originalHome)

	// Create test credentials
	expiresAt := time.Now().Add(time.Hour).Truncate(time.Second)
	creds := StoredCredentials{
		Token:     "test-jwt-token",
		ExpiresAt: expiresAt,
		Subject:   "user@example.com",
		Name:      "Test User",
		Groups:    []string{"developers", "admins"},
	}

	// Store credentials
	if err := StoreCredentials(creds); err != nil {
		t.Fatalf("StoreCredentials() error = %v", err)
	}

	// Verify file was created with correct permissions
	credPath := filepath.Join(tmpDir, ".aussie", "credentials")
	info, err := os.Stat(credPath)
	if err != nil {
		t.Fatalf("credentials file not created: %v", err)
	}

	// Check file permissions (should be 0600)
	if mode := info.Mode().Perm(); mode != 0600 {
		t.Errorf("credentials file mode = %o, want 0600", mode)
	}

	// Load credentials
	loaded, err := LoadCredentials()
	if err != nil {
		t.Fatalf("LoadCredentials() error = %v", err)
	}

	// Verify loaded data matches
	if loaded.Token != creds.Token {
		t.Errorf("Token = %q, want %q", loaded.Token, creds.Token)
	}
	if loaded.Subject != creds.Subject {
		t.Errorf("Subject = %q, want %q", loaded.Subject, creds.Subject)
	}
	if loaded.Name != creds.Name {
		t.Errorf("Name = %q, want %q", loaded.Name, creds.Name)
	}
	if !loaded.ExpiresAt.Equal(creds.ExpiresAt) {
		t.Errorf("ExpiresAt = %v, want %v", loaded.ExpiresAt, creds.ExpiresAt)
	}
	if len(loaded.Groups) != len(creds.Groups) {
		t.Errorf("Groups length = %d, want %d", len(loaded.Groups), len(creds.Groups))
	}
}

func TestLoadCredentials_NotExists(t *testing.T) {
	// Create a temporary directory for testing
	tmpDir, err := os.MkdirTemp("", "aussie-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// Override home directory for testing
	originalHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", originalHome)

	// Try to load non-existent credentials
	_, err = LoadCredentials()
	if err == nil {
		t.Error("LoadCredentials() should return error for non-existent credentials")
	}
}

func TestLoadCredentials_Expired(t *testing.T) {
	// Create a temporary directory for testing
	tmpDir, err := os.MkdirTemp("", "aussie-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// Override home directory for testing
	originalHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", originalHome)

	// Store expired credentials
	creds := StoredCredentials{
		Token:     "expired-token",
		ExpiresAt: time.Now().Add(-time.Hour), // Expired 1 hour ago
		Subject:   "user@example.com",
	}

	if err := StoreCredentials(creds); err != nil {
		t.Fatalf("StoreCredentials() error = %v", err)
	}

	// Try to load expired credentials
	_, err = LoadCredentials()
	if err == nil {
		t.Error("LoadCredentials() should return error for expired credentials")
	}

	// Verify credentials file was cleaned up
	credPath := filepath.Join(tmpDir, ".aussie", "credentials")
	if _, err := os.Stat(credPath); !os.IsNotExist(err) {
		t.Error("expired credentials file should be deleted")
	}
}

func TestClearCredentials(t *testing.T) {
	// Create a temporary directory for testing
	tmpDir, err := os.MkdirTemp("", "aussie-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// Override home directory for testing
	originalHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", originalHome)

	// Store credentials
	creds := StoredCredentials{
		Token:     "test-token",
		ExpiresAt: time.Now().Add(time.Hour),
		Subject:   "user@example.com",
	}

	if err := StoreCredentials(creds); err != nil {
		t.Fatalf("StoreCredentials() error = %v", err)
	}

	// Clear credentials
	if err := ClearCredentials(); err != nil {
		t.Fatalf("ClearCredentials() error = %v", err)
	}

	// Verify file was removed
	credPath := filepath.Join(tmpDir, ".aussie", "credentials")
	if _, err := os.Stat(credPath); !os.IsNotExist(err) {
		t.Error("credentials file should be deleted after ClearCredentials()")
	}
}

func TestClearCredentials_NotExists(t *testing.T) {
	// Create a temporary directory for testing
	tmpDir, err := os.MkdirTemp("", "aussie-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// Override home directory for testing
	originalHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", originalHome)

	// Clear non-existent credentials should not error
	if err := ClearCredentials(); err != nil {
		t.Errorf("ClearCredentials() error = %v, want nil", err)
	}
}

func TestHasCredentials(t *testing.T) {
	// Create a temporary directory for testing
	tmpDir, err := os.MkdirTemp("", "aussie-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// Override home directory for testing
	originalHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", originalHome)

	// Should be false initially
	if HasCredentials() {
		t.Error("HasCredentials() = true, want false")
	}

	// Store credentials
	creds := StoredCredentials{
		Token:     "test-token",
		ExpiresAt: time.Now().Add(time.Hour),
		Subject:   "user@example.com",
	}

	if err := StoreCredentials(creds); err != nil {
		t.Fatalf("StoreCredentials() error = %v", err)
	}

	// Should be true after storing
	if !HasCredentials() {
		t.Error("HasCredentials() = false, want true")
	}

	// Clear credentials
	if err := ClearCredentials(); err != nil {
		t.Fatalf("ClearCredentials() error = %v", err)
	}

	// Should be false after clearing
	if HasCredentials() {
		t.Error("HasCredentials() = true after clear, want false")
	}
}

func TestGetAuthToken_JWTFirst(t *testing.T) {
	// Create a temporary directory for testing
	tmpDir, err := os.MkdirTemp("", "aussie-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// Override home directory for testing
	originalHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", originalHome)

	// Store JWT credentials
	creds := StoredCredentials{
		Token:     "jwt-token-from-login",
		ExpiresAt: time.Now().Add(time.Hour),
		Subject:   "user@example.com",
	}

	if err := StoreCredentials(creds); err != nil {
		t.Fatalf("StoreCredentials() error = %v", err)
	}

	// GetAuthToken should return JWT token even when API key is provided
	token, err := GetAuthToken("api-key-from-config")
	if err != nil {
		t.Fatalf("GetAuthToken() error = %v", err)
	}

	if token != "jwt-token-from-login" {
		t.Errorf("GetAuthToken() = %q, want %q", token, "jwt-token-from-login")
	}
}

func TestGetAuthToken_FallbackToAPIKey(t *testing.T) {
	// Create a temporary directory for testing
	tmpDir, err := os.MkdirTemp("", "aussie-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// Override home directory for testing
	originalHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", originalHome)

	// No JWT credentials stored

	// GetAuthToken should fall back to API key
	token, err := GetAuthToken("api-key-from-config")
	if err != nil {
		t.Fatalf("GetAuthToken() error = %v", err)
	}

	if token != "api-key-from-config" {
		t.Errorf("GetAuthToken() = %q, want %q", token, "api-key-from-config")
	}
}

func TestGetAuthToken_NoAuthAvailable(t *testing.T) {
	// Create a temporary directory for testing
	tmpDir, err := os.MkdirTemp("", "aussie-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// Override home directory for testing
	originalHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", originalHome)

	// No JWT credentials and no API key
	_, err = GetAuthToken("")
	if err == nil {
		t.Error("GetAuthToken() should return error when no auth is available")
	}
}

func TestGetAuthToken_ExpiredJWT_FallbackToAPIKey(t *testing.T) {
	// Create a temporary directory for testing
	tmpDir, err := os.MkdirTemp("", "aussie-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// Override home directory for testing
	originalHome := os.Getenv("HOME")
	os.Setenv("HOME", tmpDir)
	defer os.Setenv("HOME", originalHome)

	// Store expired JWT credentials
	creds := StoredCredentials{
		Token:     "expired-jwt-token",
		ExpiresAt: time.Now().Add(-time.Hour), // Expired
		Subject:   "user@example.com",
	}

	if err := StoreCredentials(creds); err != nil {
		t.Fatalf("StoreCredentials() error = %v", err)
	}

	// GetAuthToken should fall back to API key when JWT is expired
	token, err := GetAuthToken("api-key-from-config")
	if err != nil {
		t.Fatalf("GetAuthToken() error = %v", err)
	}

	if token != "api-key-from-config" {
		t.Errorf("GetAuthToken() = %q, want %q", token, "api-key-from-config")
	}
}
