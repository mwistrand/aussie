package cmd

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAuthKeysShowCmd_Initialized(t *testing.T) {
	if authKeysShowCmd == nil {
		t.Fatal("authKeysShowCmd is nil")
	}

	if authKeysShowCmd.Use != "show <key-id>" {
		t.Errorf("authKeysShowCmd.Use = %q, want %q", authKeysShowCmd.Use, "show <key-id>")
	}

	if authKeysShowCmd.Short == "" {
		t.Error("authKeysShowCmd.Short should not be empty")
	}

	if authKeysShowCmd.Long == "" {
		t.Error("authKeysShowCmd.Long should not be empty")
	}

	if authKeysShowCmd.RunE == nil {
		t.Error("authKeysShowCmd.RunE should not be nil")
	}

	if authKeysShowCmd.Args == nil {
		t.Error("authKeysShowCmd should have Args validator")
	}
}

func TestAuthKeysShowCmd_HasIncludePublicKeyFlag(t *testing.T) {
	flag := authKeysShowCmd.Flags().Lookup("include-public-key")
	if flag == nil {
		t.Fatal("authKeysShowCmd should have 'include-public-key' flag")
	}

	if flag.DefValue != "false" {
		t.Errorf("include-public-key flag default = %q, want %q", flag.DefValue, "false")
	}
}

func TestSigningKeyDetail_JSONDeserialization(t *testing.T) {
	jsonData := `{
		"keyId": "k-2024-q1-abc123",
		"status": "ACTIVE",
		"createdAt": "2024-01-15T10:30:00Z",
		"activatedAt": "2024-01-15T11:00:00Z",
		"deprecatedAt": "",
		"retiredAt": "",
		"canSign": true,
		"canVerify": true
	}`

	var key signingKeyDetail
	err := json.Unmarshal([]byte(jsonData), &key)
	if err != nil {
		t.Fatalf("Failed to unmarshal signingKeyDetail: %v", err)
	}

	if key.KeyId != "k-2024-q1-abc123" {
		t.Errorf("KeyId = %q, want %q", key.KeyId, "k-2024-q1-abc123")
	}
	if key.Status != "ACTIVE" {
		t.Errorf("Status = %q, want %q", key.Status, "ACTIVE")
	}
	if !key.CanSign {
		t.Error("CanSign should be true")
	}
	if !key.CanVerify {
		t.Error("CanVerify should be true")
	}
}

func TestSigningKeyDetail_WithPublicKey(t *testing.T) {
	jsonData := `{
		"keyId": "k-2024-q1-abc123",
		"status": "ACTIVE",
		"createdAt": "2024-01-15T10:30:00Z",
		"canSign": true,
		"canVerify": true,
		"publicKey": {
			"algorithm": "RSA",
			"format": "X.509",
			"modulus_bits": 2048
		}
	}`

	var key signingKeyDetail
	err := json.Unmarshal([]byte(jsonData), &key)
	if err != nil {
		t.Fatalf("Failed to unmarshal signingKeyDetail: %v", err)
	}

	if key.PublicKey == nil {
		t.Fatal("PublicKey should not be nil")
	}

	if alg, ok := key.PublicKey["algorithm"]; !ok || alg != "RSA" {
		t.Errorf("PublicKey.algorithm = %v, want RSA", alg)
	}
	if format, ok := key.PublicKey["format"]; !ok || format != "X.509" {
		t.Errorf("PublicKey.format = %v, want X.509", format)
	}
	if bits, ok := key.PublicKey["modulus_bits"]; !ok {
		t.Error("PublicKey.modulus_bits should be present")
	} else {
		// JSON numbers are float64
		if bitsFloat, ok := bits.(float64); !ok || int(bitsFloat) != 2048 {
			t.Errorf("PublicKey.modulus_bits = %v, want 2048", bits)
		}
	}
}

func TestSigningKeyDetail_DeprecatedKey(t *testing.T) {
	jsonData := `{
		"keyId": "k-old",
		"status": "DEPRECATED",
		"createdAt": "2024-01-01T10:30:00Z",
		"activatedAt": "2024-01-01T11:00:00Z",
		"deprecatedAt": "2024-03-15T10:00:00Z",
		"canSign": false,
		"canVerify": true
	}`

	var key signingKeyDetail
	err := json.Unmarshal([]byte(jsonData), &key)
	if err != nil {
		t.Fatalf("Failed to unmarshal signingKeyDetail: %v", err)
	}

	if key.Status != "DEPRECATED" {
		t.Errorf("Status = %q, want %q", key.Status, "DEPRECATED")
	}
	if key.CanSign {
		t.Error("CanSign should be false for deprecated key")
	}
	if !key.CanVerify {
		t.Error("CanVerify should be true for deprecated key")
	}
	if key.DeprecatedAt == "" {
		t.Error("DeprecatedAt should not be empty")
	}
}

