package cmd

import (
	"fmt"
	"net/http"
	"regexp"

	"github.com/spf13/cobra"
)

// validIDPattern matches alphanumeric characters, hyphens, and underscores only
var validIDPattern = regexp.MustCompile(`^[a-zA-Z0-9_-]+$`)

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

	// Validate key ID to prevent path traversal attacks
	if !validIDPattern.MatchString(keyId) {
		return fmt.Errorf("invalid key ID format: must contain only alphanumeric characters, hyphens, and underscores")
	}

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	resp, err := client.DoJSON(http.MethodDelete, "/admin/api-keys/"+keyId, nil)
	if err != nil {
		return err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
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
