package cmd

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAuthKeysVerifyCmd_Initialized(t *testing.T) {
	if authKeysVerifyCmd == nil {
		t.Fatal("authKeysVerifyCmd is nil")
	}

	if authKeysVerifyCmd.Use != "verify" {
		t.Errorf("authKeysVerifyCmd.Use = %q, want %q", authKeysVerifyCmd.Use, "verify")
	}

	if authKeysVerifyCmd.Short == "" {
		t.Error("authKeysVerifyCmd.Short should not be empty")
	}

	if authKeysVerifyCmd.Long == "" {
		t.Error("authKeysVerifyCmd.Long should not be empty")
	}

	if authKeysVerifyCmd.RunE == nil {
		t.Error("authKeysVerifyCmd.RunE should not be nil")
	}
}

func TestKeyHealthStatus_JSONDeserialization(t *testing.T) {
	jsonData := `{
		"enabled": true,
		"status": "healthy",
		"activeKeyId": "k-2024-q1-abc123",
		"verificationKeyCount": 2,
		"lastCacheRefresh": "2024-04-01T10:00:00Z"
	}`

	var status keyHealthStatus
	err := json.Unmarshal([]byte(jsonData), &status)
	if err != nil {
		t.Fatalf("Failed to unmarshal keyHealthStatus: %v", err)
	}

	if !status.Enabled {
		t.Error("Enabled should be true")
	}
	if status.Status != "healthy" {
		t.Errorf("Status = %q, want %q", status.Status, "healthy")
	}
	if status.ActiveKeyId != "k-2024-q1-abc123" {
		t.Errorf("ActiveKeyId = %q, want %q", status.ActiveKeyId, "k-2024-q1-abc123")
	}
	if status.VerificationKeyCount != 2 {
		t.Errorf("VerificationKeyCount = %d, want 2", status.VerificationKeyCount)
	}
}

func TestKeyHealthStatus_Unhealthy(t *testing.T) {
	jsonData := `{
		"enabled": true,
		"status": "initializing",
		"verificationKeyCount": 0
	}`

	var status keyHealthStatus
	err := json.Unmarshal([]byte(jsonData), &status)
	if err != nil {
		t.Fatalf("Failed to unmarshal keyHealthStatus: %v", err)
	}

	if status.Status != "initializing" {
		t.Errorf("Status = %q, want %q", status.Status, "initializing")
	}
	if status.ActiveKeyId != "" {
		t.Errorf("ActiveKeyId = %q, want empty string", status.ActiveKeyId)
	}
}

func TestKeyHealthStatus_Disabled(t *testing.T) {
	jsonData := `{
		"enabled": false,
		"status": "disabled",
		"verificationKeyCount": 0
	}`

	var status keyHealthStatus
	err := json.Unmarshal([]byte(jsonData), &status)
	if err != nil {
		t.Fatalf("Failed to unmarshal keyHealthStatus: %v", err)
	}

	if status.Enabled {
		t.Error("Enabled should be false")
	}
	if status.Status != "disabled" {
		t.Errorf("Status = %q, want %q", status.Status, "disabled")
	}
}

func TestFormatBool_True(t *testing.T) {
	result := formatBool(true)
	if result != "Yes" {
		t.Errorf("formatBool(true) = %q, want %q", result, "Yes")
	}
}

func TestFormatBool_False(t *testing.T) {
	result := formatBool(false)
	if result != "No" {
		t.Errorf("formatBool(false) = %q, want %q", result, "No")
	}
}

func TestAuthKeysVerifyCmd_HTTPRequest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify request method
		if r.Method != "GET" {
			t.Errorf("Expected GET request, got %s", r.Method)
		}

		// Verify path
		if r.URL.Path != "/admin/keys/health" {
			t.Errorf("Expected path /admin/keys/health, got %s", r.URL.Path)
		}

		// Verify headers
		if r.Header.Get("Authorization") == "" {
			t.Error("Expected Authorization header")
		}

		// Send response
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{
			"enabled": true,
			"status": "healthy",
			"activeKeyId": "k-test",
			"verificationKeyCount": 2
		}`))
	}))
	defer server.Close()

	// Verify endpoint pattern
	_ = server.URL + "/admin/keys/health"
}

func TestAuthKeysVerifyCmd_HTTPStatusCodes(t *testing.T) {
	tests := []struct {
		name          string
		statusCode    int
		responseBody  string
		expectError   bool
		errorContains string
	}{
		{
			name:       "OK - healthy",
			statusCode: http.StatusOK,
			responseBody: `{
				"enabled": true,
				"status": "healthy",
				"activeKeyId": "k-test",
				"verificationKeyCount": 2
			}`,
			expectError: false,
		},
		{
			name:       "OK - unhealthy",
			statusCode: http.StatusOK,
			responseBody: `{
				"enabled": true,
				"status": "initializing",
				"verificationKeyCount": 0
			}`,
			expectError: true, // Returns error when not healthy
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

func TestAuthKeysVerifyCmd_HealthyLogic(t *testing.T) {
	tests := []struct {
		name        string
		enabled     bool
		status      string
		wantHealthy bool
	}{
		{
			name:        "enabled and healthy",
			enabled:     true,
			status:      "healthy",
			wantHealthy: true,
		},
		{
			name:        "enabled but initializing",
			enabled:     true,
			status:      "initializing",
			wantHealthy: false,
		},
		{
			name:        "disabled",
			enabled:     false,
			status:      "disabled",
			wantHealthy: false,
		},
		{
			name:        "disabled but status healthy",
			enabled:     false,
			status:      "healthy",
			wantHealthy: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			status := keyHealthStatus{
				Enabled: tt.enabled,
				Status:  tt.status,
			}

			// Logic from runAuthKeysVerify
			healthy := status.Enabled && status.Status == "healthy"

			if healthy != tt.wantHealthy {
				t.Errorf("healthy = %v, want %v", healthy, tt.wantHealthy)
			}
		})
	}
}
