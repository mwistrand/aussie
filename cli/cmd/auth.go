package cmd

import (
	"github.com/spf13/cobra"
)

var authCmd = &cobra.Command{
	Use:   "auth",
	Short: "Manage authentication with the Aussie API gateway",
	Long: `Commands for authenticating with the Aussie API gateway.

Authentication Methods:
  1. IdP Authentication (recommended):
     Use 'aussie login' to authenticate via your organization's
     identity provider. Tokens are short-lived and automatically managed.

  2. API Key (fallback):
     Configure an API key in .aussierc. This is disabled by default
     and must be enabled by your platform team.

Configuration (.aussierc):
  host = "https://aussie.yourcompany.com"

  [auth]
  login_url = "https://sso.yourcompany.com/auth/aussie/login"
  mode = "browser"  # or "device_code" for headless environments

Examples:
  aussie login      # Authenticate via IdP
  aussie auth status     # Show current authentication state
  aussie auth logout     # Clear stored credentials`,
}

func init() {
	rootCmd.AddCommand(authCmd)
}
