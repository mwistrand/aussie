package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var inspectTokenCmd = &cobra.Command{
	Use:   "inspect <token>",
	Short: "Inspect a JWT token to view its claims",
	Long: `Decode and display the claims of a JWT token without validation.

This is useful for:
  - Finding the JTI (JWT ID) for token revocation
  - Debugging token issues
  - Viewing token expiration and other claims

The token signature is NOT validated - this only decodes the payload.

Examples:
  aussie auth inspect eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
  aussie auth inspect $(cat ~/.aussie/credentials | jq -r .token)
  aussie auth inspect <token> --format json`,
	Args: cobra.ExactArgs(1),
	RunE: runInspectToken,
}

var inspectTokenFormat string

func init() {
	authCmd.AddCommand(inspectTokenCmd)
	inspectTokenCmd.Flags().StringVar(&inspectTokenFormat, "format", "table", "Output format (table|json)")
}

func runInspectToken(cmd *cobra.Command, args []string) error {
	token := args[0]

	if token == "" {
		return fmt.Errorf("token cannot be empty")
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

	// Build request body
	reqBody := map[string]interface{}{
		"token": token,
	}

	bodyBytes, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("failed to marshal request: %w", err)
	}

	url := fmt.Sprintf("%s/admin/tokens/inspect", cfg.Host)
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

		if inspectTokenFormat == "json" {
			output, _ := json.MarshalIndent(result, "", "  ")
			fmt.Println(string(output))
		} else {
			printTokenClaims(result)
		}
		return nil
	case http.StatusBadRequest:
		return fmt.Errorf("invalid token format")
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to inspect tokens")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}

func printTokenClaims(claims map[string]interface{}) {
	fmt.Println("Token Claims:")
	fmt.Println("─────────────────────────────────────────")

	// Print key claims in order
	keyFields := []struct {
		key   string
		label string
	}{
		{"jti", "JTI (JWT ID)"},
		{"subject", "Subject"},
		{"issuer", "Issuer"},
		{"audience", "Audience"},
		{"issuedAt", "Issued At"},
		{"expiresAt", "Expires At"},
		{"notBefore", "Not Before"},
	}

	for _, field := range keyFields {
		if val, ok := claims[field.key]; ok && val != nil {
			fmt.Printf("  %-14s %v\n", field.label+":", val)
		}
	}

	// Print other claims if present
	if otherClaims, ok := claims["otherClaims"].(map[string]interface{}); ok && len(otherClaims) > 0 {
		fmt.Println("\nOther Claims:")
		for key, val := range otherClaims {
			fmt.Printf("  %-14s %v\n", key+":", val)
		}
	}

	fmt.Println("─────────────────────────────────────────")

	// Highlight the JTI for easy copying
	if jti, ok := claims["jti"].(string); ok && jti != "" {
		fmt.Printf("\n💡 To revoke this token: aussie auth revoke token %s\n", jti)
	}
}
