package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var statusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show current authentication status",
	Long: `Display information about the current authentication state.

This command checks for:
  1. JWT token credentials (from 'aussie login')
  2. API key (fallback, if configured in .aussierc)

The active authentication method is validated against the Aussie API gateway.

Examples:
  aussie auth status`,
	RunE: runStatus,
}

func init() {
	authCmd.AddCommand(statusCmd)
}

func runStatus(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	fmt.Printf("Server: %s\n\n", cfg.Host)

	// Check for stored JWT credentials first (higher priority)
	if creds, err := auth.LoadCredentials(); err == nil {
		return showJWTStatus(cfg, creds)
	}

	// Fall back to API key (if configured)
	if cfg.IsAuthenticated() {
		return showAPIKeyStatus(cfg)
	}

	// Not authenticated
	fmt.Println("Status: Not authenticated")
	fmt.Println()
	fmt.Println("To authenticate, run:")
	fmt.Println("  aussie login")
	fmt.Println()
	fmt.Println("Or configure an API key in .aussierc (if enabled by your platform team).")

	return nil
}

// showJWTStatus displays status for JWT token authentication.
func showJWTStatus(cfg *config.Config, creds *auth.StoredCredentials) error {
	fmt.Println("Authentication: JWT Token (IdP)")
	fmt.Printf("  User:   %s\n", creds.Subject)
	if creds.Name != "" {
		fmt.Printf("  Name:   %s\n", creds.Name)
	}
	if len(creds.Groups) > 0 {
		fmt.Printf("  Groups: %s\n", strings.Join(creds.Groups, ", "))
	}
	fmt.Printf("  Expires: %s\n", creds.ExpiresAt.Format(time.RFC3339))

	// Show warning if token is expiring soon
	remaining := creds.TimeRemaining()
	if remaining < time.Hour {
		fmt.Printf("\n  Warning: Token expires in %s\n", remaining.Round(time.Minute))
		fmt.Println("  Run 'aussie login' to refresh.")
	}

	fmt.Println()

	// Validate token against server
	return validateWithServer(cfg, creds.Token)
}

// showAPIKeyStatus displays status for API key authentication.
func showAPIKeyStatus(cfg *config.Config) error {
	fmt.Println("Authentication: API Key (fallback)")
	fmt.Println()

	// Validate key against server
	return validateWithServer(cfg, cfg.ApiKey)
}

// validateWithServer validates the token/key against the server and displays details.
func validateWithServer(cfg *config.Config, token string) error {
	url := fmt.Sprintf("%s/admin/whoami", cfg.Host)
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		fmt.Println("Server Status: Unable to connect")
		fmt.Printf("  Error: %v\n", err)
		return nil
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusUnauthorized:
		fmt.Println("Server Status: Invalid or expired credentials")
		fmt.Println("  Run 'aussie login' to re-authenticate.")
		return nil
	case http.StatusForbidden:
		fmt.Println("Server Status: Insufficient permissions")
		return nil
	case http.StatusOK:
		// Parse and display server response
		return displayServerResponse(resp)
	default:
		fmt.Printf("Server Status: Error (HTTP %d)\n", resp.StatusCode)
		return nil
	}
}

// displayServerResponse parses and displays the whoami response.
func displayServerResponse(resp *http.Response) error {
	var whoami struct {
		KeyId                string   `json:"keyId,omitempty"`
		Subject              string   `json:"sub,omitempty"`
		Name                 string   `json:"name,omitempty"`
		Permissions          []string `json:"permissions,omitempty"`
		Groups               []string `json:"groups,omitempty"`
		EffectivePermissions []string `json:"effectivePermissions,omitempty"`
		ExpiresAt            string   `json:"expiresAt,omitempty"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&whoami); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Println("Server Status: Authenticated")

	if whoami.KeyId != "" {
		fmt.Printf("  Key ID: %s\n", whoami.KeyId)
	}
	if whoami.Name != "" {
		fmt.Printf("  Name: %s\n", whoami.Name)
	}

	// Show groups if present
	if len(whoami.Groups) > 0 {
		fmt.Printf("  Groups: %s\n", strings.Join(whoami.Groups, ", "))
	}

	// Show effective permissions (expanded from groups + direct)
	if len(whoami.EffectivePermissions) > 0 {
		fmt.Printf("  Effective Permissions: %s\n", strings.Join(whoami.EffectivePermissions, ", "))
	} else if len(whoami.Permissions) > 0 {
		fmt.Printf("  Permissions: %s\n", strings.Join(whoami.Permissions, ", "))
	}

	if whoami.ExpiresAt != "" {
		fmt.Printf("  Expires: %s\n", whoami.ExpiresAt)
	}

	return nil
}
