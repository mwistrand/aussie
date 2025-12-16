package auth

import (
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"
)

// createTestJWT creates a simple JWT for testing (header.payload.signature)
func createTestJWT(claims TokenClaims) string {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))

	payload, _ := json.Marshal(claims)
	encodedPayload := base64.RawURLEncoding.EncodeToString(payload)

	// Fake signature (we don't verify in CLI)
	signature := base64.RawURLEncoding.EncodeToString([]byte("fake-signature"))

	return header + "." + encodedPayload + "." + signature
}

func TestParseTokenClaims(t *testing.T) {
	now := time.Now().Unix()
	expiresAt := now + 3600 // 1 hour from now

	claims := TokenClaims{
		Subject:   "user@example.com",
		Name:      "Test User",
		Email:     "user@example.com",
		Groups:    []string{"developers", "admins"},
		IssuedAt:  now,
		ExpiresAt: expiresAt,
		Issuer:    "test-issuer",
	}

	token := createTestJWT(claims)

	parsed, err := ParseTokenClaims(token)
	if err != nil {
		t.Fatalf("ParseTokenClaims() error = %v", err)
	}

	if parsed.Subject != claims.Subject {
		t.Errorf("Subject = %q, want %q", parsed.Subject, claims.Subject)
	}
	if parsed.Name != claims.Name {
		t.Errorf("Name = %q, want %q", parsed.Name, claims.Name)
	}
	if parsed.Email != claims.Email {
		t.Errorf("Email = %q, want %q", parsed.Email, claims.Email)
	}
	if len(parsed.Groups) != len(claims.Groups) {
		t.Errorf("Groups length = %d, want %d", len(parsed.Groups), len(claims.Groups))
	}
	if parsed.ExpiresAt != claims.ExpiresAt {
		t.Errorf("ExpiresAt = %d, want %d", parsed.ExpiresAt, claims.ExpiresAt)
	}
}

func TestParseTokenClaims_InvalidFormat(t *testing.T) {
	tests := []struct {
		name  string
		token string
	}{
		{
			name:  "empty string",
			token: "",
		},
		{
			name:  "no dots",
			token: "nodots",
		},
		{
			name:  "one dot",
			token: "one.dot",
		},
		{
			name:  "too many dots",
			token: "too.many.dots.here",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := ParseTokenClaims(tt.token)
			if err == nil {
				t.Error("ParseTokenClaims() should return error for invalid format")
			}
		})
	}
}

func TestParseTokenClaims_InvalidBase64(t *testing.T) {
	// Token with invalid base64 in payload
	token := "eyJhbGciOiJIUzI1NiJ9.!!!invalid!!.signature"

	_, err := ParseTokenClaims(token)
	if err == nil {
		t.Error("ParseTokenClaims() should return error for invalid base64")
	}
}

func TestParseTokenClaims_InvalidJSON(t *testing.T) {
	// Token with valid base64 but invalid JSON
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256"}`))
	payload := base64.RawURLEncoding.EncodeToString([]byte(`not valid json`))
	signature := base64.RawURLEncoding.EncodeToString([]byte("sig"))

	token := header + "." + payload + "." + signature

	_, err := ParseTokenClaims(token)
	if err == nil {
		t.Error("ParseTokenClaims() should return error for invalid JSON")
	}
}

func TestTokenClaims_ExpiryTime(t *testing.T) {
	expiresAt := time.Now().Add(time.Hour).Unix()
	claims := TokenClaims{
		Subject:   "user@example.com",
		ExpiresAt: expiresAt,
	}

	expiry := claims.ExpiryTime()

	// Should be within a second of expected
	expected := time.Unix(expiresAt, 0)
	if !expiry.Equal(expected) {
		t.Errorf("ExpiryTime() = %v, want %v", expiry, expected)
	}
}

func TestTokenClaims_IsExpired(t *testing.T) {
	tests := []struct {
		name      string
		expiresAt int64
		want      bool
	}{
		{
			name:      "not expired",
			expiresAt: time.Now().Add(time.Hour).Unix(),
			want:      false,
		},
		{
			name:      "expired",
			expiresAt: time.Now().Add(-time.Hour).Unix(),
			want:      true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			claims := TokenClaims{
				Subject:   "user@example.com",
				ExpiresAt: tt.expiresAt,
			}

			if got := claims.IsExpired(); got != tt.want {
				t.Errorf("IsExpired() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestTokenClaims_ExpiresIn(t *testing.T) {
	expiresAt := time.Now().Add(time.Hour).Unix()
	claims := TokenClaims{
		Subject:   "user@example.com",
		ExpiresAt: expiresAt,
	}

	expiresIn := claims.ExpiresIn()

	// Should be close to 1 hour
	if expiresIn < 59*time.Minute || expiresIn > 61*time.Minute {
		t.Errorf("ExpiresIn() = %v, want ~1 hour", expiresIn)
	}
}

func TestTokenClaims_ToStoredCredentials(t *testing.T) {
	now := time.Now()
	claims := TokenClaims{
		Subject:   "user@example.com",
		Name:      "Test User",
		Groups:    []string{"developers"},
		ExpiresAt: now.Add(time.Hour).Unix(),
	}

	token := "test-jwt-token"
	creds := claims.ToStoredCredentials(token)

	if creds.Token != token {
		t.Errorf("Token = %q, want %q", creds.Token, token)
	}
	if creds.Subject != claims.Subject {
		t.Errorf("Subject = %q, want %q", creds.Subject, claims.Subject)
	}
	if creds.Name != claims.Name {
		t.Errorf("Name = %q, want %q", creds.Name, claims.Name)
	}
	if len(creds.Groups) != len(claims.Groups) {
		t.Errorf("Groups length = %d, want %d", len(creds.Groups), len(claims.Groups))
	}
	if creds.ExpiresAt.Unix() != claims.ExpiresAt {
		t.Errorf("ExpiresAt = %v, want %v", creds.ExpiresAt.Unix(), claims.ExpiresAt)
	}
}

func TestTokenClaims_MinimalToken(t *testing.T) {
	// Test with only required fields
	claims := TokenClaims{
		Subject:   "user@example.com",
		ExpiresAt: time.Now().Add(time.Hour).Unix(),
	}

	token := createTestJWT(claims)

	parsed, err := ParseTokenClaims(token)
	if err != nil {
		t.Fatalf("ParseTokenClaims() error = %v", err)
	}

	if parsed.Subject != claims.Subject {
		t.Errorf("Subject = %q, want %q", parsed.Subject, claims.Subject)
	}
	if parsed.Name != "" {
		t.Errorf("Name = %q, want empty", parsed.Name)
	}
	if len(parsed.Groups) != 0 {
		t.Errorf("Groups length = %d, want 0", len(parsed.Groups))
	}
}
