package cmd

import (
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var authKeysDeprecateCmd = &cobra.Command{
	Use:   "deprecate <key-id>",
	Short: "Deprecate a signing key",
	Long: `Deprecate a signing key.

Deprecated keys are no longer used for signing new tokens but remain
valid for verifying existing tokens until they expire.

This is typically an intermediate step before retiring a key.

Examples:
  aussie auth keys deprecate k-2024-q1-abc123`,
	Args: cobra.ExactArgs(1),
	RunE: runAuthKeysDeprecate,
}

func init() {
	authKeysCmd.AddCommand(authKeysDeprecateCmd)
}

func runAuthKeysDeprecate(cmd *cobra.Command, args []string) error {
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

	url := fmt.Sprintf("%s/admin/keys/%s/deprecate", cfg.Host, keyId)
	req, err := http.NewRequest("POST", url, nil)
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
	case http.StatusNoContent:
		fmt.Printf("Key %s has been deprecated.\n", keyId)
		fmt.Println("The key can still verify existing tokens but will not sign new ones.")
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to deprecate signing keys (requires keys.write)")
	case http.StatusNotFound:
		return fmt.Errorf("signing key not found: %s", keyId)
	case http.StatusConflict:
		return fmt.Errorf("key %s cannot be deprecated (may already be deprecated or retired)", keyId)
	case http.StatusServiceUnavailable:
		return fmt.Errorf("key rotation is not enabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}
