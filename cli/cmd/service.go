package cmd

import (
	"github.com/spf13/cobra"
)

var serviceCmd = &cobra.Command{
	Use:   "service",
	Short: "Manage services registered with Aussie",
	Long: `Commands for managing services registered with the Aussie API gateway.

Use these commands to register, validate, preview, and delete service configurations.

Examples:
  aussie service register -f my-service.json
  aussie service validate -f my-service.json
  aussie service preview my-service
  aussie service delete my-service`,
}

func init() {
	rootCmd.AddCommand(serviceCmd)
}
