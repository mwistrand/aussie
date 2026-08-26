package cmd

import (
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
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

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	resp, err := client.DoJSON(http.MethodPost, "/admin/keys/"+keyId+"/deprecate", nil)
	if err != nil {
		return err
	}

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
