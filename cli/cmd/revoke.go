package cmd

import (
	"github.com/spf13/cobra"
)

var revokeCmd = &cobra.Command{
	Use:   "revoke",
	Short: "Token revocation commands",
	Long: `Commands for revoking tokens in the Aussie API gateway.

Token revocation immediately invalidates tokens, preventing their use
even before they expire. This is useful for:
  - Responding to security incidents
  - Forcing user re-authentication
  - Emergency logout-everywhere operations

Examples:
  aussie auth revoke token <jti>             # Revoke a specific token
  aussie auth revoke user <user-id>          # Revoke all tokens for a user
  aussie auth revoke list                    # List currently revoked tokens
  aussie auth revoke check <jti>             # Check if a token is revoked
  aussie auth revoke rebuild-filter          # Force rebuild bloom filter`,
}

func init() {
	authCmd.AddCommand(revokeCmd)
}
