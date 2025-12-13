package cmd

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"

	"github.com/spf13/cobra"
)

var validateFile string

var serviceValidateCmd = &cobra.Command{
	Use:   "validate",
	Short: "Validate a service configuration file against the schema",
	Long: `Validate a service configuration JSON file against the Aussie service schema.

This command checks that your service configuration is valid before attempting
to register it with the gateway. It validates:
- Required fields (serviceId, displayName, baseUrl)
- Field types and formats
- Visibility rules and endpoint configurations
- Optional fields like routePrefix, defaultVisibility, and accessConfig

Examples:
  aussie service validate -f my-service.json
  aussie service validate --file ./config/service.json`,
	RunE: runServiceValidate,
}

func init() {
	serviceCmd.AddCommand(serviceValidateCmd)
	serviceValidateCmd.Flags().StringVarP(&validateFile, "file", "f", "", "Path to the service configuration JSON file (required)")
	serviceValidateCmd.MarkFlagRequired("file")
}

// ValidationError represents a validation error with path context
type ValidationError struct {
	Path    string
	Message string
}

func (e ValidationError) Error() string {
	if e.Path != "" {
		return fmt.Sprintf("%s: %s", e.Path, e.Message)
	}
	return e.Message
}

// ValidationResult contains all validation errors found
type ValidationResult struct {
	Errors []ValidationError
}

func (r *ValidationResult) AddError(path, message string) {
	r.Errors = append(r.Errors, ValidationError{Path: path, Message: message})
}

func (r *ValidationResult) IsValid() bool {
	return len(r.Errors) == 0
}

var validVisibilities = map[string]bool{
	"PUBLIC":  true,
	"PRIVATE": true,
}

var validMethods = map[string]bool{
	"GET":     true,
	"POST":    true,
	"PUT":     true,
	"PATCH":   true,
	"DELETE":  true,
	"HEAD":    true,
	"OPTIONS": true,
}

var validEndpointTypes = map[string]bool{
	"HTTP":      true,
	"WEBSOCKET": true,
}

func runServiceValidate(cmd *cobra.Command, args []string) error {
	// Read the configuration file
	data, err := os.ReadFile(validateFile)
	if err != nil {
		return fmt.Errorf("failed to read file %s: %w", validateFile, err)
	}

	// Validate the configuration
	result := ValidateServiceConfig(data)

	if result.IsValid() {
		fmt.Printf("Configuration is valid.\n")
		return nil
	}

	// Print errors
	fmt.Printf("Configuration validation failed with %d error(s):\n\n", len(result.Errors))
	for i, err := range result.Errors {
		fmt.Printf("  %d. %s\n", i+1, err.Error())
	}
	fmt.Println()

	return fmt.Errorf("validation failed")
}

// ValidateServiceConfig validates a service configuration JSON
func ValidateServiceConfig(data []byte) *ValidationResult {
	result := &ValidationResult{}

	// First, check if it's valid JSON
	var rawConfig map[string]interface{}
	if err := json.Unmarshal(data, &rawConfig); err != nil {
		result.AddError("", fmt.Sprintf("invalid JSON: %v", err))
		return result
	}

	// Parse into struct for validation
	var config ServiceRegistration
	if err := json.Unmarshal(data, &config); err != nil {
		result.AddError("", fmt.Sprintf("failed to parse configuration: %v", err))
		return result
	}

	// Required fields
	if config.ServiceID == "" {
		result.AddError("serviceId", "required field is missing or empty")
	} else if !isValidServiceID(config.ServiceID) {
		result.AddError("serviceId", "must be a valid identifier (alphanumeric, hyphens, underscores)")
	}

	if config.DisplayName == "" {
		result.AddError("displayName", "required field is missing or empty")
	}

	if config.BaseURL == "" {
		result.AddError("baseUrl", "required field is missing or empty")
	} else if !isValidURL(config.BaseURL) {
		result.AddError("baseUrl", "must be a valid URL (http:// or https://)")
	}

	// Optional fields validation
	if config.DefaultVisibility != "" {
		if !validVisibilities[strings.ToUpper(config.DefaultVisibility)] {
			result.AddError("defaultVisibility", "must be 'PUBLIC' or 'PRIVATE'")
		}
	}

	if config.RoutePrefix != "" {
		if !strings.HasPrefix(config.RoutePrefix, "/") {
			result.AddError("routePrefix", "must start with '/'")
		}
	}

	// Validate visibility rules
	for i, rule := range config.VisibilityRules {
		path := fmt.Sprintf("visibilityRules[%d]", i)
		validateVisibilityRule(rule, path, result)
	}

	// Validate endpoints (v1 format)
	for i, endpoint := range config.Endpoints {
		path := fmt.Sprintf("endpoints[%d]", i)
		validateEndpoint(endpoint, path, result)
	}

	// Validate access config
	if config.AccessConfig != nil {
		validateAccessConfig(config.AccessConfig, "accessConfig", result)
	}

	// Validate rate limit config
	if config.RateLimitConfig != nil {
		validateRateLimitConfig(config.RateLimitConfig, "rateLimitConfig", result)
	}

	return result
}

