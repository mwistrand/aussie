package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
)

var lockoutStatusCmd = &cobra.Command{
	Use:   "status",
	Short: "Check lockout status for an IP, user, or API key",
	Long: `Check the lockout status for a specific IP address, user identifier,
or API key prefix.

You must specify exactly one of --ip, --user, or --apikey.

Examples:
  aussie auth lockout status --ip 192.168.1.100
  aussie auth lockout status --user john@example.com
  aussie auth lockout status --apikey sk_live_
  aussie auth lockout status --ip 192.168.1.100 --format json`,
	RunE: runLockoutStatus,
}

var lockoutStatusFormat string
var lockoutStatusIP string
var lockoutStatusUser string
var lockoutStatusApiKey string

func init() {
	lockoutCmd.AddCommand(lockoutStatusCmd)
	lockoutStatusCmd.Flags().StringVar(&lockoutStatusFormat, "format", "table", "Output format (table|json)")
	lockoutStatusCmd.Flags().StringVar(&lockoutStatusIP, "ip", "", "IP address to check")
	lockoutStatusCmd.Flags().StringVar(&lockoutStatusUser, "user", "", "User identifier to check")
	lockoutStatusCmd.Flags().StringVar(&lockoutStatusApiKey, "apikey", "", "API key prefix to check")
}

func runLockoutStatus(cmd *cobra.Command, args []string) error {
	// Validate that exactly one identifier is provided
	identifierCount := 0
	if lockoutStatusIP != "" {
		identifierCount++
	}
	if lockoutStatusUser != "" {
		identifierCount++
	}
	if lockoutStatusApiKey != "" {
		identifierCount++
	}

	if identifierCount == 0 {
		return fmt.Errorf("must specify one of --ip, --user, or --apikey")
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

	if lockoutStatusIP != "" {
		path = "/admin/lockouts/ip/" + lockoutStatusIP
		identifierType = "IP"
		identifierValue = lockoutStatusIP
	} else if lockoutStatusUser != "" {
		path = "/admin/lockouts/user/" + lockoutStatusUser
		identifierType = "User"
		identifierValue = lockoutStatusUser
	} else {
		path = "/admin/lockouts/apikey/" + lockoutStatusApiKey
		identifierType = "API Key"
		identifierValue = lockoutStatusApiKey
	}

	resp, err := client.DoJSON(http.MethodGet, path, nil)
	if err != nil {
		return err
	}

	switch resp.StatusCode {
	case http.StatusOK:
		var result LockoutStatus
		if err := resp.DecodeJSON(&result); err != nil {
			return fmt.Errorf("failed to parse response: %w", err)
		}
		if lockoutStatusFormat == "json" {
			output, _ := json.MarshalIndent(result, "", "  ")
			fmt.Println(string(output))
		} else {
			printLockoutStatus(result, identifierType, identifierValue)
		}
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to check lockout status")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("authentication rate limiting is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}

func printLockoutStatus(result LockoutStatus, identifierType, identifierValue string) {
	fmt.Printf("%s:             %s\n", identifierType, identifierValue)

	if result.LockedOut {
		fmt.Printf("Status:          LOCKED OUT\n")
		fmt.Printf("Failed Attempts: %d\n", result.FailedAttempts)

		if result.LockoutStarted != "" {
			fmt.Printf("Lockout Started: %s\n", result.LockoutStarted)
		}
		if result.LockoutExpires != "" {
			fmt.Printf("Lockout Expires: %s\n", result.LockoutExpires)
		}
		if result.LockoutCount > 0 {
			fmt.Printf("Lockout Count:   %d\n", result.LockoutCount)
		}
	} else {
		fmt.Printf("Status:          OK\n")
		fmt.Printf("Failed Attempts: %d / %d\n", result.FailedAttempts, result.MaxAttempts)
	}
}
