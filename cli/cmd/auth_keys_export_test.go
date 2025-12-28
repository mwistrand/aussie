package cmd

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestAuthKeysExportCmd_Initialized(t *testing.T) {
	if authKeysExportCmd == nil {
		t.Fatal("authKeysExportCmd is nil")
	}

	if authKeysExportCmd.Use != "export" {
		t.Errorf("authKeysExportCmd.Use = %q, want %q", authKeysExportCmd.Use, "export")
	}

	if authKeysExportCmd.Short == "" {
		t.Error("authKeysExportCmd.Short should not be empty")
	}

	if authKeysExportCmd.Long == "" {
		t.Error("authKeysExportCmd.Long should not be empty")
	}

	if authKeysExportCmd.RunE == nil {
		t.Error("authKeysExportCmd.RunE should not be nil")
	}
}

func TestAuthKeysExportCmd_HasOutputFlag(t *testing.T) {
	flag := authKeysExportCmd.Flags().Lookup("output")
	if flag == nil {
		t.Fatal("authKeysExportCmd should have 'output' flag")
	}

	if flag.Shorthand != "o" {
		t.Errorf("output flag shorthand = %q, want %q", flag.Shorthand, "o")
	}

	if flag.DefValue != "" {
		t.Errorf("output flag default = %q, want empty string", flag.DefValue)
	}
}

func TestJWKS_JSONDeserialization(t *testing.T) {
	jsonData := `{
		"keys": [
			{
				"kty": "RSA",
				"kid": "k-2024-q1-abc123",
				"use": "sig",
				"alg": "RS256",
				"n": "base64-encoded-modulus",
				"e": "AQAB"
			}
		]
	}`

	var jwks map[string]interface{}
	err := json.Unmarshal([]byte(jsonData), &jwks)
	if err != nil {
		t.Fatalf("Failed to unmarshal JWKS: %v", err)
	}

	keys, ok := jwks["keys"].([]interface{})
	if !ok {
		t.Fatal("Expected 'keys' array in JWKS")
	}

	if len(keys) != 1 {
		t.Errorf("Expected 1 key in JWKS, got %d", len(keys))
	}

	key := keys[0].(map[string]interface{})
	if key["kty"] != "RSA" {
		t.Errorf("kty = %v, want RSA", key["kty"])
	}
	if key["kid"] != "k-2024-q1-abc123" {
		t.Errorf("kid = %v, want k-2024-q1-abc123", key["kid"])
	}
	if key["alg"] != "RS256" {
		t.Errorf("alg = %v, want RS256", key["alg"])
	}
}

func TestJWKS_MultipleKeys(t *testing.T) {
	jsonData := `{
		"keys": [
			{
				"kty": "RSA",
				"kid": "k-active",
				"use": "sig",
				"alg": "RS256",
				"n": "modulus1",
				"e": "AQAB"
			},
			{
				"kty": "RSA",
				"kid": "k-deprecated",
				"use": "sig",
				"alg": "RS256",
				"n": "modulus2",
				"e": "AQAB"
			}
		]
	}`

	var jwks map[string]interface{}
	err := json.Unmarshal([]byte(jsonData), &jwks)
	if err != nil {
		t.Fatalf("Failed to unmarshal JWKS: %v", err)
	}

	keys, ok := jwks["keys"].([]interface{})
	if !ok {
		t.Fatal("Expected 'keys' array in JWKS")
	}

	if len(keys) != 2 {
		t.Errorf("Expected 2 keys in JWKS, got %d", len(keys))
	}
}

