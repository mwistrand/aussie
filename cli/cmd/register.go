package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/aussie/cli/internal/config"
	"github.com/spf13/cobra"
)

var (
	// File input
	registerFile string

	// Basic service options
	version             int64
	serviceID           string
	displayName         string
	baseURL             string
	routePrefix         string
	defaultVisibility   string
	defaultAuthRequired string // "true", "false", or "" for not set

	// Access config options
	allowedIPs        []string
	allowedDomains    []string
	allowedSubdomains []string

	// CORS options
	corsOrigins        []string
	corsMethods        []string
	corsHeaders        []string
	corsExposedHeaders []string
	corsCredentials    string // "true", "false", or "" for not set
	corsMaxAge         int64

	// Permission policy options
	permissionPolicyFile string
)

var registerCmd = &cobra.Command{
	Use:   "register",
	Short: "Register a service with the Aussie API gateway",
	Long: `Register a service with the Aussie API gateway.

You can provide configuration via a JSON file, command-line flags, or both.
When both are provided, command-line flags override values from the file.

Required fields (via file or flags):
  --version       Configuration version (must be current version + 1 to update)
  --service-id    Unique identifier for the service
  --base-url      The backend service URL

Version Requirements:
  For new services, use version 1. To update an existing service, the version
  must be exactly the current stored version plus one. For example, if the
  current version is 1, you must provide version 2 to update.

Optional fields:
  --display-name          Human-readable name (defaults to service-id)
  --route-prefix          URL prefix for routing
  --default-visibility    Default visibility: PUBLIC or PRIVATE
  --default-auth-required Whether authentication is required by default

Access Control:
  --allowed-ips           IP addresses/CIDR ranges allowed to access
  --allowed-domains       Domains allowed to access
  --allowed-subdomains    Subdomains allowed to access

CORS Configuration:
  --cors-origins          Allowed origins for CORS
  --cors-methods          Allowed HTTP methods for CORS
  --cors-headers          Allowed headers for CORS
  --cors-exposed-headers  Headers exposed to the browser
  --cors-credentials      Allow credentials (true/false)
  --cors-max-age          Max age for preflight cache (seconds)

Permission Policy:
  --permission-policy-file  Path to JSON file containing permission policy

Examples:
  # Register a new service using a JSON file (file must include version: 1)
  aussie register -f service.json

  # Register a new service using command-line flags
  aussie register --version 1 --service-id my-service --base-url http://localhost:9090

  # Update an existing service (increment version from current)
  aussie register -f service.json --version 2

  # Use a file as base and override specific values
  aussie register -f service.json --base-url http://production:8080

  # Register with CORS enabled
  aussie register --version 1 --service-id api --base-url http://localhost:9090 \
    --cors-origins "http://localhost:3000" --cors-credentials true`,
	RunE: runRegister,
}

