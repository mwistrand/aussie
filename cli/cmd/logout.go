package cmd

import (
	"github.com/spf13/cobra"
)

// topLogoutCmd is a top-level alias for "aussie auth logout"
var topLogoutCmd = &cobra.Command{
	Use:   "logout",
	Short: "Clear stored authentication credentials",
	Long: `Remove locally stored authentication tokens.

Optionally calls the IdP logout endpoint to invalidate server-side sessions
if configured and --server flag is provided.

Examples:
  aussie logout           # Clear local credentials only
  aussie logout --server  # Also invalidate server session`,
	RunE: runLogout, // Reuse the existing runLogout function from auth_logout.go
}

func init() {
	rootCmd.AddCommand(topLogoutCmd)
	topLogoutCmd.Flags().Bool("server", false, "Also logout from IdP server (if configured)")
}
