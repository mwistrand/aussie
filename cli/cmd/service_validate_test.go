package cmd

import (
	"os"
	"path/filepath"
	"testing"
)

// These tests use the "ServiceValidate" prefix to avoid conflicts with register_test.go

func TestValidateServiceConfig_ValidMinimalConfig(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080"
	}`

	result := ValidateServiceConfig([]byte(json))

	if !result.IsValid() {
		t.Errorf("Expected valid config, got errors: %v", result.Errors)
	}
}

func TestValidateServiceConfig_ValidV2Config(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"routePrefix": "/my-service",
		"defaultVisibility": "PRIVATE",
		"visibilityRules": [
			{
				"pattern": "/api/health",
				"methods": ["GET"],
				"visibility": "PUBLIC"
			},
			{
				"pattern": "/api/users/**",
				"visibility": "PUBLIC"
			}
		]
	}`

	result := ValidateServiceConfig([]byte(json))

	if !result.IsValid() {
		t.Errorf("Expected valid config, got errors: %v", result.Errors)
	}
}

func TestValidateServiceConfig_ValidV1Config(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"endpoints": [
			{
				"path": "/api/users",
				"methods": ["GET", "POST"],
				"visibility": "PUBLIC"
			}
		]
	}`

	result := ValidateServiceConfig([]byte(json))

	if !result.IsValid() {
		t.Errorf("Expected valid config, got errors: %v", result.Errors)
	}
}

func TestServiceValidate_MissingServiceId(t *testing.T) {
	json := `{
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080"
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for missing serviceId")
	}

	if !hasErrorWithPath(result, "serviceId") {
		t.Error("Expected error for serviceId field")
	}
}

func TestValidateServiceConfig_MissingDisplayName(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"baseUrl": "http://localhost:8080"
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for missing displayName")
	}

	if !hasErrorWithPath(result, "displayName") {
		t.Error("Expected error for displayName field")
	}
}

func TestServiceValidate_MissingBaseUrl(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service"
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for missing baseUrl")
	}

	if !hasErrorWithPath(result, "baseUrl") {
		t.Error("Expected error for baseUrl field")
	}
}

func TestServiceValidate_InvalidJSON(t *testing.T) {
	json := `{ invalid json }`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for invalid JSON")
	}
}

func TestValidateServiceConfig_InvalidBaseUrl(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "not-a-url"
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for invalid baseUrl")
	}

	if !hasErrorWithPath(result, "baseUrl") {
		t.Error("Expected error for baseUrl field")
	}
}

func TestValidateServiceConfig_InvalidServiceId(t *testing.T) {
	json := `{
		"serviceId": "my service with spaces",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080"
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for invalid serviceId")
	}

	if !hasErrorWithPath(result, "serviceId") {
		t.Error("Expected error for serviceId field")
	}
}

func TestValidateServiceConfig_InvalidVisibility(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"defaultVisibility": "INVALID"
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for invalid defaultVisibility")
	}

	if !hasErrorWithPath(result, "defaultVisibility") {
		t.Error("Expected error for defaultVisibility field")
	}
}

func TestValidateServiceConfig_InvalidRoutePrefix(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"routePrefix": "no-leading-slash"
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for invalid routePrefix")
	}

	if !hasErrorWithPath(result, "routePrefix") {
		t.Error("Expected error for routePrefix field")
	}
}

func TestValidateServiceConfig_InvalidVisibilityRule(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"visibilityRules": [
			{
				"pattern": "",
				"visibility": "PUBLIC"
			}
		]
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for empty pattern")
	}

	if !hasErrorWithPath(result, "visibilityRules[0].pattern") {
		t.Error("Expected error for visibilityRules[0].pattern field")
	}
}

func TestValidateServiceConfig_InvalidVisibilityRulePattern(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"visibilityRules": [
			{
				"pattern": "no-leading-slash",
				"visibility": "PUBLIC"
			}
		]
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for pattern without leading slash")
	}

	if !hasErrorWithPath(result, "visibilityRules[0].pattern") {
		t.Error("Expected error for visibilityRules[0].pattern field")
	}
}

func TestValidateServiceConfig_InvalidHttpMethod(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"visibilityRules": [
			{
				"pattern": "/api/test",
				"methods": ["INVALID"],
				"visibility": "PUBLIC"
			}
		]
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for invalid HTTP method")
	}

	if !hasErrorWithPath(result, "visibilityRules[0].methods[0]") {
		t.Error("Expected error for visibilityRules[0].methods[0] field")
	}
}

func TestValidateServiceConfig_ValidHttpMethods(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"visibilityRules": [
			{
				"pattern": "/api/test",
				"methods": ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"],
				"visibility": "PUBLIC"
			}
		]
	}`

	result := ValidateServiceConfig([]byte(json))

	if !result.IsValid() {
		t.Errorf("Expected valid config with all HTTP methods, got errors: %v", result.Errors)
	}
}

func TestValidateServiceConfig_InvalidEndpoint(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"endpoints": [
			{
				"path": "",
				"methods": [],
				"visibility": ""
			}
		]
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation errors for invalid endpoint")
	}

	if !hasErrorWithPath(result, "endpoints[0].path") {
		t.Error("Expected error for endpoints[0].path field")
	}
	if !hasErrorWithPath(result, "endpoints[0].methods") {
		t.Error("Expected error for endpoints[0].methods field")
	}
	if !hasErrorWithPath(result, "endpoints[0].visibility") {
		t.Error("Expected error for endpoints[0].visibility field")
	}
}

