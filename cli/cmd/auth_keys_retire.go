package cmd

import (
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
)

var authKeysRetireForce bool

var authKeysRetireCmd = &cobra.Command{
	Use:   "retire <key-id>",
	Short: "Retire a signing key",
	Long: `Retire a signing key, removing it from active use.

Retired keys can no longer sign or verify tokens. This action should only
be taken after all tokens signed by this key have expired.

Use --force to retire a key that is still ACTIVE (not recommended).

Examples:
  aussie auth keys retire k-2024-q1-abc123
  aussie auth keys retire k-2024-q1-abc123 --force`,
	Args: cobra.ExactArgs(1),
	RunE: runAuthKeysRetire,
}

func init() {
	authKeysCmd.AddCommand(authKeysRetireCmd)
	authKeysRetireCmd.Flags().BoolVar(&authKeysRetireForce, "force", false, "Force retire an active key (dangerous)")
}

func runAuthKeysRetire(cmd *cobra.Command, args []string) error {
	keyId := args[0]

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	path := "/admin/keys/" + keyId
	if authKeysRetireForce {
		path += "?force=true"
	}

	resp, err := client.DoJSON(http.MethodDelete, path, nil)
	if err != nil {
		return err
	}

	switch resp.StatusCode {
	case http.StatusNoContent:
		fmt.Printf("Key %s has been retired.\n", keyId)
		fmt.Println("The key can no longer sign or verify tokens.")
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to retire signing keys (requires keys.write)")
	case http.StatusNotFound:
		return fmt.Errorf("signing key not found: %s", keyId)
	case http.StatusConflict:
		return fmt.Errorf("key %s is still active. Use --force to retire an active key (not recommended)", keyId)
	case http.StatusServiceUnavailable:
		return fmt.Errorf("key rotation is not enabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}
