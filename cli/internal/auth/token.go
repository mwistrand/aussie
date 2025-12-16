package auth

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// TokenClaims represents the claims extracted from a JWT token.
// We parse these without verification since the gateway will verify.
type TokenClaims struct {
	Subject   string   `json:"sub"`
	Name      string   `json:"name,omitempty"`
	Email     string   `json:"email,omitempty"`
	Groups    []string `json:"groups,omitempty"`
	IssuedAt  int64    `json:"iat,omitempty"`
	ExpiresAt int64    `json:"exp,omitempty"`
	Issuer    string   `json:"iss,omitempty"`
}

// ParseTokenClaims extracts claims from a JWT token without verification.
// The gateway will perform full verification; we just need the claims for display.
func ParseTokenClaims(token string) (*TokenClaims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("invalid token format: expected 3 parts, got %d", len(parts))
	}

	// Decode the payload (second part)
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("failed to decode token payload: %w", err)
	}

	var claims TokenClaims
	if err := json.Unmarshal(payload, &claims); err != nil {
		return nil, fmt.Errorf("failed to parse token claims: %w", err)
	}

	return &claims, nil
}

// ExpiryTime returns the token expiry as a time.Time.
func (c *TokenClaims) ExpiryTime() time.Time {
	return time.Unix(c.ExpiresAt, 0)
}

// IsExpired returns true if the token has expired.
func (c *TokenClaims) IsExpired() bool {
	return time.Now().After(c.ExpiryTime())
}

// ExpiresIn returns the duration until the token expires.
func (c *TokenClaims) ExpiresIn() time.Duration {
	return time.Until(c.ExpiryTime())
}

// ToStoredCredentials converts token claims to StoredCredentials.
func (c *TokenClaims) ToStoredCredentials(token string) StoredCredentials {
	return StoredCredentials{
		Token:     token,
		ExpiresAt: c.ExpiryTime(),
		Subject:   c.Subject,
		Name:      c.Name,
		Groups:    c.Groups,
	}
}
