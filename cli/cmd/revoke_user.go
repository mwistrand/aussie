package cmd

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var revokeUserCmd = &cobra.Command{
	Use:   "user <user-id>",
	Short: "Revoke all tokens for a user (logout everywhere)",
	Long: `Revoke all tokens for a specific user, effectively forcing a logout
from all devices and sessions.

This is useful for:
  - Responding to credential compromise
  - Forcing re-authentication after permission changes
  - Emergency security response

Examples:
  aussie auth revoke user user-12345
  aussie auth revoke user user-12345 --reason "Credential compromise"
  aussie auth revoke user user-12345 --force  # Skip confirmation`,
	Args: cobra.ExactArgs(1),
	RunE: runRevokeUser,
}

var revokeUserReason string
var revokeUserFormat string
var revokeUserForce bool

func init() {
	revokeCmd.AddCommand(revokeUserCmd)
	revokeUserCmd.Flags().StringVar(&revokeUserReason, "reason", "", "Reason for revocation (for audit)")
	revokeUserCmd.Flags().StringVar(&revokeUserFormat, "format", "table", "Output format (table|json)")
	revokeUserCmd.Flags().BoolVar(&revokeUserForce, "force", false, "Skip confirmation prompt")
}

func runRevokeUser(cmd *cobra.Command, args []string) error {
	userId := args[0]

	// Validate user ID
	if userId == "" {
		return fmt.Errorf("user ID cannot be empty")
	}

	// Confirm unless --force is used
	if !revokeUserForce {
		fmt.Printf("This will revoke ALL tokens for user %s.\n", userId)
		fmt.Print("Are you sure? [y/N]: ")
		reader := bufio.NewReader(os.Stdin)
		response, _ := reader.ReadString('\n')
		response = strings.TrimSpace(strings.ToLower(response))
		if response != "y" && response != "yes" {
			fmt.Println("Aborted.")
			return nil
		}
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

	// Build request body
	reqBody := map[string]interface{}{}
	if revokeUserReason != "" {
		reqBody["reason"] = revokeUserReason
	}

	var bodyBytes []byte
	if len(reqBody) > 0 {
		bodyBytes, err = json.Marshal(reqBody)
		if err != nil {
			return fmt.Errorf("failed to marshal request: %w", err)
		}
	}

	url := fmt.Sprintf("%s/admin/tokens/users/%s", cfg.Host, userId)
	req, err := http.NewRequest("DELETE", url, bytes.NewReader(bodyBytes))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusNoContent:
		if revokeUserFormat == "json" {
			result := map[string]interface{}{
				"userId":    userId,
				"status":    "all_tokens_revoked",
				"revokedAt": time.Now().UTC().Format(time.RFC3339),
			}
			if revokeUserReason != "" {
				result["reason"] = revokeUserReason
			}
			output, _ := json.MarshalIndent(result, "", "  ")
			fmt.Println(string(output))
		} else {
			fmt.Printf("✓ All tokens revoked for user: %s\n", userId)
			if revokeUserReason != "" {
				fmt.Printf("  Reason: %s\n", revokeUserReason)
			}
		}
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to revoke user tokens")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("user-level token revocation is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}