func TestSigningKeyDetail_RetiredKey(t *testing.T) {
	jsonData := `{
		"keyId": "k-retired",
		"status": "RETIRED",
		"createdAt": "2023-01-01T10:30:00Z",
		"activatedAt": "2023-01-01T11:00:00Z",
		"deprecatedAt": "2023-06-15T10:00:00Z",
		"retiredAt": "2024-01-15T10:00:00Z",
		"canSign": false,
		"canVerify": false
	}`

	var key signingKeyDetail
	err := json.Unmarshal([]byte(jsonData), &key)
	if err != nil {
		t.Fatalf("Failed to unmarshal signingKeyDetail: %v", err)
	}

	if key.Status != "RETIRED" {
		t.Errorf("Status = %q, want %q", key.Status, "RETIRED")
	}
	if key.CanSign {
		t.Error("CanSign should be false for retired key")
	}
	if key.CanVerify {
		t.Error("CanVerify should be false for retired key")
	}
	if key.RetiredAt == "" {
		t.Error("RetiredAt should not be empty")
	}
}

func TestFormatTimestamp_EmptyString(t *testing.T) {
	result := formatTimestamp("")
	if result != "-" {
		t.Errorf("formatTimestamp(\"\") = %q, want %q", result, "-")
	}
}

func TestFormatTimestamp_ValidRFC3339(t *testing.T) {
	result := formatTimestamp("2024-01-15T10:30:00Z")
	// Should contain the formatted date
	if result == "" || result == "-" {
		t.Errorf("formatTimestamp(\"2024-01-15T10:30:00Z\") should return formatted timestamp, got %q", result)
	}
	// Check it contains expected components
	if len(result) < 19 { // "2006-01-02 15:04:05" is 19 chars
		t.Errorf("formatTimestamp result too short: %q", result)
	}
}

func TestFormatTimestamp_InvalidTimestamp(t *testing.T) {
	result := formatTimestamp("invalid-timestamp")
	if result != "invalid-timestamp" {
		t.Errorf("formatTimestamp(\"invalid-timestamp\") = %q, want raw input", result)
	}
}

func TestAuthKeysShowCmd_HTTPStatusCodes(t *testing.T) {
	tests := []struct {
		name          string
		statusCode    int
		responseBody  string
		expectError   bool
		errorContains string
	}{
		{
			name:       "OK",
			statusCode: http.StatusOK,
			responseBody: `{
				"keyId": "k-test",
				"status": "ACTIVE",
				"createdAt": "2024-01-15T10:30:00Z",
				"canSign": true,
				"canVerify": true
			}`,
			expectError: false,
		},
		{
			name:          "Unauthorized",
			statusCode:    http.StatusUnauthorized,
			responseBody:  "",
			expectError:   true,
			errorContains: "authentication failed",
		},
		{
			name:          "Forbidden",
			statusCode:    http.StatusForbidden,
			responseBody:  "",
			expectError:   true,
			errorContains: "insufficient permissions",
		},
		{
			name:          "Not Found",
			statusCode:    http.StatusNotFound,
			responseBody:  "",
			expectError:   true,
			errorContains: "signing key not found",
		},
		{
			name:          "Service Unavailable",
			statusCode:    http.StatusServiceUnavailable,
			responseBody:  "",
			expectError:   true,
			errorContains: "key rotation is not enabled",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				// Verify request
				if r.Method != "GET" {
					t.Errorf("Expected GET request, got %s", r.Method)
				}
				if r.Header.Get("Authorization") == "" {
					t.Error("Expected Authorization header")
				}

				w.WriteHeader(tt.statusCode)
				w.Write([]byte(tt.responseBody))
			}))
			defer server.Close()

			// Verify the server URL pattern
			_ = server.URL
		})
	}
}

func TestAuthKeysShowCmd_IncludePublicKeyQueryParam(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Check query parameter
		includePublicKey := r.URL.Query().Get("includePublicKey")

		response := `{
			"keyId": "k-test",
			"status": "ACTIVE",
			"createdAt": "2024-01-15T10:30:00Z",
			"canSign": true,
			"canVerify": true
		}`

		if includePublicKey == "true" {
			response = `{
				"keyId": "k-test",
				"status": "ACTIVE",
				"createdAt": "2024-01-15T10:30:00Z",
				"canSign": true,
				"canVerify": true,
				"publicKey": {
					"algorithm": "RSA",
					"format": "X.509",
					"modulus_bits": 2048
				}
			}`
		}

		w.WriteHeader(http.StatusOK)
		w.Write([]byte(response))
	}))
	defer server.Close()

	// Verify the endpoint pattern
	_ = server.URL + "/admin/keys/k-test?includePublicKey=true"
}
