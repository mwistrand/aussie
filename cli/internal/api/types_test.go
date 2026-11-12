package api

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestServiceRegistrationMatchesGoldenFixture(t *testing.T) {
	_, sourceFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	fixturePath := filepath.Join(filepath.Dir(sourceFile), "../../../contract-fixtures/control-plane/service-registration-request.json")
	fixture, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}

	var registration ServiceRegistration
	if err := json.Unmarshal(fixture, &registration); err != nil {
		t.Fatalf("decode fixture: %v", err)
	}
	encoded, err := json.Marshal(registration)
	if err != nil {
		t.Fatalf("encode registration: %v", err)
	}

	var want, got any
	if err := json.Unmarshal(fixture, &want); err != nil {
		t.Fatalf("decode expected fixture: %v", err)
	}
	if err := json.Unmarshal(encoded, &got); err != nil {
		t.Fatalf("decode encoded registration: %v", err)
	}
	if !bytes.Equal(mustJSON(t, want), mustJSON(t, got)) {
		t.Fatalf("encoded registration does not match fixture:\n%s", encoded)
	}
}

func TestAPIKeyCreateRequestOmitsUnsetTTL(t *testing.T) {
	data, err := json.Marshal(APIKeyCreateRequest{
		Name:        "ci",
		Permissions: []string{"admin"},
	})
	if err != nil {
		t.Fatalf("marshal API key request: %v", err)
	}
	if string(data) != `{"name":"ci","permissions":["admin"]}` {
		t.Fatalf("unexpected request JSON: %s", data)
	}
}

func TestTokenRevocationWireTypesRoundTrip(t *testing.T) {
	var response TokenRevocationList
	if err := json.Unmarshal([]byte(`{"revokedTokens":["jti-1"],"count":1,"limit":50}`), &response); err != nil {
		t.Fatalf("decode token revocation list: %v", err)
	}
	if len(response.RevokedTokens) != 1 || response.RevokedTokens[0] != "jti-1" {
		t.Fatalf("unexpected token revocation list: %#v", response)
	}

	request, err := json.Marshal(RevokeByTokenRequest{Token: "jwt", Reason: "incident"})
	if err != nil {
		t.Fatalf("encode revoke request: %v", err)
	}
	if string(request) != `{"token":"jwt","reason":"incident"}` {
		t.Fatalf("unexpected revoke request: %s", request)
	}
}

func TestLockoutWireTypesRoundTrip(t *testing.T) {
	var response LockoutStatus
	if err := json.Unmarshal([]byte(`{"type":"user","value":"alice","key":"user:alice","lockedOut":true,"failedAttempts":5,"maxAttempts":5,"lockoutStarted":"2026-01-01T00:00:00Z","lockoutExpires":"2026-01-01T01:00:00Z","lockoutCount":2,"checkedAt":"2026-01-01T00:00:01Z"}`), &response); err != nil {
		t.Fatalf("decode lockout status: %v", err)
	}
	if !response.LockedOut || response.FailedAttempts != 5 || response.LockoutCount != 2 {
		t.Fatalf("unexpected lockout status: %#v", response)
	}

	encoded, err := json.Marshal(ClearAllLockoutsRequest{Force: true, Reason: "incident"})
	if err != nil {
		t.Fatalf("encode lockout request: %v", err)
	}
	if string(encoded) != `{"force":true,"reason":"incident"}` {
		t.Fatalf("unexpected lockout request: %s", encoded)
	}
}

func TestTokenInspectionPreservesNullClaims(t *testing.T) {
	fixture := []byte(`{"jti":null,"subject":null,"issuer":null,"audience":null,"issuedAt":null,"expiresAt":null,"notBefore":null}`)

	var inspection TokenInspection
	if err := json.Unmarshal(fixture, &inspection); err != nil {
		t.Fatalf("decode token inspection: %v", err)
	}
	encoded, err := json.Marshal(inspection)
	if err != nil {
		t.Fatalf("encode token inspection: %v", err)
	}

	var want, got any
	if err := json.Unmarshal(fixture, &want); err != nil {
		t.Fatalf("decode expected inspection: %v", err)
	}
	if err := json.Unmarshal(encoded, &got); err != nil {
		t.Fatalf("decode encoded inspection: %v", err)
	}
	if !bytes.Equal(mustJSON(t, want), mustJSON(t, got)) {
		t.Fatalf("null claims were not preserved: %s", encoded)
	}
}

