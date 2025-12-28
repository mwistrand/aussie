package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var authKeysShowIncludePublicKey bool

var authKeysShowCmd = &cobra.Command{
	Use:   "show <key-id>",
	Short: "Show details for a specific signing key",
	Long: `Show detailed information about a specific signing key.

Displays key ID, status, lifecycle timestamps, and optionally public key metadata.

Examples:
  aussie auth keys show k-2024-q1-abc123
  aussie auth keys show k-2024-q1-abc123 --include-public-key`,
	Args: cobra.ExactArgs(1),
	RunE: runAuthKeysShow,
}

func init() {
	authKeysCmd.AddCommand(authKeysShowCmd)
	authKeysShowCmd.Flags().BoolVar(&authKeysShowIncludePublicKey, "include-public-key", false, "Include public key metadata in output")
}

// signingKeyDetail represents detailed information about a signing key.
type signingKeyDetail struct {
	KeyId        string                 `json:"keyId"`
	Status       string                 `json:"status"`
	CreatedAt    string                 `json:"createdAt"`
	ActivatedAt  string                 `json:"activatedAt,omitempty"`
	DeprecatedAt string                 `json:"deprecatedAt,omitempty"`
	RetiredAt    string                 `json:"retiredAt,omitempty"`
	CanSign      bool                   `json:"canSign"`
	CanVerify    bool                   `json:"canVerify"`
	PublicKey    map[string]interface{} `json:"publicKey,omitempty"`
}

func runAuthKeysShow(cmd *cobra.Command, args []string) error {
	keyId := args[0]

	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	token, err := auth.GetAuthToken(cfg.ApiKey)
	if err != nil {
		return err
	}

	url := fmt.Sprintf("%s/admin/keys/%s", cfg.Host, keyId)
	if authKeysShowIncludePublicKey {
		url += "?includePublicKey=true"
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
		// Continue
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to view signing key (requires keys.read)")
	case http.StatusNotFound:
		return fmt.Errorf("signing key not found: %s", keyId)
	case http.StatusServiceUnavailable:
		return fmt.Errorf("key rotation is not enabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var key signingKeyDetail
	if err := json.NewDecoder(resp.Body).Decode(&key); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	// Print formatted output
	fmt.Printf("Key ID:       %s\n", key.KeyId)
	fmt.Printf("Status:       %s\n", key.Status)
	fmt.Printf("Can Sign:     %v\n", key.CanSign)
	fmt.Printf("Can Verify:   %v\n", key.CanVerify)
	fmt.Println()
	fmt.Println("Lifecycle:")
	fmt.Printf("  Created:    %s\n", formatTimestamp(key.CreatedAt))
	fmt.Printf("  Activated:  %s\n", formatTimestamp(key.ActivatedAt))
	fmt.Printf("  Deprecated: %s\n", formatTimestamp(key.DeprecatedAt))
	fmt.Printf("  Retired:    %s\n", formatTimestamp(key.RetiredAt))

	if key.PublicKey != nil {
		fmt.Println()
		fmt.Println("Public Key:")
		if alg, ok := key.PublicKey["algorithm"]; ok {
			fmt.Printf("  Algorithm:    %v\n", alg)
		}
		if format, ok := key.PublicKey["format"]; ok {
			fmt.Printf("  Format:       %v\n", format)
		}
		if bits, ok := key.PublicKey["modulus_bits"]; ok {
			fmt.Printf("  Modulus Bits: %v\n", bits)
		}
	}

	return nil
}

// formatTimestamp formats an ISO timestamp for display.
func formatTimestamp(ts string) string {
	if ts == "" {
		return "-"
	}
	t, err := time.Parse(time.RFC3339, ts)
	if err != nil {
		return ts
	}
	return t.Format("2006-01-02 15:04:05 MST")
}
