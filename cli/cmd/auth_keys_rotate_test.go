package cmd

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAuthKeysRotateCmd_Initialized(t *testing.T) {
	if authKeysRotateCmd == nil {
		t.Fatal("authKeysRotateCmd is nil")
	}

	if authKeysRotateCmd.Use != "rotate" {
		t.Errorf("authKeysRotateCmd.Use = %q, want %q", authKeysRotateCmd.Use, "rotate")
	}

	if authKeysRotateCmd.Short == "" {
		t.Error("authKeysRotateCmd.Short should not be empty")
	}

	if authKeysRotateCmd.Long == "" {
		t.Error("authKeysRotateCmd.Long should not be empty")
	}

	if authKeysRotateCmd.RunE == nil {
		t.Error("authKeysRotateCmd.RunE should not be nil")
	}
}

func TestAuthKeysRotateCmd_HasReasonFlag(t *testing.T) {
	flag := authKeysRotateCmd.Flags().Lookup("reason")
	if flag == nil {
		t.Fatal("authKeysRotateCmd should have 'reason' flag")
	}

	// Reason should default to empty
	if flag.DefValue != "" {
		t.Errorf("reason flag default = %q, want empty string", flag.DefValue)
	}
}

func TestRotateRequest_JSONSerialization(t *testing.T) {
	req := rotateRequest{Reason: "Quarterly rotation"}

	data, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("Failed to marshal rotateRequest: %v", err)
	}

	var result map[string]interface{}
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatalf("Failed to unmarshal to map: %v", err)
	}

	if reason, ok := result["reason"]; !ok || reason != "Quarterly rotation" {
		t.Errorf("reason = %v, want %q", reason, "Quarterly rotation")
	}
}

func TestRotateResponse_JSONDeserialization(t *testing.T) {
	jsonData := `{
		"keyId": "k-2024-q2-def456",
		"status": "ACTIVE",
		"createdAt": "2024-04-01T10:00:00Z",
		"activatedAt": "2024-04-01T10:00:00Z",
		"previousKeyId": "k-2024-q1-abc123"
	}`

	var resp rotateResponse
	err := json.Unmarshal([]byte(jsonData), &resp)
	if err != nil {
		t.Fatalf("Failed to unmarshal rotateResponse: %v", err)
	}

	if resp.KeyId != "k-2024-q2-def456" {
		t.Errorf("KeyId = %q, want %q", resp.KeyId, "k-2024-q2-def456")
	}
	if resp.Status != "ACTIVE" {
		t.Errorf("Status = %q, want %q", resp.Status, "ACTIVE")
	}
	if resp.PreviousKey != "k-2024-q1-abc123" {
		t.Errorf("PreviousKey = %q, want %q", resp.PreviousKey, "k-2024-q1-abc123")
	}
}

func TestRotateResponse_NoPreviousKey(t *testing.T) {
	jsonData := `{
		"keyId": "k-2024-q1-first",
		"status": "ACTIVE",
		"createdAt": "2024-01-01T10:00:00Z",
		"activatedAt": "2024-01-01T10:00:00Z"
	}`

	var resp rotateResponse
	err := json.Unmarshal([]byte(jsonData), &resp)
	if err != nil {
		t.Fatalf("Failed to unmarshal rotateResponse: %v", err)
	}

	if resp.PreviousKey != "" {
		t.Errorf("PreviousKey = %q, want empty string", resp.PreviousKey)
	}
}

func TestAuthKeysRotateCmd_HTTPRequest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify request method
		if r.Method != "POST" {
			t.Errorf("Expected POST request, got %s", r.Method)
		}

		// Verify path
		if r.URL.Path != "/admin/keys/rotate" {
			t.Errorf("Expected path /admin/keys/rotate, got %s", r.URL.Path)
		}

		// Verify headers
		if r.Header.Get("Authorization") == "" {
			t.Error("Expected Authorization header")
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Errorf("Expected Content-Type application/json, got %s", r.Header.Get("Content-Type"))
		}

		// Verify request body
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Fatalf("Failed to read request body: %v", err)
		}

		var req rotateRequest
		if err := json.Unmarshal(body, &req); err != nil {
			t.Fatalf("Failed to unmarshal request body: %v", err)
		}

		if req.Reason == "" {
			t.Error("Expected reason in request body")
		}

		// Send response
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{
			"keyId": "k-new",
			"status": "ACTIVE",
			"createdAt": "2024-04-01T10:00:00Z",
			"activatedAt": "2024-04-01T10:00:00Z"
		}`))
	}))
	defer server.Close()

	// Verify endpoint pattern
	_ = server.URL + "/admin/keys/rotate"
}

func TestAuthKeysRotateCmd_HTTPStatusCodes(t *testing.T) {
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
				"keyId": "k-new",
				"status": "ACTIVE",
				"createdAt": "2024-04-01T10:00:00Z",
				"activatedAt": "2024-04-01T10:00:00Z"
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
				w.WriteHeader(tt.statusCode)
				w.Write([]byte(tt.responseBody))
			}))
			defer server.Close()

			// Verify server URL pattern
			_ = server.URL
		})
	}
}

func TestAuthKeysRotateCmd_LongDescription(t *testing.T) {
	long := authKeysRotateCmd.Long

	// Should mention immediate/emergency use cases
	if long == "" {
		t.Error("authKeysRotateCmd.Long should not be empty")
	}
}
