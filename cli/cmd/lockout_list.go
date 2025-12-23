package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
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
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override with server flag if provided
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	// Get authentication token
	token, err := auth.GetAuthToken(cfg.ApiKey)
	if err != nil {
		return err
	}

	url := fmt.Sprintf("%s/admin/lockouts?limit=%d", cfg.Host, lockoutListLimit)

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
		body, err := io.ReadAll(resp.Body)
		if err != nil {
			return fmt.Errorf("failed to read response: %w", err)
		}

		if lockoutListFormat == "json" {
			// Pretty print JSON
			var result interface{}
			json.Unmarshal(body, &result)
			output, _ := json.MarshalIndent(result, "", "  ")
			fmt.Println(string(output))
		} else {
			// Parse and format as table
			var result map[string]interface{}
			if err := json.Unmarshal(body, &result); err != nil {
				return fmt.Errorf("failed to parse response: %w", err)
			}
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

func printLockouts(result map[string]interface{}) {
	lockouts, ok := result["lockouts"].([]interface{})
	if !ok {
		fmt.Println("No lockouts found.")
		return
	}

	count := int(result["count"].(float64))
	limit := int(result["limit"].(float64))

	fmt.Printf("Active Lockouts (%d shown, limit %d):\n", count, limit)
	fmt.Println("================================================================================")

	if len(lockouts) == 0 {
		fmt.Println("  (none)")
		return
	}

	for _, l := range lockouts {
		lockout := l.(map[string]interface{})
		lockoutType := lockout["type"].(string)
		value := lockout["value"].(string)
		expiresAt := lockout["expiresAt"].(string)
		failedAttempts := int(lockout["failedAttempts"].(float64))
		lockoutCount := int(lockout["lockoutCount"].(float64))

		fmt.Printf("\n  Type:            %s\n", lockoutType)
		fmt.Printf("  Value:           %s\n", value)
		fmt.Printf("  Failed Attempts: %d\n", failedAttempts)
		fmt.Printf("  Lockout Count:   %d\n", lockoutCount)
		fmt.Printf("  Expires:         %s\n", expiresAt)
		fmt.Println("  ----------------------------------------")
	}

	if count >= limit {
		fmt.Printf("\n(Results may be truncated. Use --limit to see more.)\n")
	}
}
