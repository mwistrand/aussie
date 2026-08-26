package cmd

import (
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
)

var lockoutClearCmd = &cobra.Command{
	Use:   "clear",
	Short: "Clear a lockout for an IP, user, or API key",
	Long: `Clear a lockout for a specific IP address, user identifier,
or API key prefix.

You must specify exactly one of --ip, --user, or --apikey.
Use --all to clear all lockouts (requires --force).

Examples:
  aussie auth lockout clear --ip 192.168.1.100
  aussie auth lockout clear --ip 192.168.1.100 --reason "User verified via support ticket #12345"
  aussie auth lockout clear --user john@example.com
  aussie auth lockout clear --apikey sk_live_
  aussie auth lockout clear --all --force`,
	RunE: runLockoutClear,
}

var lockoutClearIP string
var lockoutClearUser string
var lockoutClearApiKey string
var lockoutClearReason string
var lockoutClearAll bool
var lockoutClearForce bool

func init() {
	lockoutCmd.AddCommand(lockoutClearCmd)
	lockoutClearCmd.Flags().StringVar(&lockoutClearIP, "ip", "", "IP address to unlock")
	lockoutClearCmd.Flags().StringVar(&lockoutClearUser, "user", "", "User identifier to unlock")
	lockoutClearCmd.Flags().StringVar(&lockoutClearApiKey, "apikey", "", "API key prefix to unlock")
	lockoutClearCmd.Flags().StringVar(&lockoutClearReason, "reason", "", "Reason for clearing the lockout (for audit log)")
	lockoutClearCmd.Flags().BoolVar(&lockoutClearAll, "all", false, "Clear all lockouts (emergency use)")
	lockoutClearCmd.Flags().BoolVar(&lockoutClearForce, "force", false, "Required with --all to confirm clearing all lockouts")
}

func runLockoutClear(cmd *cobra.Command, args []string) error {
	// Handle --all case
	if lockoutClearAll {
		return runLockoutClearAll(cmd)
	}

	// Validate that exactly one identifier is provided
	identifierCount := 0
	if lockoutClearIP != "" {
		identifierCount++
	}
	if lockoutClearUser != "" {
		identifierCount++
	}
	if lockoutClearApiKey != "" {
		identifierCount++
	}

	if identifierCount == 0 {
		return fmt.Errorf("must specify one of --ip, --user, --apikey, or --all")
	}
	if identifierCount > 1 {
		return fmt.Errorf("must specify only one of --ip, --user, or --apikey")
	}

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	// Build URL based on identifier type
	var path string
	var identifierType, identifierValue string

	if lockoutClearIP != "" {
		path = "/admin/lockouts/ips/" + lockoutClearIP
		identifierType = "IP"
		identifierValue = lockoutClearIP
	} else if lockoutClearUser != "" {
		path = "/admin/lockouts/users/" + lockoutClearUser
		identifierType = "user"
		identifierValue = lockoutClearUser
	} else {
		path = "/admin/lockouts/apikeys/" + lockoutClearApiKey
		identifierType = "API key"
		identifierValue = lockoutClearApiKey
	}

	// Create request body
	body := map[string]string{
		"reason": lockoutClearReason,
	}
	resp, err := client.DoJSON(http.MethodDelete, path, body)
	if err != nil {
		return err
	}

	switch resp.StatusCode {
	case http.StatusNoContent:
		fmt.Printf("✓ Cleared lockout for %s %s\n", identifierType, identifierValue)
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to clear lockouts")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("authentication rate limiting is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}

func runLockoutClearAll(cmd *cobra.Command) error {
	if !lockoutClearForce {
		return fmt.Errorf("clearing all lockouts requires --force flag")
	}

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	// Create request body
	body := map[string]interface{}{
		"force":  true,
		"reason": lockoutClearReason,
	}
	resp, err := client.DoJSON(http.MethodPost, "/admin/lockouts:reset", body)
	if err != nil {
		return err
	}

	switch resp.StatusCode {
	case http.StatusOK:
		var result map[string]interface{}
		if err := resp.DecodeJSON(&result); err != nil {
			return fmt.Errorf("failed to parse response: %w", err)
		}
		count := int(result["count"].(float64))
		fmt.Printf("✓ Cleared %d lockouts\n", count)
		return nil
	case http.StatusBadRequest:
		return fmt.Errorf("must set force=true to clear all lockouts")
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to clear all lockouts")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("authentication rate limiting is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}
