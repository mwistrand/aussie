package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"

	"github.com/aussie/cli/internal/config"
	"github.com/spf13/cobra"
)

var (
	// File input
	registerFile string

	// Basic service options
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
)

var registerCmd = &cobra.Command{
	Use:   "register",
	Short: "Register a service with the Aussie API gateway",
	Long: `Register a service with the Aussie API gateway.

You can provide configuration via a JSON file, command-line flags, or both.
When both are provided, command-line flags override values from the file.

Required fields (via file or flags):
  --service-id    Unique identifier for the service
  --base-url      The backend service URL

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

Examples:
  # Register using a JSON file
  aussie register -f service.json

  # Register using command-line flags
  aussie register --service-id my-service --base-url http://localhost:9090

  # Use a file as base and override specific values
  aussie register -f service.json --base-url http://production:8080

  # Register with CORS enabled
  aussie register --service-id api --base-url http://localhost:9090 \
    --cors-origins "http://localhost:3000" --cors-credentials true`,
	RunE: runRegister,
}

func init() {
	rootCmd.AddCommand(registerCmd)

	// File input
	registerCmd.Flags().StringVarP(&registerFile, "file", "f", "", "Path to service configuration JSON file")

	// Basic service options
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

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to Aussie at %s: %w", cfg.Host, err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

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
		return fmt.Errorf("permission denied: insufficient privileges to register services")
	case http.StatusConflict:
		return fmt.Errorf("service already exists: %s", string(body))
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