func validateVisibilityRule(rule VisibilityRule, path string, result *ValidationResult) {
	if rule.Pattern == "" {
		result.AddError(path+".pattern", "required field is missing or empty")
	} else if !strings.HasPrefix(rule.Pattern, "/") {
		result.AddError(path+".pattern", "must start with '/'")
	}

	if rule.Visibility == "" {
		result.AddError(path+".visibility", "required field is missing or empty")
	} else if !validVisibilities[strings.ToUpper(rule.Visibility)] {
		result.AddError(path+".visibility", "must be 'PUBLIC' or 'PRIVATE'")
	}

	// Methods are optional, but if provided must be valid
	for j, method := range rule.Methods {
		if !validMethods[strings.ToUpper(method)] {
			result.AddError(fmt.Sprintf("%s.methods[%d]", path, j),
				fmt.Sprintf("invalid HTTP method '%s'", method))
		}
	}
}

func validateEndpoint(endpoint EndpointConfig, path string, result *ValidationResult) {
	if endpoint.Path == "" {
		result.AddError(path+".path", "required field is missing or empty")
	} else if !strings.HasPrefix(endpoint.Path, "/") {
		result.AddError(path+".path", "must start with '/'")
	}

	// Validate endpoint type if specified
	isWebSocket := false
	if endpoint.Type != "" {
		upperType := strings.ToUpper(endpoint.Type)
		if !validEndpointTypes[upperType] {
			result.AddError(path+".type", "must be 'HTTP' or 'WEBSOCKET'")
		}
		isWebSocket = upperType == "WEBSOCKET"
	}

	// WebSocket endpoints don't require methods (they always use GET for upgrade)
	// HTTP endpoints require methods
	if !isWebSocket {
		if len(endpoint.Methods) == 0 {
			result.AddError(path+".methods", "required field is missing or empty")
		} else {
			for j, method := range endpoint.Methods {
				upperMethod := strings.ToUpper(method)
				if !validMethods[upperMethod] && upperMethod != "*" {
					result.AddError(fmt.Sprintf("%s.methods[%d]", path, j),
						fmt.Sprintf("invalid HTTP method '%s'", method))
				}
			}
		}
	} else {
		// WebSocket endpoints: validate methods if provided (should only be GET or empty)
		if len(endpoint.Methods) > 0 {
			for j, method := range endpoint.Methods {
				upperMethod := strings.ToUpper(method)
				if upperMethod != "GET" && upperMethod != "*" {
					result.AddError(fmt.Sprintf("%s.methods[%d]", path, j),
						fmt.Sprintf("WebSocket endpoints only support GET method, got '%s'", method))
				}
			}
		}
	}

	if endpoint.Visibility == "" {
		result.AddError(path+".visibility", "required field is missing or empty")
	} else if !validVisibilities[strings.ToUpper(endpoint.Visibility)] {
		result.AddError(path+".visibility", "must be 'PUBLIC' or 'PRIVATE'")
	}
}

func validateAccessConfig(config *ServiceAccessConfig, path string, result *ValidationResult) {
	// Validate IP addresses/CIDR notation
	for i, ip := range config.AllowedIPs {
		if ip == "" {
			result.AddError(fmt.Sprintf("%s.allowedIps[%d]", path, i), "empty value")
		}
	}

	// Validate domains
	for i, domain := range config.AllowedDomains {
		if domain == "" {
			result.AddError(fmt.Sprintf("%s.allowedDomains[%d]", path, i), "empty value")
		}
	}

	// Validate subdomain patterns
	for i, subdomain := range config.AllowedSubdomains {
		if subdomain == "" {
			result.AddError(fmt.Sprintf("%s.allowedSubdomains[%d]", path, i), "empty value")
		}
	}
}

func isValidServiceID(id string) bool {
	if id == "" {
		return false
	}
	for _, c := range id {
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
			(c >= '0' && c <= '9') || c == '-' || c == '_') {
			return false
		}
	}
	return true
}

func isValidURL(url string) bool {
	return strings.HasPrefix(url, "http://") || strings.HasPrefix(url, "https://")
}

func validateRateLimitConfig(config *RateLimitConfig, path string, result *ValidationResult) {
	if config == nil {
		return
	}

	if config.RequestsPerWindow != nil && *config.RequestsPerWindow <= 0 {
		result.AddError(path+".requestsPerWindow", "must be greater than 0")
	}

	if config.WindowSeconds != nil && *config.WindowSeconds <= 0 {
		result.AddError(path+".windowSeconds", "must be greater than 0")
	}

	if config.BurstCapacity != nil && *config.BurstCapacity <= 0 {
		result.AddError(path+".burstCapacity", "must be greater than 0")
	}

	// Validate WebSocket config if present
	if config.WebSocket != nil {
		validateWebSocketRateLimitConfig(config.WebSocket, path+".websocket", result)
	}
}

func validateWebSocketRateLimitConfig(config *WebSocketRateLimitConfig, path string, result *ValidationResult) {
	if config == nil {
		return
	}

	if config.Connection != nil {
		validateRateLimitValues(config.Connection, path+".connection", result)
	}

	if config.Message != nil {
		validateRateLimitValues(config.Message, path+".message", result)
	}
}

func validateRateLimitValues(config *RateLimitValues, path string, result *ValidationResult) {
	if config == nil {
		return
	}

	if config.RequestsPerWindow != nil && *config.RequestsPerWindow <= 0 {
		result.AddError(path+".requestsPerWindow", "must be greater than 0")
	}

	if config.WindowSeconds != nil && *config.WindowSeconds <= 0 {
		result.AddError(path+".windowSeconds", "must be greater than 0")
	}

	if config.BurstCapacity != nil && *config.BurstCapacity <= 0 {
		result.AddError(path+".burstCapacity", "must be greater than 0")
	}
}
