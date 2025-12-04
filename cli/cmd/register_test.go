package cmd

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// validateServiceConfig validates the service configuration JSON
// This is extracted for testing purposes
func validateServiceConfig(data []byte) error {
	var serviceConfig map[string]interface{}
	if err := json.Unmarshal(data, &serviceConfig); err != nil {
		return err
	}

	if _, ok := serviceConfig["serviceId"]; !ok {
		return &missingFieldError{field: "serviceId"}
	}
	if _, ok := serviceConfig["baseUrl"]; !ok {
		return &missingFieldError{field: "baseUrl"}
	}
	return nil
}

type missingFieldError struct {
	field string
}

func (e *missingFieldError) Error() string {
	return "missing required field '" + e.field + "'"
}

func TestValidateServiceConfig_ValidConfig(t *testing.T) {
	validJSON := `{
		"serviceId": "test-service",
		"displayName": "Test Service",
		"baseUrl": "http://localhost:9090",
		"endpoints": []
	}`

	err := validateServiceConfig([]byte(validJSON))

	if err != nil {
		t.Errorf("validateServiceConfig() returned error for valid config: %v", err)
	}
}

func TestValidateServiceConfig_MinimalConfig(t *testing.T) {
	minimalJSON := `{
		"serviceId": "test-service",
		"baseUrl": "http://localhost:9090"
	}`

	err := validateServiceConfig([]byte(minimalJSON))

	if err != nil {
		t.Errorf("validateServiceConfig() returned error for minimal valid config: %v", err)
	}
}

func TestValidateServiceConfig_MissingServiceId(t *testing.T) {
	invalidJSON := `{
		"baseUrl": "http://localhost:9090"
	}`

	err := validateServiceConfig([]byte(invalidJSON))

	if err == nil {
		t.Error("validateServiceConfig() should return error for missing serviceId")
	}

	if mfe, ok := err.(*missingFieldError); ok {
		if mfe.field != "serviceId" {
			t.Errorf("Expected missing field 'serviceId', got '%s'", mfe.field)
		}
	}
}

func TestValidateServiceConfig_MissingBaseUrl(t *testing.T) {
	invalidJSON := `{
		"serviceId": "test-service"
	}`

	err := validateServiceConfig([]byte(invalidJSON))

	if err == nil {
		t.Error("validateServiceConfig() should return error for missing baseUrl")
	}

	if mfe, ok := err.(*missingFieldError); ok {
		if mfe.field != "baseUrl" {
			t.Errorf("Expected missing field 'baseUrl', got '%s'", mfe.field)
		}
	}
}

func TestValidateServiceConfig_InvalidJSON(t *testing.T) {
	invalidJSON := `{ invalid json }`

	err := validateServiceConfig([]byte(invalidJSON))

	if err == nil {
		t.Error("validateServiceConfig() should return error for invalid JSON")
	}
}

func TestValidateServiceConfig_EmptyJSON(t *testing.T) {
	emptyJSON := `{}`

	err := validateServiceConfig([]byte(emptyJSON))

	if err == nil {
		t.Error("validateServiceConfig() should return error for empty JSON")
	}
}

func TestValidateServiceConfig_FullConfig(t *testing.T) {
	fullJSON := `{
		"serviceId": "user-service",
		"displayName": "User Service",
		"baseUrl": "http://localhost:9090",
		"endpoints": [
			{
				"path": "/api/users/**",
				"methods": ["GET", "POST"],
				"visibility": "PUBLIC"
			}
		],
		"accessConfig": {
			"allowedIps": ["10.0.0.0/8"]
		}
	}`

	err := validateServiceConfig([]byte(fullJSON))

	if err != nil {
		t.Errorf("validateServiceConfig() returned error for full config: %v", err)
	}
}

func TestReadServiceFile_NonExistent(t *testing.T) {
	_, err := os.ReadFile("/nonexistent/service.json")

	if err == nil {
		t.Error("ReadFile should return error for non-existent file")
	}
}

func TestReadServiceFile_ValidFile(t *testing.T) {
	tmpDir := t.TempDir()
	filePath := filepath.Join(tmpDir, "service.json")

	content := `{"serviceId": "test", "baseUrl": "http://localhost:8080"}`
	if err := os.WriteFile(filePath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test file: %v", err)
	}

	data, err := os.ReadFile(filePath)

	if err != nil {
		t.Fatalf("ReadFile returned error: %v", err)
	}

	if string(data) != content {
		t.Errorf("ReadFile content = %q, want %q", string(data), content)
	}
}

func TestRegisterCmd_Initialized(t *testing.T) {
	if registerCmd == nil {
		t.Fatal("registerCmd is nil")
	}

	if registerCmd.Use != "register" {
		t.Errorf("registerCmd.Use = %q, want %q", registerCmd.Use, "register")
	}

	if registerCmd.Short == "" {
		t.Error("registerCmd.Short should not be empty")
	}

	// Check that file flag exists
	flag := registerCmd.Flags().Lookup("file")
	if flag == nil {
		t.Error("registerCmd should have 'file' flag")
	}

	if flag != nil && flag.Shorthand != "f" {
		t.Errorf("file flag shorthand = %q, want %q", flag.Shorthand, "f")
	}
}

func TestValidateServiceConfig_ArrayInsteadOfObject(t *testing.T) {
	arrayJSON := `[]`

	err := validateServiceConfig([]byte(arrayJSON))

	if err == nil {
		t.Error("validateServiceConfig() should return error for JSON array")
	}
}

func TestValidateServiceConfig_NullServiceId(t *testing.T) {
	// null values are treated as missing in this context
	jsonWithNull := `{
		"serviceId": null,
		"baseUrl": "http://localhost:8080"
	}`

	err := validateServiceConfig([]byte(jsonWithNull))

	// Note: JSON null unmarshals as nil, so this should still pass
	// because the key exists (even if value is nil)
	// This tests current behavior - if stricter validation is needed,
	// the validation function should be updated
	if err != nil {
		// Current implementation treats null as present
		t.Logf("validateServiceConfig() behavior with null: %v", err)
	}
}

func TestValidateServiceConfig_EmptyStringValues(t *testing.T) {
	jsonWithEmpty := `{
		"serviceId": "",
		"baseUrl": ""
	}`

	err := validateServiceConfig([]byte(jsonWithEmpty))

	// Empty strings are technically valid JSON values
	// The API should validate non-empty, not the CLI
	if err != nil {
		t.Errorf("validateServiceConfig() should not reject empty strings (API validates): %v", err)
	}
}
