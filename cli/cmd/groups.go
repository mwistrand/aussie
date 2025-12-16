package cmd

import (
	"github.com/spf13/cobra"
)

var groupsCmd = &cobra.Command{
	Use:   "groups",
	Short: "Manage RBAC groups",
	Long: `Commands for managing Role-Based Access Control (RBAC) groups.

Groups map organizational roles to Aussie permissions. When users authenticate
via IdP, their group claims are expanded to effective permissions.

Examples:
  aussie groups list
  aussie groups create --id service-admin --display-name "Service Administrators" --permissions "apikeys.write,service.config.*"
  aussie groups get service-admin
  aussie groups update service-admin --permissions "apikeys.write,apikeys.read"
  aussie groups delete service-admin`,
}

func init() {
	rootCmd.AddCommand(groupsCmd)
}
