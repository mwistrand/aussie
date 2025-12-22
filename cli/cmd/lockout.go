package cmd

import (
	"github.com/spf13/cobra"
)

var lockoutCmd = &cobra.Command{
	Use:   "lockout",
	Short: "Authentication lockout commands",
	Long: `Commands for managing authentication lockouts in the Aussie API gateway.

Authentication lockouts protect against brute force attacks by blocking
clients that exceed the maximum number of failed authentication attempts.

Lockouts can be applied to:
  - IP addresses
  - User identifiers (username, email)
  - API key prefixes

Examples:
  aussie auth lockout list                     # List current lockouts
  aussie auth lockout status --ip 192.168.1.1  # Check lockout status for IP
  aussie auth lockout clear --ip 192.168.1.1   # Clear lockout for IP
  aussie auth lockout clear --user john@ex.com # Clear lockout for user`,
}

func init() {
	authCmd.AddCommand(lockoutCmd)
}
