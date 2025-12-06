package cmd

import (
	"github.com/spf13/cobra"
)

var keysCmd = &cobra.Command{
	Use:   "keys",
	Short: "Manage API keys",
	Long: `Commands for managing API keys for the Aussie API gateway.

Use these commands to list, create, and revoke API keys.

Examples:
  aussie keys list
  aussie keys create --name my-key --ttl 7
  aussie keys revoke abc123`,
}

func init() {
	rootCmd.AddCommand(keysCmd)
}