func TestControlPlaneContractFixturesRoundTrip(t *testing.T) {
	tests := []struct {
		name  string
		value any
	}{
		{
			name: "capabilities",
			value: Capabilities{
				ContractVersion:           "v1",
				SupportedContractVersions: []string{"v1"},
				OpenAPI:                   "/openapi.yaml",
			},
		},
		{
			name: "oauth-token",
			value: OAuthTokenResponse{
				AccessToken: "access-token",
				TokenType:   "Bearer",
				ExpiresIn:   3600,
				IDToken:     "id-token",
				Scope:       "openid profile email",
			},
		},
		{
			name: "oauth-device-code",
			value: OAuthDeviceCodeResponse{
				DeviceCode:              "device-code",
				UserCode:                "ABCD-EFGH",
				VerificationURI:         "https://idp.example.com/device",
				VerificationURL:         "https://idp.example.com/device",
				VerificationURIComplete: "https://idp.example.com/device?user_code=ABCD-EFGH",
				ExpiresIn:               600,
				Interval:                5,
			},
		},
		{
			name: "oauth-device-token",
			value: OAuthTokenResponse{
				AccessToken: "access-token",
				TokenType:   "Bearer",
				ExpiresIn:   3600,
			},
		},
		{
			name: "role",
			value: Role{
				ID:          "platform-team",
				DisplayName: "Platform Team",
				Description: "Platform infrastructure team",
				Permissions: []string{"service.config.create", "service.config.read"},
				Version:     1,
			},
		},
		{
			name: "translation-status",
			value: TranslationStatus{
				Enabled:         true,
				ActiveProvider:  "config",
				ProviderHealthy: true,
				Cache:           TranslationCacheStatus{CurrentSize: 0, MaxSize: 1000, TtlSeconds: 300},
			},
		},
		{
			name:  "translation-validation",
			value: TranslationConfigValidation{Valid: false, Errors: []string{"Configuration is required"}},
		},
		{
			name: "signing-key-health",
			value: SigningKeyHealthStatus{
				Enabled: true, Status: "healthy", ActiveKeyId: "key-2026",
				VerificationKeyCount: 2, LastCacheRefresh: "2026-01-01T00:00:00Z",
			},
		},
		{
			name: "api-key",
			value: APIKey{
				Id: "key-1", Name: "automation", Description: "CI automation key",
				Permissions: []string{"admin:read"}, CreatedBy: "contract-user",
				CreatedAt: "2026-01-01T00:00:00Z", ExpiresAt: "2026-02-01T00:00:00Z",
				Revoked: false,
			},
		},
		{
			name:  "token-revocation-status",
			value: TokenRevocationStatus{JTI: "contract-jti", Revoked: false, CheckedAt: "2026-01-01T00:00:00Z"},
		},
		{
			name: "lockout-status",
			value: LockoutStatus{
				Type: "user", Value: "contract-user", Key: "user:contract-user", MaxAttempts: 5,
				CheckedAt: "2026-01-01T00:00:00Z",
			},
		},
		{
			name: "whoami",
			value: WhoAmIResponse{
				Name:        "development-mode",
				Permissions: []string{"*"},
				Roles: []string{
					"*", "admin", "apikeys.read", "apikeys.write", "aussie:admin",
					"auth.roles.create", "auth.roles.delete", "auth.roles.read", "auth.roles.update",
					"benchmark.run", "keys.read", "keys.rotate", "keys.write",
					"lockouts.read", "lockouts.write", "service.config.create", "service.config.delete",
					"service.config.read", "service.config.update", "service.permissions.read",
					"service.permissions.write", "tokens.read", "tokens.revoke",
					"translation.config.read", "translation.config.write",
				},
			},
		},
		{
			name: "problem",
			value: ProblemResponse{
				Type:     "urn:aussie:problem:bad_gateway",
				Title:    "Bad Gateway",
				Status:   502,
				Detail:   "IdP response missing ID token",
				Instance: "/auth/oidc/token",
				Code:     "bad_gateway",
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fixturePath := filepath.Join(filepath.Dir(mustSourceFile(t)), "../../../contract-fixtures/control-plane", test.name+".json")
			fixture, err := os.ReadFile(fixturePath)
			if err != nil {
				t.Fatalf("read fixture: %v", err)
			}
			encoded, err := json.Marshal(test.value)
			if err != nil {
				t.Fatalf("encode value: %v", err)
			}
			var want, got any
			if err := json.Unmarshal(fixture, &want); err != nil {
				t.Fatalf("decode fixture: %v", err)
			}
			if err := json.Unmarshal(encoded, &got); err != nil {
				t.Fatalf("decode encoded value: %v", err)
			}
			if !bytes.Equal(mustJSON(t, want), mustJSON(t, got)) {
				t.Fatalf("encoded value does not match fixture: %s", encoded)
			}
		})
	}
}

func mustSourceFile(t *testing.T) string {
	t.Helper()
	_, sourceFile, _, ok := runtime.Caller(1)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	return sourceFile
}

func mustJSON(t *testing.T, value any) []byte {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal JSON: %v", err)
	}
	return data
}