func init() {
	rootCmd.AddCommand(registerCmd)

	// File input
	registerCmd.Flags().StringVarP(&registerFile, "file", "f", "", "Path to service configuration JSON file")

	// Basic service options
	registerCmd.Flags().Int64Var(&version, "version", 0, "Current configuration version")
	registerCmd.Flags().StringVar(&serviceID, "service-id", "", "Unique identifier for the service")
	registerCmd.Flags().StringVar(&displayName, "display-name", "", "Human-readable name for the service")
	registerCmd.Flags().StringVar(&baseURL, "base-url", "", "The backend service URL")
	registerCmd.Flags().StringVar(&routePrefix, "route-prefix", "", "URL prefix for routing")
	registerCmd.Flags().StringVar(&defaultVisibility, "default-visibility", "", "Default visibility: PUBLIC or PRIVATE")
	registerCmd.Flags().StringVar(&defaultAuthRequired, "default-auth-required", "", "Whether authentication is required by default (true/false)")

	// Access config options
	registerCmd.Flags().StringSliceVar(&allowedIPs, "allowed-ips", nil, "IP addresses/CIDR ranges allowed to access (comma-separated or repeated)")
	registerCmd.Flags().StringSliceVar(&allowedDomains, "allowed-domains", nil, "Domains allowed to access (comma-separated or repeated)")
	registerCmd.Flags().StringSliceVar(&allowedSubdomains, "allowed-subdomains", nil, "Subdomains allowed to access (comma-separated or repeated)")

	// CORS options
	registerCmd.Flags().StringSliceVar(&corsOrigins, "cors-origins", nil, "Allowed origins for CORS (comma-separated or repeated)")
	registerCmd.Flags().StringSliceVar(&corsMethods, "cors-methods", nil, "Allowed HTTP methods for CORS (comma-separated or repeated)")
	registerCmd.Flags().StringSliceVar(&corsHeaders, "cors-headers", nil, "Allowed headers for CORS (comma-separated or repeated)")
	registerCmd.Flags().StringSliceVar(&corsExposedHeaders, "cors-exposed-headers", nil, "Headers exposed to the browser (comma-separated or repeated)")
	registerCmd.Flags().StringVar(&corsCredentials, "cors-credentials", "", "Allow credentials for CORS (true/false)")
	registerCmd.Flags().Int64Var(&corsMaxAge, "cors-max-age", 0, "Max age for preflight cache in seconds")

	// Permission policy options
	registerCmd.Flags().StringVar(&permissionPolicyFile, "permission-policy-file", "", "Path to JSON file containing permission policy")
}

func runRegister(cmd *cobra.Command, args []string) error {
	// Load CLI configuration
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Check if server flag was provided (overrides config)
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" && serverFlag != "http://localhost:8080" {
		cfg.Host = serverFlag
	}

	// Build service registration from file and/or flags
	registration, err := buildServiceRegistration(cmd)
	if err != nil {
		return err
	}

	// Validate required fields
	if registration.Version < 1 {
		return fmt.Errorf("a positive version is required (use --version or provide in JSON file)")
	}
	if registration.ServiceID == "" {
		return fmt.Errorf("service-id is required (use --service-id or provide in JSON file)")
	}
	if registration.BaseURL == "" {
		return fmt.Errorf("base-url is required (use --base-url or provide in JSON file)")
	}

	// Serialize to JSON
	data, err := json.Marshal(registration)
	if err != nil {
		return fmt.Errorf("failed to serialize registration: %w", err)
	}

	// Send registration request to Aussie
	url := fmt.Sprintf("%s/admin/services", cfg.Host)
	req, err := http.NewRequest("POST", url, bytes.NewReader(data))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	// Add authentication if available
	if cfg.ApiKey != "" {
		req.Header.Set("Authorization", "Bearer "+cfg.ApiKey)
	}

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to Aussie at %s: %w", cfg.Host, err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("failed to read response: %w", err)
	}

	if resp.StatusCode == http.StatusCreated {
		// Pretty print the response
		var result map[string]interface{}
		if err := json.Unmarshal(body, &result); err == nil {
			prettyJSON, _ := json.MarshalIndent(result, "", "  ")
			fmt.Printf("Service registered successfully!\n\n%s\n", string(prettyJSON))
		} else {
			fmt.Printf("Service registered successfully!\n%s\n", string(body))
		}
		return nil
	}

	// Handle errors
	switch resp.StatusCode {
	case http.StatusBadRequest:
		return fmt.Errorf("invalid service configuration: %s", string(body))
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication required: use 'aussie auth login' to authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("permission denied: insufficient privileges")
	case http.StatusConflict:
		return fmt.Errorf("version conflict: %s", string(body))
	default:
		return fmt.Errorf("registration failed (HTTP %d): %s", resp.StatusCode, string(body))
	}
}

