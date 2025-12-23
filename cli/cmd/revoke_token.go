package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var revokeTokenCmd = &cobra.Command{
	Use:   "token <jti-or-token>",
	Short: "Revoke a specific token by its JTI or full JWT",
	Long: `Revoke a specific JWT by its JTI (JWT ID) claim or by the full token.

If you provide a full JWT token (contains dots), the JTI will be extracted automatically.
If you provide just the JTI value, it will be used directly.

The token will be immediately invalidated and rejected by the gateway.
The revocation entry automatically expires when the token would have expired.

Examples:
  aussie auth revoke token abc123-def456-ghi789
  aussie auth revoke token eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature
  aussie auth revoke token abc123 --reason "Credential compromise"
  aussie auth revoke token abc123 --format json`,
	Args: cobra.ExactArgs(1),
	RunE: runRevokeToken,
}

var revokeTokenReason string
var revokeTokenFormat string

func init() {
	revokeCmd.AddCommand(revokeTokenCmd)
	revokeTokenCmd.Flags().StringVar(&revokeTokenReason, "reason", "", "Reason for revocation (for audit)")
	revokeTokenCmd.Flags().StringVar(&revokeTokenFormat, "format", "table", "Output format (table|json)")
}

func isFullJwtToken(input string) bool {
	// A JWT token has exactly 3 parts separated by dots
	parts := strings.Split(input, ".")
	return len(parts) == 3
}

func runRevokeToken(cmd *cobra.Command, args []string) error {
	input := args[0]

	if input == "" {
		return fmt.Errorf("JTI or token cannot be empty")
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
	authToken, err := auth.GetAuthToken(cfg.ApiKey)
	if err != nil {
		return err
	}

	// Determine if input is a full JWT or just a JTI
	if isFullJwtToken(input) {
		return revokeByFullToken(cfg.Host, authToken, input)
	}
	return revokeByJti(cfg.Host, authToken, input)
}

func revokeByFullToken(host, authToken, fullToken string) error {
	// Use POST /admin/tokens/revoke endpoint
	reqBody := map[string]interface{}{
		"token": fullToken,
	}
	if revokeTokenReason != "" {
		reqBody["reason"] = revokeTokenReason
	}

	bodyBytes, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("failed to marshal request: %w", err)
	}

	url := fmt.Sprintf("%s/admin/tokens/revoke", host)
	req, err := http.NewRequest("POST", url, bytes.NewReader(bodyBytes))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+authToken)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK:
		var result map[string]interface{}
		if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
			return fmt.Errorf("failed to parse response: %w", err)
		}

		jti, _ := result["jti"].(string)

		if revokeTokenFormat == "json" {
			if revokeTokenReason != "" {
				result["reason"] = revokeTokenReason
			}
			output, _ := json.MarshalIndent(result, "", "  ")
			fmt.Println(string(output))
		} else {
			fmt.Printf("✓ Token revoked (JTI: %s)\n", jti)
			if revokeTokenReason != "" {
				fmt.Printf("  Reason: %s\n", revokeTokenReason)
			}
		}
		return nil
	case http.StatusBadRequest:
		return fmt.Errorf("invalid token format or token does not contain a JTI claim")
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to revoke tokens")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("token revocation is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}

func revokeByJti(host, authToken, jti string) error {
	// Use DELETE /admin/tokens/{jti} endpoint
	reqBody := map[string]interface{}{}
	if revokeTokenReason != "" {
		reqBody["reason"] = revokeTokenReason
	}

	var bodyBytes []byte
	var err error
	if len(reqBody) > 0 {
		bodyBytes, err = json.Marshal(reqBody)
		if err != nil {
			return fmt.Errorf("failed to marshal request: %w", err)
		}
	}

	url := fmt.Sprintf("%s/admin/tokens/%s", host, jti)
	req, err := http.NewRequest("DELETE", url, bytes.NewReader(bodyBytes))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+authToken)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusNoContent:
		if revokeTokenFormat == "json" {
			result := map[string]interface{}{
				"jti":       jti,
				"status":    "revoked",
				"revokedAt": time.Now().UTC().Format(time.RFC3339),
			}
			if revokeTokenReason != "" {
				result["reason"] = revokeTokenReason
			}
			output, _ := json.MarshalIndent(result, "", "  ")
			fmt.Println(string(output))
		} else {
			fmt.Printf("✓ Token revoked: %s\n", jti)
			if revokeTokenReason != "" {
				fmt.Printf("  Reason: %s\n", revokeTokenReason)
			}
		}
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to revoke tokens")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("token revocation is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}
