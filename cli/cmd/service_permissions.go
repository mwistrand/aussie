package cmd

import (
	"github.com/spf13/cobra"
)

var servicePermissionsCmd = &cobra.Command{
	Use:   "permissions",
	Short: "Manage service permission policies",
	Long: `Commands for managing service-level permission policies.

Permission policies define which permissions (from API keys) are allowed
to perform specific operations on a service. Aussie treats permissions as opaque
strings and performs simple set intersection for authorization.

Operations are Aussie-defined (e.g., service.config.read, service.config.update).
Permissions are organization-defined (e.g., demo-service.admin, team:platform).

Examples:
  aussie service permissions get my-service
  aussie service permissions set my-service -f policy.json
  aussie service permissions grant my-service --operation service.config.update --permission "my-service.lead"
  aussie service permissions revoke my-service --operation service.config.read --permission "my-service.readonly"`,
}

func init() {
	serviceCmd.AddCommand(servicePermissionsCmd)
}
