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

	// Build URL based on identifier type
	var url string
	var identifierType, identifierValue string

	if lockoutStatusIP != "" {
		url = fmt.Sprintf("%s/admin/lockouts/ip/%s", cfg.Host, lockoutStatusIP)
		identifierType = "IP"
		identifierValue = lockoutStatusIP
	} else if lockoutStatusUser != "" {
		url = fmt.Sprintf("%s/admin/lockouts/user/%s", cfg.Host, lockoutStatusUser)
		identifierType = "User"
		identifierValue = lockoutStatusUser
	} else {
		url = fmt.Sprintf("%s/admin/lockouts/apikey/%s", cfg.Host, lockoutStatusApiKey)
		identifierType = "API Key"
		identifierValue = lockoutStatusApiKey
	}

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

		if lockoutStatusFormat == "json" {
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
			printLockoutStatus(result, identifierType, identifierValue)
		}
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie auth login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to check lockout status")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("authentication rate limiting is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}

func printLockoutStatus(result map[string]interface{}, identifierType, identifierValue string) {
	lockedOut := result["lockedOut"].(bool)
	failedAttempts := int(result["failedAttempts"].(float64))
	maxAttempts := int(result["maxAttempts"].(float64))

	fmt.Printf("%s:             %s\n", identifierType, identifierValue)

	if lockedOut {
		fmt.Printf("Status:          LOCKED OUT\n")
		fmt.Printf("Failed Attempts: %d\n", failedAttempts)

		if lockoutStarted, ok := result["lockoutStarted"].(string); ok {
			fmt.Printf("Lockout Started: %s\n", lockoutStarted)
		}
		if lockoutExpires, ok := result["lockoutExpires"].(string); ok {
			fmt.Printf("Lockout Expires: %s\n", lockoutExpires)
		}
		if lockoutCount, ok := result["lockoutCount"].(float64); ok {
			fmt.Printf("Lockout Count:   %d\n", int(lockoutCount))
		}
	} else {
		fmt.Printf("Status:          OK\n")
		fmt.Printf("Failed Attempts: %d / %d\n", failedAttempts, maxAttempts)
	}
}
