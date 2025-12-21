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

var revokeCheckCmd = &cobra.Command{
	Use:   "check <jti>",
	Short: "Check if a token is revoked",
	Long: `Check the revocation status of a specific token by its JTI.

This is useful for:
  - Verifying a revocation was successful
  - Debugging authentication issues
  - Incident response verification

Examples:
  aussie auth revoke check abc123-def456-ghi789
  aussie auth revoke check abc123 --format json`,
	Args: cobra.ExactArgs(1),
	RunE: runRevokeCheck,
}

var revokeCheckFormat string

func init() {
	revokeCmd.AddCommand(revokeCheckCmd)
	revokeCheckCmd.Flags().StringVar(&revokeCheckFormat, "format", "table", "Output format (table|json)")
}

func runRevokeCheck(cmd *cobra.Command, args []string) error {
	jti := args[0]

	// Validate JTI
	if jti == "" {
		return fmt.Errorf("JTI cannot be empty")
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

	url := fmt.Sprintf("%s/admin/tokens/%s/status", cfg.Host, jti)
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

		var result map[string]interface{}
		if err := json.Unmarshal(body, &result); err != nil {
			return fmt.Errorf("failed to parse response: %w", err)
		}

		if revokeCheckFormat == "json" {
			output, _ := json.MarshalIndent(result, "", "  ")
			fmt.Println(string(output))
		} else {
			revoked, _ := result["revoked"].(bool)
			checkedAt, _ := result["checkedAt"].(string)

			fmt.Printf("Token Status: %s\n", jti)
			fmt.Println("----------------------------------")
			if revoked {
				fmt.Println("  Status:    REVOKED")
			} else {
				fmt.Println("  Status:    VALID (not revoked)")
			}
			fmt.Printf("  Checked:   %s\n", checkedAt)
		}
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie auth login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to check revocation status")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("token revocation is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}
