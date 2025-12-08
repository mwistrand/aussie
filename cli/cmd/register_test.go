package cmd

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/spf13/cobra"
)

// validateServiceRegistration validates required fields in ServiceRegistration
func validateServiceRegistration(reg *ServiceRegistration) error {
	if reg.ServiceID == "" {
		return &missingFieldError{field: "serviceId"}
	}
	if reg.BaseURL == "" {
		return &missingFieldError{field: "baseUrl"}
	}
	return nil
}

// validateServiceConfig validates the service configuration JSON (legacy)
func validateServiceConfig(data []byte) error {
	var reg ServiceRegistration
	if err := json.Unmarshal(data, &reg); err != nil {
		return err
	}
	return validateServiceRegistration(&reg)
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
		"routePrefix": "/api/v1",
		"defaultVisibility": "PRIVATE",
		"defaultAuthRequired": true,
		"endpoints": [
			{
				"path": "/api/users/**",
				"methods": ["GET", "POST"],
				"visibility": "PUBLIC"
			}
		],
		"visibilityRules": [
			{
				"pattern": "/health",
				"methods": ["GET"],
				"visibility": "PUBLIC"
			}
		],
		"accessConfig": {
			"allowedIps": ["10.0.0.0/8"],
			"allowedDomains": ["example.com"],
			"allowedSubdomains": ["api"]
		},
		"cors": {
			"allowedOrigins": ["http://localhost:3000"],
			"allowedMethods": ["GET", "POST"],
			"allowedHeaders": ["Content-Type", "Authorization"],
			"exposedHeaders": ["X-Request-Id"],
			"allowCredentials": true,
			"maxAge": 3600
		}
	}`

	err := validateServiceConfig([]byte(fullJSON))
	if err != nil {
		t.Errorf("validateServiceConfig() returned error for full config: %v", err)
	}

	// Also verify deserialization
	var reg ServiceRegistration
	if err := json.Unmarshal([]byte(fullJSON), &reg); err != nil {
		t.Fatalf("Failed to unmarshal full config: %v", err)
	}

	if reg.RoutePrefix != "/api/v1" {
		t.Errorf("RoutePrefix = %q, want %q", reg.RoutePrefix, "/api/v1")
	}
	if reg.DefaultVisibility != "PRIVATE" {
		t.Errorf("DefaultVisibility = %q, want %q", reg.DefaultVisibility, "PRIVATE")
	}
	if reg.DefaultAuthRequired == nil || *reg.DefaultAuthRequired != true {
		t.Errorf("DefaultAuthRequired = %v, want true", reg.DefaultAuthRequired)
	}
	if len(reg.VisibilityRules) != 1 {
		t.Errorf("VisibilityRules length = %d, want 1", len(reg.VisibilityRules))
	}
	if reg.AccessConfig == nil {
		t.Error("AccessConfig should not be nil")
	}
	if reg.Cors == nil {
		t.Error("Cors should not be nil")
	} else {
		if reg.Cors.MaxAge == nil || *reg.Cors.MaxAge != 3600 {
			t.Errorf("Cors.MaxAge = %v, want 3600", reg.Cors.MaxAge)
		}
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

func TestRegisterCmd_HasAllFlags(t *testing.T) {
	expectedFlags := []string{
		"file",
		"service-id",
		"display-name",
		"base-url",
		"route-prefix",
		"default-visibility",
		"default-auth-required",
		"allowed-ips",
		"allowed-domains",
		"allowed-subdomains",
		"cors-origins",
		"cors-methods",
		"cors-headers",
		"cors-exposed-headers",
		"cors-credentials",
		"cors-max-age",
	}

	for _, flagName := range expectedFlags {
		flag := registerCmd.Flags().Lookup(flagName)
		if flag == nil {
			t.Errorf("registerCmd should have '%s' flag", flagName)
		}
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
	jsonWithNull := `{
		"serviceId": null,
		"baseUrl": "http://localhost:8080"
	}`

	err := validateServiceConfig([]byte(jsonWithNull))
	// Empty string after unmarshaling null should fail validation
	if err == nil {
		t.Error("validateServiceConfig() should return error for null serviceId")
	}
}

func TestValidateServiceConfig_EmptyStringValues(t *testing.T) {
	jsonWithEmpty := `{
		"serviceId": "",
		"baseUrl": ""
	}`

	err := validateServiceConfig([]byte(jsonWithEmpty))
	// Empty strings should now fail validation
	if err == nil {
		t.Error("validateServiceConfig() should return error for empty strings")
	}
}

func TestServiceRegistration_JSONSerialization(t *testing.T) {
	boolTrue := true
	maxAge := int64(3600)

	reg := ServiceRegistration{
		ServiceID:           "test-service",
		DisplayName:         "Test Service",
		BaseURL:             "http://localhost:9090",
		RoutePrefix:         "/api",
		DefaultVisibility:   "PRIVATE",
		DefaultAuthRequired: &boolTrue,
		VisibilityRules: []VisibilityRule{
			{Pattern: "/health", Methods: []string{"GET"}, Visibility: "PUBLIC"},
		},
		Endpoints: []EndpointConfig{
			{Path: "/users", Methods: []string{"GET", "POST"}, Visibility: "PRIVATE"},
		},
		AccessConfig: &ServiceAccessConfig{
			AllowedIPs:     []string{"10.0.0.0/8"},
			AllowedDomains: []string{"example.com"},
		},
		Cors: &CorsConfig{
			AllowedOrigins:   []string{"http://localhost:3000"},
			AllowedMethods:   []string{"GET", "POST"},
			AllowCredentials: &boolTrue,
			MaxAge:           &maxAge,
		},
	}

	data, err := json.Marshal(reg)
	if err != nil {
		t.Fatalf("Failed to marshal ServiceRegistration: %v", err)
	}

	var unmarshaled ServiceRegistration
	if err := json.Unmarshal(data, &unmarshaled); err != nil {
		t.Fatalf("Failed to unmarshal ServiceRegistration: %v", err)
	}

	if unmarshaled.ServiceID != reg.ServiceID {
		t.Errorf("ServiceID = %q, want %q", unmarshaled.ServiceID, reg.ServiceID)
	}
	if unmarshaled.RoutePrefix != reg.RoutePrefix {
		t.Errorf("RoutePrefix = %q, want %q", unmarshaled.RoutePrefix, reg.RoutePrefix)
	}
	if unmarshaled.Cors == nil || unmarshaled.Cors.MaxAge == nil || *unmarshaled.Cors.MaxAge != maxAge {
		t.Error("CORS config not preserved through serialization")
	}
}

func TestServiceRegistration_OmitEmptyFields(t *testing.T) {
	reg := ServiceRegistration{
		ServiceID: "test-service",
		BaseURL:   "http://localhost:9090",
	}

	data, err := json.Marshal(reg)
	if err != nil {
		t.Fatalf("Failed to marshal ServiceRegistration: %v", err)
	}

	var result map[string]interface{}
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatalf("Failed to unmarshal to map: %v", err)
	}

	// These fields should be omitted when empty
	omittedFields := []string{
		"displayName",
		"routePrefix",
		"defaultVisibility",
		"defaultAuthRequired",
		"visibilityRules",
		"endpoints",
		"accessConfig",
		"cors",
	}

	for _, field := range omittedFields {
		if _, exists := result[field]; exists {
			t.Errorf("Field %q should be omitted when empty, but was present", field)
		}
	}

	// These fields should always be present
	if _, exists := result["serviceId"]; !exists {
		t.Error("serviceId should always be present")
	}
	if _, exists := result["baseUrl"]; !exists {
		t.Error("baseUrl should always be present")
	}
}

func TestBuildServiceRegistration_FromFileOnly(t *testing.T) {
	tmpDir := t.TempDir()
	filePath := filepath.Join(tmpDir, "service.json")

	content := `{
		"serviceId": "file-service",
		"baseUrl": "http://file-host:8080",
		"displayName": "From File"
	}`
	if err := os.WriteFile(filePath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test file: %v", err)
	}

	// Save and restore global state
	oldRegisterFile := registerFile
	defer func() { registerFile = oldRegisterFile }()
	registerFile = filePath

	cmd := &cobra.Command{}
	cmd.Flags().StringVarP(&registerFile, "file", "f", filePath, "")
	cmd.Flags().StringVar(&serviceID, "service-id", "", "")
	cmd.Flags().StringVar(&baseURL, "base-url", "", "")
	cmd.Flags().StringVar(&displayName, "display-name", "", "")

	reg, err := buildServiceRegistration(cmd)
	if err != nil {
		t.Fatalf("buildServiceRegistration() error: %v", err)
	}

	if reg.ServiceID != "file-service" {
		t.Errorf("ServiceID = %q, want %q", reg.ServiceID, "file-service")
	}
	if reg.BaseURL != "http://file-host:8080" {
		t.Errorf("BaseURL = %q, want %q", reg.BaseURL, "http://file-host:8080")
	}
	if reg.DisplayName != "From File" {
		t.Errorf("DisplayName = %q, want %q", reg.DisplayName, "From File")
	}
}

func TestBuildServiceRegistration_FlagsOnly(t *testing.T) {
	// Save and restore global state
	oldRegisterFile := registerFile
	oldServiceID := serviceID
	oldBaseURL := baseURL
	oldDisplayName := displayName
	defer func() {
		registerFile = oldRegisterFile
		serviceID = oldServiceID
		baseURL = oldBaseURL
		displayName = oldDisplayName
	}()

	registerFile = ""
	serviceID = "flag-service"
	baseURL = "http://flag-host:9090"
	displayName = "From Flags"

	cmd := &cobra.Command{}
	cmd.Flags().StringVarP(&registerFile, "file", "f", "", "")
	cmd.Flags().StringVar(&serviceID, "service-id", "flag-service", "")
	cmd.Flags().StringVar(&baseURL, "base-url", "http://flag-host:9090", "")
	cmd.Flags().StringVar(&displayName, "display-name", "From Flags", "")

	// Mark flags as changed
	cmd.Flags().Set("service-id", "flag-service")
	cmd.Flags().Set("base-url", "http://flag-host:9090")
	cmd.Flags().Set("display-name", "From Flags")

	reg, err := buildServiceRegistration(cmd)
	if err != nil {
		t.Fatalf("buildServiceRegistration() error: %v", err)
	}

	if reg.ServiceID != "flag-service" {
		t.Errorf("ServiceID = %q, want %q", reg.ServiceID, "flag-service")
	}
	if reg.BaseURL != "http://flag-host:9090" {
		t.Errorf("BaseURL = %q, want %q", reg.BaseURL, "http://flag-host:9090")
	}
	if reg.DisplayName != "From Flags" {
		t.Errorf("DisplayName = %q, want %q", reg.DisplayName, "From Flags")
	}
}

func TestBuildServiceRegistration_FlagsOverrideFile(t *testing.T) {
	tmpDir := t.TempDir()
	filePath := filepath.Join(tmpDir, "service.json")

	content := `{
		"serviceId": "file-service",
		"baseUrl": "http://file-host:8080",
		"displayName": "From File",
		"defaultVisibility": "PRIVATE"
	}`
	if err := os.WriteFile(filePath, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test file: %v", err)
	}

	// Save and restore global state
	oldRegisterFile := registerFile
	oldBaseURL := baseURL
	defer func() {
		registerFile = oldRegisterFile
		baseURL = oldBaseURL
	}()

	registerFile = filePath
	baseURL = "http://override-host:9090"

	cmd := &cobra.Command{}
	cmd.Flags().StringVarP(&registerFile, "file", "f", filePath, "")
	cmd.Flags().StringVar(&serviceID, "service-id", "", "")
	cmd.Flags().StringVar(&baseURL, "base-url", "http://override-host:9090", "")
	cmd.Flags().StringVar(&displayName, "display-name", "", "")
	cmd.Flags().StringVar(&defaultVisibility, "default-visibility", "", "")

	// Only base-url flag is changed
	cmd.Flags().Set("base-url", "http://override-host:9090")

	reg, err := buildServiceRegistration(cmd)
	if err != nil {
		t.Fatalf("buildServiceRegistration() error: %v", err)
	}

	// ServiceID should come from file
	if reg.ServiceID != "file-service" {
		t.Errorf("ServiceID = %q, want %q", reg.ServiceID, "file-service")
	}
	// BaseURL should be overridden by flag
	if reg.BaseURL != "http://override-host:9090" {
		t.Errorf("BaseURL = %q, want %q", reg.BaseURL, "http://override-host:9090")
	}
	// DisplayName should come from file
	if reg.DisplayName != "From File" {
		t.Errorf("DisplayName = %q, want %q", reg.DisplayName, "From File")
	}
	// DefaultVisibility should come from file (flag not set)
	if reg.DefaultVisibility != "PRIVATE" {
		t.Errorf("DefaultVisibility = %q, want %q", reg.DefaultVisibility, "PRIVATE")
	}
}

func TestBuildServiceRegistration_CorsFlags(t *testing.T) {
	// Save and restore global state
	oldRegisterFile := registerFile
	oldCorsOrigins := corsOrigins
	oldCorsMethods := corsMethods
	oldCorsCredentials := corsCredentials
	oldCorsMaxAge := corsMaxAge
	defer func() {
		registerFile = oldRegisterFile
		corsOrigins = oldCorsOrigins
		corsMethods = oldCorsMethods
		corsCredentials = oldCorsCredentials
		corsMaxAge = oldCorsMaxAge
	}()

	registerFile = ""
	corsOrigins = []string{"http://localhost:3000", "http://localhost:8080"}
	corsMethods = []string{"GET", "POST"}
	corsCredentials = "true"
	corsMaxAge = 7200

	cmd := &cobra.Command{}
	cmd.Flags().StringVarP(&registerFile, "file", "f", "", "")
	cmd.Flags().StringVar(&serviceID, "service-id", "", "")
	cmd.Flags().StringVar(&baseURL, "base-url", "", "")
	cmd.Flags().StringSliceVar(&corsOrigins, "cors-origins", nil, "")
	cmd.Flags().StringSliceVar(&corsMethods, "cors-methods", nil, "")
	cmd.Flags().StringVar(&corsCredentials, "cors-credentials", "", "")
	cmd.Flags().Int64Var(&corsMaxAge, "cors-max-age", 0, "")

	cmd.Flags().Set("cors-origins", "http://localhost:3000,http://localhost:8080")
	cmd.Flags().Set("cors-methods", "GET,POST")
	cmd.Flags().Set("cors-credentials", "true")
	cmd.Flags().Set("cors-max-age", "7200")

	reg, err := buildServiceRegistration(cmd)
	if err != nil {
		t.Fatalf("buildServiceRegistration() error: %v", err)
	}

	if reg.Cors == nil {
		t.Fatal("Cors should not be nil when CORS flags are set")
	}
	if len(reg.Cors.AllowedOrigins) != 2 {
		t.Errorf("AllowedOrigins length = %d, want 2", len(reg.Cors.AllowedOrigins))
	}
	if reg.Cors.AllowCredentials == nil || *reg.Cors.AllowCredentials != true {
		t.Error("AllowCredentials should be true")
	}
	if reg.Cors.MaxAge == nil || *reg.Cors.MaxAge != 7200 {
		t.Errorf("MaxAge = %v, want 7200", reg.Cors.MaxAge)
	}
}

func TestBuildServiceRegistration_AccessConfigFlags(t *testing.T) {
	// Save and restore global state
	oldRegisterFile := registerFile
	oldAllowedIPs := allowedIPs
	oldAllowedDomains := allowedDomains
	defer func() {
		registerFile = oldRegisterFile
		allowedIPs = oldAllowedIPs
		allowedDomains = oldAllowedDomains
	}()

	registerFile = ""
	allowedIPs = []string{"10.0.0.0/8", "192.168.0.0/16"}
	allowedDomains = []string{"example.com"}

	cmd := &cobra.Command{}
	cmd.Flags().StringVarP(&registerFile, "file", "f", "", "")
	cmd.Flags().StringVar(&serviceID, "service-id", "", "")
	cmd.Flags().StringVar(&baseURL, "base-url", "", "")
	cmd.Flags().StringSliceVar(&allowedIPs, "allowed-ips", nil, "")
	cmd.Flags().StringSliceVar(&allowedDomains, "allowed-domains", nil, "")
	cmd.Flags().StringSliceVar(&allowedSubdomains, "allowed-subdomains", nil, "")

	cmd.Flags().Set("allowed-ips", "10.0.0.0/8,192.168.0.0/16")
	cmd.Flags().Set("allowed-domains", "example.com")

	reg, err := buildServiceRegistration(cmd)
	if err != nil {
		t.Fatalf("buildServiceRegistration() error: %v", err)
	}

	if reg.AccessConfig == nil {
		t.Fatal("AccessConfig should not be nil when access flags are set")
	}
	if len(reg.AccessConfig.AllowedIPs) != 2 {
		t.Errorf("AllowedIPs length = %d, want 2", len(reg.AccessConfig.AllowedIPs))
	}
	if len(reg.AccessConfig.AllowedDomains) != 1 {
		t.Errorf("AllowedDomains length = %d, want 1", len(reg.AccessConfig.AllowedDomains))
	}
}

func TestHasCorsFlags(t *testing.T) {
	tests := []struct {
		name     string
		setFlags []string
		want     bool
	}{
		{"no flags", []string{}, false},
		{"cors-origins", []string{"cors-origins"}, true},
		{"cors-methods", []string{"cors-methods"}, true},
		{"cors-headers", []string{"cors-headers"}, true},
		{"cors-exposed-headers", []string{"cors-exposed-headers"}, true},
		{"cors-credentials", []string{"cors-credentials"}, true},
		{"cors-max-age", []string{"cors-max-age"}, true},
		{"multiple", []string{"cors-origins", "cors-credentials"}, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cmd := &cobra.Command{}
			cmd.Flags().StringSliceVar(&corsOrigins, "cors-origins", nil, "")
			cmd.Flags().StringSliceVar(&corsMethods, "cors-methods", nil, "")
			cmd.Flags().StringSliceVar(&corsHeaders, "cors-headers", nil, "")
			cmd.Flags().StringSliceVar(&corsExposedHeaders, "cors-exposed-headers", nil, "")
			cmd.Flags().StringVar(&corsCredentials, "cors-credentials", "", "")
			cmd.Flags().Int64Var(&corsMaxAge, "cors-max-age", 0, "")

			for _, flag := range tt.setFlags {
				switch flag {
				case "cors-origins":
					cmd.Flags().Set(flag, "http://localhost")
				case "cors-methods":
					cmd.Flags().Set(flag, "GET")
				case "cors-headers":
					cmd.Flags().Set(flag, "Content-Type")
				case "cors-exposed-headers":
					cmd.Flags().Set(flag, "X-Custom")
				case "cors-credentials":
					cmd.Flags().Set(flag, "true")
				case "cors-max-age":
					cmd.Flags().Set(flag, "3600")
				}
			}

			got := hasCorsFlags(cmd)
			if got != tt.want {
				t.Errorf("hasCorsFlags() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestVisibilityRule_JSONRoundTrip(t *testing.T) {
	rule := VisibilityRule{
		Pattern:    "/api/health",
		Methods:    []string{"GET", "HEAD"},
		Visibility: "PUBLIC",
	}

	data, err := json.Marshal(rule)
	if err != nil {
		t.Fatalf("Marshal error: %v", err)
	}

	var unmarshaled VisibilityRule
	if err := json.Unmarshal(data, &unmarshaled); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}

	if unmarshaled.Pattern != rule.Pattern {
		t.Errorf("Pattern = %q, want %q", unmarshaled.Pattern, rule.Pattern)
	}
	if len(unmarshaled.Methods) != len(rule.Methods) {
		t.Errorf("Methods length = %d, want %d", len(unmarshaled.Methods), len(rule.Methods))
	}
}

func TestEndpointConfig_JSONRoundTrip(t *testing.T) {
	authRequired := true
	endpoint := EndpointConfig{
		Path:         "/api/users",
		Methods:      []string{"GET", "POST", "PUT"},
		Visibility:   "PRIVATE",
		PathRewrite:  "/v1/users",
		AuthRequired: &authRequired,
	}

	data, err := json.Marshal(endpoint)
	if err != nil {
		t.Fatalf("Marshal error: %v", err)
	}

	var unmarshaled EndpointConfig
	if err := json.Unmarshal(data, &unmarshaled); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}

	if unmarshaled.PathRewrite != endpoint.PathRewrite {
		t.Errorf("PathRewrite = %q, want %q", unmarshaled.PathRewrite, endpoint.PathRewrite)
	}
	if unmarshaled.AuthRequired == nil || *unmarshaled.AuthRequired != authRequired {
		t.Error("AuthRequired not preserved")
	}
}
