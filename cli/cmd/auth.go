package cmd

import (
	"github.com/spf13/cobra"
)

var authCmd = &cobra.Command{
	Use:   "auth",
	Short: "View authentication status",
	Long: `Commands for viewing authentication status with the Aussie API gateway.

To configure authentication, add your API key to ~/.aussierc or .aussierc:

  host = 'http://localhost:8080'
  api_key = 'your-api-key'

Examples:
  aussie auth status`,
}

func init() {
	rootCmd.AddCommand(authCmd)
}
