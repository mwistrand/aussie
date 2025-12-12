package cmd

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/config"
)

var servicePermissionsRevokeCmd = &cobra.Command{
	Use:   "revoke <service-id>",
	Short: "Revoke a permission for an operation",
	Long: `Remove a permission from the allowed permissions for a specific operation.

This is a convenience command that fetches the current policy, removes the permission
from the specified operation, and updates the policy with optimistic locking.

If the permission doesn't exist for the operation, no changes are made.

Examples:
  aussie service permissions revoke my-service --operation service.config.read --permission "my-service.readonly"
  aussie service permissions revoke my-service -o service.config.update -p "team:former-team"`,
	Args: cobra.ExactArgs(1),
	RunE: runServicePermissionsRevoke,
}

var (
	revokeOperation  string
	revokePermission string
)

func init() {
	servicePermissionsRevokeCmd.Flags().StringVarP(&revokeOperation, "operation", "o", "", "Operation to revoke permission from (required)")
	servicePermissionsRevokeCmd.Flags().StringVarP(&revokePermission, "permission", "p", "", "Permission to revoke (required)")
	servicePermissionsRevokeCmd.MarkFlagRequired("operation")
	servicePermissionsRevokeCmd.MarkFlagRequired("permission")
	servicePermissionsCmd.AddCommand(servicePermissionsRevokeCmd)
}

func runServicePermissionsRevoke(cmd *cobra.Command, args []string) error {
	serviceID := args[0]

	// Validate service ID
	if !validServiceIDPattern.MatchString(serviceID) {
		return fmt.Errorf("invalid service ID format: must contain only alphanumeric characters, hyphens, and underscores")
	}

	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override with server flag if provided
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	if !cfg.IsAuthenticated() {
		return fmt.Errorf("not authenticated. Add your API key to ~/.aussierc or .aussierc")
	}

	// Step 1: Get current policy
	currentPolicy, version, err := getPermissionPolicy(cfg, serviceID)
	if err != nil {
		return err
	}

	if currentPolicy == nil || currentPolicy.Permissions == nil {
		fmt.Printf("No permission policy defined for service '%s'\n", serviceID)
		return nil
	}

	// Step 2: Remove the permission from the operation
	opPerm, exists := currentPolicy.Permissions[revokeOperation]
	if !exists {
		fmt.Printf("Operation '%s' not found in policy\n", revokeOperation)
		return nil
	}

	// Find and remove the permission
	found := false
	newPermissions := make([]string, 0, len(opPerm.AnyOfPermissions))
	for _, p := range opPerm.AnyOfPermissions {
		if p == revokePermission {
			found = true
		} else {
			newPermissions = append(newPermissions, p)
		}
	}

	if !found {
		fmt.Printf("Permission '%s' not found for operation '%s'\n", revokePermission, revokeOperation)
		return nil
	}

	opPerm.AnyOfPermissions = newPermissions
	currentPolicy.Permissions[revokeOperation] = opPerm

	// Step 3: Update the policy
	if err := updatePermissionPolicy(cfg, serviceID, currentPolicy, version); err != nil {
		return err
	}

	fmt.Printf("Revoked permission '%s' for operation '%s' on service '%s'\n", revokePermission, revokeOperation, serviceID)
	return nil
}
