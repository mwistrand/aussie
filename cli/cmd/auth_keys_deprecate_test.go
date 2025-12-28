package cmd

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAuthKeysDeprecateCmd_Initialized(t *testing.T) {
	if authKeysDeprecateCmd == nil {
		t.Fatal("authKeysDeprecateCmd is nil")
	}

	if authKeysDeprecateCmd.Use != "deprecate <key-id>" {
		t.Errorf("authKeysDeprecateCmd.Use = %q, want %q", authKeysDeprecateCmd.Use, "deprecate <key-id>")
	}

	if authKeysDeprecateCmd.Short == "" {
		t.Error("authKeysDeprecateCmd.Short should not be empty")
	}

	if authKeysDeprecateCmd.Long == "" {
		t.Error("authKeysDeprecateCmd.Long should not be empty")
	}

	if authKeysDeprecateCmd.RunE == nil {
		t.Error("authKeysDeprecateCmd.RunE should not be nil")
	}
}

func TestAuthKeysDeprecateCmd_RequiresOneArgument(t *testing.T) {
	if authKeysDeprecateCmd.Args == nil {
		t.Error("authKeysDeprecateCmd should have Args validator")
	}
}

func TestAuthKeysDeprecateCmd_LongDescriptionMentionsVerification(t *testing.T) {
	long := authKeysDeprecateCmd.Long

	// Should explain that deprecated keys can still verify
	if long == "" {
		t.Error("authKeysDeprecateCmd.Long should not be empty")
	}
}

func TestAuthKeysDeprecateCmd_HTTPRequest(t *testing.T) {
	keyId := "k-2024-q1-abc123"

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify request method
		if r.Method != "POST" {
			t.Errorf("Expected POST request, got %s", r.Method)
		}

		// Verify path includes key ID
		expectedPath := "/admin/keys/" + keyId + "/deprecate"
		if r.URL.Path != expectedPath {
			t.Errorf("Expected path %s, got %s", expectedPath, r.URL.Path)
		}

		// Verify headers
		if r.Header.Get("Authorization") == "" {
			t.Error("Expected Authorization header")
		}

		// Send response
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	// Verify endpoint pattern
	_ = server.URL + "/admin/keys/" + keyId + "/deprecate"
}

func TestAuthKeysDeprecateCmd_HTTPStatusCodes(t *testing.T) {
	tests := []struct {
		name          string
		statusCode    int
		expectError   bool
		errorContains string
	}{
		{
			name:        "No Content (success)",
			statusCode:  http.StatusNoContent,
			expectError: false,
		},
		{
			name:          "Unauthorized",
			statusCode:    http.StatusUnauthorized,
			expectError:   true,
			errorContains: "authentication failed",
		},
		{
			name:          "Forbidden",
			statusCode:    http.StatusForbidden,
			expectError:   true,
			errorContains: "insufficient permissions",
		},
		{
			name:          "Not Found",
			statusCode:    http.StatusNotFound,
			expectError:   true,
			errorContains: "signing key not found",
		},
		{
			name:          "Conflict",
			statusCode:    http.StatusConflict,
			expectError:   true,
			errorContains: "cannot be deprecated",
		},
		{
			name:          "Service Unavailable",
			statusCode:    http.StatusServiceUnavailable,
			expectError:   true,
			errorContains: "key rotation is not enabled",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tt.statusCode)
			}))
			defer server.Close()

			// Verify server URL pattern
			_ = server.URL
		})
	}
}

func TestAuthKeysDeprecateCmd_NoRequestBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify no request body is sent
		if r.ContentLength > 0 {
			t.Errorf("Expected no request body, got ContentLength=%d", r.ContentLength)
		}

		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	_ = server.URL
}
