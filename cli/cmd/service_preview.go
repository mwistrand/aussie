package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/aussie/cli/internal/config"
	"github.com/spf13/cobra"
)

// validServiceIDPattern matches alphanumeric characters, hyphens, and underscores only
var validServiceIDPattern = regexp.MustCompile(`^[a-zA-Z0-9_-]+$`)

var servicePreviewCmd = &cobra.Command{
	Use:   "preview <service-id>",
	Short: "Preview visibility settings for a registered service",
	Long: `Preview the visibility settings for a service registered with Aussie.

This command fetches the service configuration from the gateway and displays
which endpoints are PUBLIC or PRIVATE based on the visibility rules and
default visibility settings.

Examples:
  aussie service preview my-service
  aussie service preview user-service -s http://aussie.example.com:8080`,
	Args: cobra.ExactArgs(1),
	RunE: runServicePreview,
}

func init() {
	serviceCmd.AddCommand(servicePreviewCmd)
}

// ServiceResponse represents the API response for a service
type ServiceResponse struct {
	ServiceID         string               `json:"serviceId"`
	DisplayName       string               `json:"displayName"`
	BaseURL           string               `json:"baseUrl"`
	RoutePrefix       string               `json:"routePrefix"`
	DefaultVisibility string               `json:"defaultVisibility"`
	VisibilityRules   []VisibilityRule     `json:"visibilityRules"`
	Endpoints         []EndpointConfig     `json:"endpoints"`
	AccessConfig      *ServiceAccessConfig `json:"accessConfig"`
}

func runServicePreview(cmd *cobra.Command, args []string) error {
	serviceID := args[0]

	// Validate service ID to prevent path traversal attacks
	if !validServiceIDPattern.MatchString(serviceID) {
		return fmt.Errorf("invalid service ID format: must contain only alphanumeric characters, hyphens, and underscores")
	}

	// Load configuration
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Check if server flag was provided (overrides config)
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" && serverFlag != "http://localhost:8080" {
		cfg.Host = serverFlag
	}

	// Fetch service from API
	url := fmt.Sprintf("%s/admin/services/%s", cfg.Host, serviceID)
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return fmt.Errorf("failed to connect to Aussie at %s: %w", cfg.Host, err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("failed to read response: %w", err)
	}

	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("service '%s' not found", serviceID)
	}

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("failed to fetch service (HTTP %d): %s", resp.StatusCode, string(body))
	}

	// Parse response
	var service ServiceResponse
	if err := json.Unmarshal(body, &service); err != nil {
		return fmt.Errorf("failed to parse service response: %w", err)
	}

	// Display preview
	printServicePreview(service)
	return nil
}

func printServicePreview(service ServiceResponse) {
	// Header
	fmt.Printf("\n%s\n", strings.Repeat("=", 60))
	fmt.Printf("Service: %s\n", service.DisplayName)
	fmt.Printf("%s\n\n", strings.Repeat("=", 60))

	// Basic info
	fmt.Printf("Service ID:    %s\n", service.ServiceID)
	fmt.Printf("Base URL:      %s\n", service.BaseURL)
	fmt.Printf("Route Prefix:  %s\n", service.RoutePrefix)
	fmt.Printf("Default:       %s\n", formatVisibility(service.DefaultVisibility))

	// Access Config
	if service.AccessConfig != nil {
		fmt.Printf("\nAccess Control:\n")
		if len(service.AccessConfig.AllowedIPs) > 0 {
			fmt.Printf("  Allowed IPs:        %s\n", strings.Join(service.AccessConfig.AllowedIPs, ", "))
		}
		if len(service.AccessConfig.AllowedDomains) > 0 {
			fmt.Printf("  Allowed Domains:    %s\n", strings.Join(service.AccessConfig.AllowedDomains, ", "))
		}
		if len(service.AccessConfig.AllowedSubdomains) > 0 {
			fmt.Printf("  Allowed Subdomains: %s\n", strings.Join(service.AccessConfig.AllowedSubdomains, ", "))
		}
	}

	// Visibility Rules (v2 format)
	if len(service.VisibilityRules) > 0 {
		fmt.Printf("\n%s\n", strings.Repeat("-", 60))
		fmt.Printf("Visibility Rules:\n")
		fmt.Printf("%s\n\n", strings.Repeat("-", 60))

		fmt.Printf("  %-10s  %-30s  %s\n", "VISIBILITY", "PATTERN", "METHODS")
		fmt.Printf("  %-10s  %-30s  %s\n", strings.Repeat("-", 10), strings.Repeat("-", 30), strings.Repeat("-", 15))

		for _, rule := range service.VisibilityRules {
			methods := "ALL"
			if len(rule.Methods) > 0 {
				methods = strings.Join(rule.Methods, ", ")
			}
			fmt.Printf("  %-10s  %-30s  %s\n",
				formatVisibility(rule.Visibility),
				truncate(rule.Pattern, 30),
				methods)
		}
	}

	// Endpoints (v1 format)
	if len(service.Endpoints) > 0 {
		fmt.Printf("\n%s\n", strings.Repeat("-", 60))
		fmt.Printf("Endpoints:\n")
		fmt.Printf("%s\n\n", strings.Repeat("-", 60))

		fmt.Printf("  %-10s  %-30s  %s\n", "VISIBILITY", "PATH", "METHODS")
		fmt.Printf("  %-10s  %-30s  %s\n", strings.Repeat("-", 10), strings.Repeat("-", 30), strings.Repeat("-", 15))

		for _, endpoint := range service.Endpoints {
			methods := strings.Join(endpoint.Methods, ", ")
			fmt.Printf("  %-10s  %-30s  %s\n",
				formatVisibility(endpoint.Visibility),
				truncate(endpoint.Path, 30),
				methods)
		}
	}

	// Summary
	fmt.Printf("\n%s\n", strings.Repeat("-", 60))
	fmt.Printf("Summary:\n")
	fmt.Printf("%s\n\n", strings.Repeat("-", 60))

	publicCount, privateCount := countVisibility(service)
	fmt.Printf("  Public paths:   %d\n", publicCount)
	fmt.Printf("  Private paths:  %d\n", privateCount)

	if service.DefaultVisibility == "PRIVATE" || service.DefaultVisibility == "" {
		fmt.Printf("\n  Note: Unlisted paths default to PRIVATE\n")
	} else {
		fmt.Printf("\n  Note: Unlisted paths default to PUBLIC\n")
	}

	fmt.Println()
}

func formatVisibility(v string) string {
	upper := strings.ToUpper(v)
	switch upper {
	case "PUBLIC":
		return "PUBLIC"
	case "PRIVATE":
		return "PRIVATE"
	default:
		if v == "" {
			return "PRIVATE" // default
		}
		return v
	}
}

func truncate(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen-3] + "..."
}

func countVisibility(service ServiceResponse) (public, private int) {
	for _, rule := range service.VisibilityRules {
		if strings.ToUpper(rule.Visibility) == "PUBLIC" {
			public++
		} else {
			private++
		}
	}

	for _, endpoint := range service.Endpoints {
		if strings.ToUpper(endpoint.Visibility) == "PUBLIC" {
			public++
		} else {
			private++
		}
	}

	return
}