func buildServiceRegistration(cmd *cobra.Command) (*ServiceRegistration, error) {
	var registration ServiceRegistration

	// If a file is provided, start with its contents
	if registerFile != "" {
		data, err := os.ReadFile(registerFile)
		if err != nil {
			return nil, fmt.Errorf("failed to read file %s: %w", registerFile, err)
		}

		if err := json.Unmarshal(data, &registration); err != nil {
			return nil, fmt.Errorf("invalid JSON in %s: %w", registerFile, err)
		}
	}

	// Override with command-line flags (only if explicitly set)
	if cmd.Flags().Changed("version") {
		registration.Version = version
	}
	if cmd.Flags().Changed("service-id") {
		registration.ServiceID = serviceID
	}
	if cmd.Flags().Changed("display-name") {
		registration.DisplayName = displayName
	}
	if cmd.Flags().Changed("base-url") {
		registration.BaseURL = baseURL
	}
	if cmd.Flags().Changed("route-prefix") {
		registration.RoutePrefix = routePrefix
	}
	if cmd.Flags().Changed("default-visibility") {
		registration.DefaultVisibility = defaultVisibility
	}
	if cmd.Flags().Changed("default-auth-required") {
		val := defaultAuthRequired == "true"
		registration.DefaultAuthRequired = &val
	}

	// Access config - build if any access flags are set
	if cmd.Flags().Changed("allowed-ips") || cmd.Flags().Changed("allowed-domains") || cmd.Flags().Changed("allowed-subdomains") {
		if registration.AccessConfig == nil {
			registration.AccessConfig = &ServiceAccessConfig{}
		}
		if cmd.Flags().Changed("allowed-ips") {
			registration.AccessConfig.AllowedIPs = allowedIPs
		}
		if cmd.Flags().Changed("allowed-domains") {
			registration.AccessConfig.AllowedDomains = allowedDomains
		}
		if cmd.Flags().Changed("allowed-subdomains") {
			registration.AccessConfig.AllowedSubdomains = allowedSubdomains
		}
	}

	// CORS config - build if any CORS flags are set
	if hasCorsFlags(cmd) {
		if registration.Cors == nil {
			registration.Cors = &CorsConfig{}
		}
		if cmd.Flags().Changed("cors-origins") {
			registration.Cors.AllowedOrigins = corsOrigins
		}
		if cmd.Flags().Changed("cors-methods") {
			registration.Cors.AllowedMethods = corsMethods
		}
		if cmd.Flags().Changed("cors-headers") {
			registration.Cors.AllowedHeaders = corsHeaders
		}
		if cmd.Flags().Changed("cors-exposed-headers") {
			registration.Cors.ExposedHeaders = corsExposedHeaders
		}
		if cmd.Flags().Changed("cors-credentials") {
			val := corsCredentials == "true"
			registration.Cors.AllowCredentials = &val
		}
		if cmd.Flags().Changed("cors-max-age") {
			registration.Cors.MaxAge = &corsMaxAge
		}
	}

	// Permission policy - load from file if provided
	if cmd.Flags().Changed("permission-policy-file") && permissionPolicyFile != "" {
		policyData, err := os.ReadFile(permissionPolicyFile)
		if err != nil {
			return nil, fmt.Errorf("failed to read permission policy file %s: %w", permissionPolicyFile, err)
		}

		var policy ServicePermissionPolicy
		if err := json.Unmarshal(policyData, &policy); err != nil {
			return nil, fmt.Errorf("invalid JSON in permission policy file %s: %w", permissionPolicyFile, err)
		}
		registration.PermissionPolicy = &policy
	}

	return &registration, nil
}

func hasCorsFlags(cmd *cobra.Command) bool {
	return cmd.Flags().Changed("cors-origins") ||
		cmd.Flags().Changed("cors-methods") ||
		cmd.Flags().Changed("cors-headers") ||
		cmd.Flags().Changed("cors-exposed-headers") ||
		cmd.Flags().Changed("cors-credentials") ||
		cmd.Flags().Changed("cors-max-age")
}
