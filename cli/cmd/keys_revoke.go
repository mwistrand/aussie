package cmd

import (
	"fmt"
	"net/http"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/config"
)

var keysRevokeCmd = &cobra.Command{
	Use:   "revoke <key-id>",
	Short: "Revoke an API key",
	Long: `Revoke an API key to prevent it from being used for authentication.

The key record is retained for audit purposes but marked as revoked.

Examples:
  aussie keys revoke abc123`,
	Args: cobra.ExactArgs(1),
	RunE: runKeysRevoke,
}

func init() {
	keysCmd.AddCommand(keysRevokeCmd)
}

func runKeysRevoke(cmd *cobra.Command, args []string) error {
	keyId := args[0]

	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override with server flag if provided
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	if !cfg.IsAuthenticated() {
		return fmt.Errorf("not authenticated. Run 'aussie auth login' first")
	}

	url := fmt.Sprintf("%s/admin/api-keys/%s", cfg.Host, keyId)
	req, err := http.NewRequest("DELETE", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+cfg.ApiKey)

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie auth login' to refresh credentials")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions to revoke API keys")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("API key not found: %s", keyId)
	}
	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	fmt.Printf("API key %s has been revoked.\n", keyId)
	return nil
}
