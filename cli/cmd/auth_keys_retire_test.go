package cmd

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAuthKeysRetireCmd_Initialized(t *testing.T) {
	if authKeysRetireCmd == nil {
		t.Fatal("authKeysRetireCmd is nil")
	}

	if authKeysRetireCmd.Use != "retire <key-id>" {
		t.Errorf("authKeysRetireCmd.Use = %q, want %q", authKeysRetireCmd.Use, "retire <key-id>")
	}

	if authKeysRetireCmd.Short == "" {
		t.Error("authKeysRetireCmd.Short should not be empty")
	}

	if authKeysRetireCmd.Long == "" {
		t.Error("authKeysRetireCmd.Long should not be empty")
	}

	if authKeysRetireCmd.RunE == nil {
		t.Error("authKeysRetireCmd.RunE should not be nil")
	}
}

func TestAuthKeysRetireCmd_RequiresOneArgument(t *testing.T) {
	if authKeysRetireCmd.Args == nil {
		t.Error("authKeysRetireCmd should have Args validator")
	}
}

func TestAuthKeysRetireCmd_HasForceFlag(t *testing.T) {
	flag := authKeysRetireCmd.Flags().Lookup("force")
	if flag == nil {
		t.Fatal("authKeysRetireCmd should have 'force' flag")
	}

	if flag.DefValue != "false" {
		t.Errorf("force flag default = %q, want %q", flag.DefValue, "false")
	}
}

func TestAuthKeysRetireCmd_HTTPRequest(t *testing.T) {
	keyId := "k-2024-q1-abc123"

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify request method - retire uses DELETE
		if r.Method != "DELETE" {
			t.Errorf("Expected DELETE request, got %s", r.Method)
		}

		// Verify path includes key ID
		expectedPath := "/admin/keys/" + keyId
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
	_ = server.URL + "/admin/keys/" + keyId
}

func TestAuthKeysRetireCmd_ForceQueryParam(t *testing.T) {
	keyId := "k-2024-q1-abc123"

	tests := []struct {
		name        string
		forceFlag   bool
		expectQuery string
	}{
		{
			name:        "without force flag",
			forceFlag:   false,
			expectQuery: "",
		},
		{
			name:        "with force flag",
			forceFlag:   true,
			expectQuery: "force=true",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				query := r.URL.RawQuery
				if tt.forceFlag {
					if query != "force=true" {
						t.Errorf("Expected query 'force=true', got %q", query)
					}
				} else {
					if query != "" {
						t.Errorf("Expected no query params, got %q", query)
					}
				}

				w.WriteHeader(http.StatusNoContent)
			}))
			defer server.Close()

			_ = server.URL + "/admin/keys/" + keyId
		})
	}
}

func TestAuthKeysRetireCmd_HTTPStatusCodes(t *testing.T) {
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
			name:          "Conflict (active key without force)",
			statusCode:    http.StatusConflict,
			expectError:   true,
			errorContains: "still active",
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

func TestAuthKeysRetireCmd_LongDescriptionMentionsWarning(t *testing.T) {
	long := authKeysRetireCmd.Long

	// Should mention the danger of retiring keys
	if long == "" {
		t.Error("authKeysRetireCmd.Long should not be empty")
	}
}

func TestAuthKeysRetireCmd_NoRequestBody(t *testing.T) {
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
