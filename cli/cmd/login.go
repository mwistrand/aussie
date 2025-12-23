package cmd

import (
	"github.com/spf13/cobra"
)

// topLoginCmd is a top-level alias for "aussie login"
var topLoginCmd = &cobra.Command{
	Use:   "login",
	Short: "Authenticate with your organization's identity provider",
	Long: `Authenticate with your organization's IdP to obtain a short-lived token.

This command triggers your organization's authentication flow. Aussie does not
handle credentials directly - authentication is delegated to your IdP.

Authentication Modes:
  browser      Opens a browser for OAuth/SAML login (default)
  device_code  Uses device code flow for headless environments
  cli_callback Starts a local server to receive the callback

The default mode can be configured in .aussierc:

  [auth]
  mode = "device_code"  # For headless environments

Configuration:
  Set auth.login_url in .aussierc to point to your organization's
  authentication endpoint (translation layer).

Examples:
  aussie login                     # Uses mode from config (default: browser)
  aussie login --mode device_code  # Override config for this invocation`,
	RunE: runLogin, // Reuse the existing runLogin function from auth_login.go
}

func init() {
	rootCmd.AddCommand(topLoginCmd)
	topLoginCmd.Flags().String("mode", "", "Auth mode: browser, device_code, cli_callback (overrides config)")
}
