package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
)

var lockoutListCmd = &cobra.Command{
	Use:   "list",
	Short: "List current lockouts",
	Long: `List all currently active authentication lockouts.

This command shows IPs, users, and API key prefixes that are currently
locked out due to failed authentication attempts.

Examples:
  aussie auth lockout list
  aussie auth lockout list --limit 50
  aussie auth lockout list --format json`,
	RunE: runLockoutList,
}

var lockoutListFormat string
var lockoutListLimit int

func init() {
	lockoutCmd.AddCommand(lockoutListCmd)
	lockoutListCmd.Flags().StringVar(&lockoutListFormat, "format", "table", "Output format (table|json)")
	lockoutListCmd.Flags().IntVar(&lockoutListLimit, "limit", 100, "Maximum number of entries to return")
}

func runLockoutList(cmd *cobra.Command, args []string) error {
	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	resp, err := client.DoJSON(http.MethodGet, fmt.Sprintf("/admin/lockouts?limit=%d", lockoutListLimit), nil)
	if err != nil {
		return err
	}

	switch resp.StatusCode {
	case http.StatusOK:
		var result LockoutList
		if err := resp.DecodeJSON(&result); err != nil {
			return fmt.Errorf("failed to parse response: %w", err)
		}
		if lockoutListFormat == "json" {
			output, _ := json.MarshalIndent(result, "", "  ")
			fmt.Println(string(output))
		} else {
			printLockouts(result)
		}
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to list lockouts")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("authentication rate limiting is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}

func printLockouts(result LockoutList) {
	if result.Lockouts == nil {
		fmt.Println("No lockouts found.")
		return
	}

	fmt.Printf("Active Lockouts (%d shown, limit %d):\n", result.Count, result.Limit)
	fmt.Println("================================================================================")

	if len(result.Lockouts) == 0 {
		fmt.Println("  (none)")
		return
	}

	for _, lockout := range result.Lockouts {
		lockoutType := lockout.Type
		value := lockout.Value
		expiresAt := lockout.ExpiresAt
		failedAttempts := lockout.FailedAttempts
		lockoutCount := lockout.LockoutCount

		fmt.Printf("\n  Type:            %s\n", lockoutType)
		fmt.Printf("  Value:           %s\n", value)
		fmt.Printf("  Failed Attempts: %d\n", failedAttempts)
		fmt.Printf("  Lockout Count:   %d\n", lockoutCount)
		fmt.Printf("  Expires:         %s\n", expiresAt)
		fmt.Println("  ----------------------------------------")
	}

	if result.Count >= result.Limit {
		fmt.Printf("\n(Results may be truncated. Use --limit to see more.)\n")
	}
}