func TestValidateServiceConfig_WithAccessConfig(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"accessConfig": {
			"allowedIps": ["10.0.0.0/8", "192.168.0.1"],
			"allowedDomains": ["example.com"],
			"allowedSubdomains": ["*.internal.example.com"]
		}
	}`

	result := ValidateServiceConfig([]byte(json))

	if !result.IsValid() {
		t.Errorf("Expected valid config with accessConfig, got errors: %v", result.Errors)
	}
}

func TestValidateServiceConfig_EmptyAccessConfigValues(t *testing.T) {
	json := `{
		"serviceId": "my-service",
		"displayName": "My Service",
		"baseUrl": "http://localhost:8080",
		"accessConfig": {
			"allowedIps": ["", "10.0.0.0/8"]
		}
	}`

	result := ValidateServiceConfig([]byte(json))

	if result.IsValid() {
		t.Error("Expected validation error for empty allowedIps value")
	}

	if !hasErrorWithPath(result, "accessConfig.allowedIps[0]") {
		t.Error("Expected error for accessConfig.allowedIps[0] field")
	}
}

func TestServiceValidateCmd_Initialized(t *testing.T) {
	if serviceValidateCmd == nil {
		t.Fatal("serviceValidateCmd is nil")
	}

	if serviceValidateCmd.Use != "validate" {
		t.Errorf("serviceValidateCmd.Use = %q, want %q", serviceValidateCmd.Use, "validate")
	}

	if serviceValidateCmd.Short == "" {
		t.Error("serviceValidateCmd.Short should not be empty")
	}

	// Check that file flag exists
	flag := serviceValidateCmd.Flags().Lookup("file")
	if flag == nil {
		t.Error("serviceValidateCmd should have 'file' flag")
	}

	if flag != nil && flag.Shorthand != "f" {
		t.Errorf("file flag shorthand = %q, want %q", flag.Shorthand, "f")
	}
}

func TestRunServiceValidate_ValidFile(t *testing.T) {
	tmpDir := t.TempDir()
	filePath := filepath.Join(tmpDir, "service.json")

	content := `{
		"serviceId": "test-service",
		"displayName": "Test Service",
		"baseUrl": "http://localhost:8080"
	}`
	if err := os.WriteFile(filePath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test file: %v", err)
	}

	// Set the flag value
	oldFile := validateFile
	validateFile = filePath
	defer func() { validateFile = oldFile }()

	// Run validation
	err := runServiceValidate(nil, nil)

	if err != nil {
		t.Errorf("runServiceValidate() returned error for valid file: %v", err)
	}
}

func TestRunServiceValidate_InvalidFile(t *testing.T) {
	tmpDir := t.TempDir()
	filePath := filepath.Join(tmpDir, "service.json")

	content := `{
		"serviceId": "",
		"baseUrl": ""
	}`
	if err := os.WriteFile(filePath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test file: %v", err)
	}

	// Set the flag value
	oldFile := validateFile
	validateFile = filePath
	defer func() { validateFile = oldFile }()

	// Run validation
	err := runServiceValidate(nil, nil)

	if err == nil {
		t.Error("runServiceValidate() should return error for invalid file")
	}
}

func TestRunServiceValidate_NonExistentFile(t *testing.T) {
	// Set the flag value
	oldFile := validateFile
	validateFile = "/nonexistent/service.json"
	defer func() { validateFile = oldFile }()

	// Run validation
	err := runServiceValidate(nil, nil)

	if err == nil {
		t.Error("runServiceValidate() should return error for non-existent file")
	}
}

func TestIsValidServiceID(t *testing.T) {
	tests := []struct {
		id    string
		valid bool
	}{
		{"my-service", true},
		{"my_service", true},
		{"myService", true},
		{"MyService123", true},
		{"service-123", true},
		{"my service", false},
		{"my.service", false},
		{"my/service", false},
		{"", false},
	}

	for _, tt := range tests {
		t.Run(tt.id, func(t *testing.T) {
			result := isValidServiceID(tt.id)
			if result != tt.valid {
				t.Errorf("isValidServiceID(%q) = %v, want %v", tt.id, result, tt.valid)
			}
		})
	}
}

func TestIsValidURL(t *testing.T) {
	tests := []struct {
		url   string
		valid bool
	}{
		{"http://localhost:8080", true},
		{"https://example.com", true},
		{"http://10.0.0.1:3000", true},
		{"https://api.example.com/v1", true},
		{"localhost:8080", false},
		{"ftp://example.com", false},
		{"not-a-url", false},
		{"", false},
	}

	for _, tt := range tests {
		t.Run(tt.url, func(t *testing.T) {
			result := isValidURL(tt.url)
			if result != tt.valid {
				t.Errorf("isValidURL(%q) = %v, want %v", tt.url, result, tt.valid)
			}
		})
	}
}

// Helper function to check if result has an error with the given path
func hasErrorWithPath(result *ValidationResult, path string) bool {
	for _, err := range result.Errors {
		if err.Path == path {
			return true
		}
	}
	return false
}
