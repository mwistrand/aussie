package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var authKeysVerifyCmd = &cobra.Command{
	Use:   "verify",
	Short: "Verify signing key health and JWKS consistency",
	Long: `Verify the health of signing keys and JWKS endpoint consistency.

Checks:
  - Active key exists and is valid
  - Cache is fresh and synchronized
  - JWKS endpoint is consistent with internal state
  - Key lifecycle integrity

Examples:
  aussie auth keys verify`,
	RunE: runAuthKeysVerify,
}

func init() {
	authKeysCmd.AddCommand(authKeysVerifyCmd)
}

// keyHealthStatus represents the health status of signing keys.
type keyHealthStatus struct {
	Enabled              bool   `json:"enabled"`
	Status               string `json:"status"`
	ActiveKeyId          string `json:"activeKeyId,omitempty"`
	VerificationKeyCount int    `json:"verificationKeyCount"`
	LastCacheRefresh     string `json:"lastCacheRefresh,omitempty"`
}

func runAuthKeysVerify(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	token, err := auth.GetAuthToken(cfg.ApiKey)
	if err != nil {
		return err
	}

	url := fmt.Sprintf("%s/admin/keys/health", cfg.Host)
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK:
		// Continue
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to verify signing keys (requires keys.read)")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("key rotation is not enabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var status keyHealthStatus
	if err := json.NewDecoder(resp.Body).Decode(&status); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	// Print health status
	healthy := status.Enabled && status.Status == "healthy"
	if healthy {
		fmt.Println("Key Health: HEALTHY")
	} else {
		fmt.Println("Key Health: UNHEALTHY")
	}
	fmt.Println()

	fmt.Println("Status:")
	fmt.Printf("  Enabled:           %s\n", formatBool(status.Enabled))
	fmt.Printf("  Status:            %s\n", status.Status)
	if status.ActiveKeyId != "" {
		fmt.Printf("  Active Key ID:     %s\n", status.ActiveKeyId)
	} else {
		fmt.Println("  Active Key ID:     (none)")
	}
	fmt.Printf("  Verification Keys: %d\n", status.VerificationKeyCount)
	if status.LastCacheRefresh != "" {
		fmt.Printf("  Last Refresh:      %s\n", formatTimestamp(status.LastCacheRefresh))
	}

	if !healthy {
		return fmt.Errorf("key health check failed")
	}

	return nil
}

// formatBool formats a boolean as Yes/No for display.
func formatBool(b bool) string {
	if b {
		return "Yes"
	}
	return "No"
}
