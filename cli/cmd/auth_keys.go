package cmd

import (
	"github.com/spf13/cobra"
)

var authKeysCmd = &cobra.Command{
	Use:   "keys",
	Short: "Manage signing keys for token issuance",
	Long: `Commands for managing JWT signing keys used by Aussie for token issuance.

Signing keys follow a lifecycle: PENDING -> ACTIVE -> DEPRECATED -> RETIRED

  PENDING    - Key is created but not yet active (grace period)
  ACTIVE     - Key is used for signing new tokens and verifying existing ones
  DEPRECATED - Key is no longer used for signing but still valid for verification
  RETIRED    - Key is fully retired and should not be used

Key Rotation:
  Keys are automatically rotated on a configurable schedule (default: quarterly).
  Manual rotation can be triggered for emergency situations like key compromise.

Examples:
  aussie auth keys list                    # List all signing keys
  aussie auth keys show k-2024-q1-abc123   # Show details for a specific key
  aussie auth keys rotate --reason "..."   # Trigger immediate key rotation
  aussie auth keys verify                  # Check key health and JWKS consistency
  aussie auth keys export                  # Export public keys as JWKS`,
}

func init() {
	authCmd.AddCommand(authKeysCmd)
}
