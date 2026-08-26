package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/api"
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

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	// Determine if input is a full JWT or just a JTI
	if isFullJwtToken(input) {
		return revokeByFullToken(client, input)
	}
	return revokeByJti(client, input)
}

func revokeByFullToken(client *api.Client, fullToken string) error {
	// Use POST /admin/tokens/revoke endpoint
	reqBody := map[string]interface{}{
		"token": fullToken,
	}
	if revokeTokenReason != "" {
		reqBody["reason"] = revokeTokenReason
	}

	resp, err := client.DoJSON(http.MethodPost, "/admin/tokens/revoke", reqBody)
	if err != nil {
		return err
	}

	switch resp.StatusCode {
	case http.StatusOK:
		var result map[string]interface{}
		if err := resp.DecodeJSON(&result); err != nil {
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

func revokeByJti(client *api.Client, jti string) error {
	// Use DELETE /admin/tokens/{jti} endpoint
	reqBody := map[string]interface{}{}
	if revokeTokenReason != "" {
		reqBody["reason"] = revokeTokenReason
	}

	var body any
	if len(reqBody) > 0 {
		body = reqBody
	}
	resp, err := client.DoJSON(http.MethodDelete, "/admin/tokens/"+jti, body)
	if err != nil {
		return err
	}

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
