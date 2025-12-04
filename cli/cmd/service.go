package cmd

import (
	"github.com/spf13/cobra"
)

var serviceCmd = &cobra.Command{
	Use:   "service",
	Short: "Manage services registered with Aussie",
	Long: `Commands for managing services registered with the Aussie API gateway.

Use these commands to validate service configurations, preview visibility settings,
and manage service registrations.

Examples:
  aussie service validate -f my-service.json
  aussie service preview my-service`,
}

func init() {
	rootCmd.AddCommand(serviceCmd)
}
