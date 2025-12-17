package cmd

import (
	"github.com/spf13/cobra"
)

var rolesCmd = &cobra.Command{
	Use:   "roles",
	Short: "Manage RBAC roles",
	Long: `Commands for managing Role-Based Access Control (RBAC) roles.

Roles map organizational roles to Aussie permissions. When users authenticate
via IdP, their role claims are expanded to effective permissions.

Examples:
  aussie roles list
  aussie roles create --id service-admin --display-name "Service Administrators" --permissions "apikeys.write,service.config.*"
  aussie roles get service-admin
  aussie roles update service-admin --permissions "apikeys.write,apikeys.read"
  aussie roles delete service-admin`,
}

func init() {
	rootCmd.AddCommand(rolesCmd)
}
