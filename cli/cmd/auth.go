package cmd

import (
	"github.com/spf13/cobra"
)

var authCmd = &cobra.Command{
	Use:   "auth",
	Short: "Manage authentication credentials",
	Long: `Commands for managing authentication credentials for the Aussie API gateway.

Use these commands to configure your API key and manage your authentication status.

Examples:
  aussie auth login
  aussie auth status
  aussie auth logout`,
}

func init() {
	rootCmd.AddCommand(authCmd)
}