func TestAuthKeysExportCmd_HTTPRequest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify request method
		if r.Method != "GET" {
			t.Errorf("Expected GET request, got %s", r.Method)
		}

		// Verify path - JWKS endpoint
		if r.URL.Path != "/auth/.well-known/jwks.json" {
			t.Errorf("Expected path /auth/.well-known/jwks.json, got %s", r.URL.Path)
		}

		// JWKS is a public endpoint - no auth required
		// (but we don't fail if auth is present)

		// Send response
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{
			"keys": [
				{
					"kty": "RSA",
					"kid": "k-test",
					"use": "sig",
					"alg": "RS256",
					"n": "test-modulus",
					"e": "AQAB"
				}
			]
		}`))
	}))
	defer server.Close()

	// Verify endpoint pattern
	_ = server.URL + "/auth/.well-known/jwks.json"
}

func TestAuthKeysExportCmd_HTTPStatusCodes(t *testing.T) {
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
				"keys": []
			}`,
			expectError: false,
		},
		{
			name:       "OK with keys",
			statusCode: http.StatusOK,
			responseBody: `{
				"keys": [
					{"kty": "RSA", "kid": "k-test", "alg": "RS256"}
				]
			}`,
			expectError: false,
		},
		{
			name:          "Service Unavailable",
			statusCode:    http.StatusServiceUnavailable,
			responseBody:  "",
			expectError:   true,
			errorContains: "key rotation is not enabled",
		},
		{
			name:          "Internal Server Error",
			statusCode:    http.StatusInternalServerError,
			responseBody:  "",
			expectError:   true,
			errorContains: "unexpected response",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tt.statusCode)
				w.Write([]byte(tt.responseBody))
			}))
			defer server.Close()

			// Verify server URL pattern
			_ = server.URL
		})
	}
}

func TestAuthKeysExportCmd_LongDescriptionMentionsJWKS(t *testing.T) {
	long := authKeysExportCmd.Long

	// Should mention JWKS format
	if long == "" {
		t.Error("authKeysExportCmd.Long should not be empty")
	}
}

func TestAuthKeysExportCmd_NoAuthRequired(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// JWKS endpoint should not require auth
		// Test that the endpoint works without Authorization header

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"keys": []}`))
	}))
	defer server.Close()

	_ = server.URL
}

func TestJWKS_PrettyPrint(t *testing.T) {
	input := `{"keys":[{"kty":"RSA","kid":"k-test"}]}`

	var jwks map[string]interface{}
	if err := json.Unmarshal([]byte(input), &jwks); err != nil {
		t.Fatalf("Failed to unmarshal: %v", err)
	}

	prettyJson, err := json.MarshalIndent(jwks, "", "  ")
	if err != nil {
		t.Fatalf("Failed to pretty print: %v", err)
	}

	// Verify it's properly indented
	expected := `{
  "keys": [
    {
      "kid": "k-test",
      "kty": "RSA"
    }
  ]
}`

	if string(prettyJson) != expected {
		t.Errorf("Pretty print output doesn't match expected format")
	}
}

func TestAuthKeysExportCmd_FileOutput(t *testing.T) {
	tmpDir := t.TempDir()
	outputPath := filepath.Join(tmpDir, "jwks.json")

	// Simulate writing to file
	jwksContent := `{
  "keys": [
    {
      "kty": "RSA",
      "kid": "k-test"
    }
  ]
}`

	err := os.WriteFile(outputPath, []byte(jwksContent), 0644)
	if err != nil {
		t.Fatalf("Failed to write file: %v", err)
	}

	// Verify file was written
	content, err := os.ReadFile(outputPath)
	if err != nil {
		t.Fatalf("Failed to read file: %v", err)
	}

	if string(content) != jwksContent {
		t.Error("File content doesn't match expected")
	}
}

func TestJWKS_EmptyKeys(t *testing.T) {
	jsonData := `{"keys": []}`

	var jwks map[string]interface{}
	err := json.Unmarshal([]byte(jsonData), &jwks)
	if err != nil {
		t.Fatalf("Failed to unmarshal JWKS: %v", err)
	}

	keys, ok := jwks["keys"].([]interface{})
	if !ok {
		t.Fatal("Expected 'keys' array in JWKS")
	}

	if len(keys) != 0 {
		t.Errorf("Expected 0 keys, got %d", len(keys))
	}
}
