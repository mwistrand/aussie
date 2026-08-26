package cmd

import (
	"fmt"
	"net/http"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/api"
)

var servicePermissionsGrantCmd = &cobra.Command{
	Use:   "grant <service-id>",
	Short: "Grant a permission for an operation",
	Long: `Add a permission to the allowed permissions for a specific operation.

This is a convenience command that fetches the current policy, adds the permission
to the specified operation, and updates the policy with optimistic locking.

If the operation doesn't exist in the policy, it will be created.
If the permission already exists, no changes are made.

Operations are Aussie-defined:
  - service.config.create      - Register new services
  - service.config.read        - View service configuration
  - service.config.update      - Modify service configuration
  - service.config.delete      - Remove service registration
  - service.permissions.read   - Read permission policy
  - service.permissions.write  - Update permission policy

Examples:
  aussie service permissions grant my-service --operation service.config.update --permission "my-service.lead"
  aussie service permissions grant my-service -o service.config.read -p "team:platform"`,
	Args: cobra.ExactArgs(1),
	RunE: runServicePermissionsGrant,
}

var (
	grantOperation  string
	grantPermission string
)

func init() {
	servicePermissionsGrantCmd.Flags().StringVarP(&grantOperation, "operation", "o", "", "Operation to grant permission for (required)")
	servicePermissionsGrantCmd.Flags().StringVarP(&grantPermission, "permission", "p", "", "Permission to grant (required)")
	servicePermissionsGrantCmd.MarkFlagRequired("operation")
	servicePermissionsGrantCmd.MarkFlagRequired("permission")
	servicePermissionsCmd.AddCommand(servicePermissionsGrantCmd)
}

func runServicePermissionsGrant(cmd *cobra.Command, args []string) error {
	serviceID := args[0]

	// Validate service ID
	if !validServiceIDPattern.MatchString(serviceID) {
		return fmt.Errorf("invalid service ID format: must contain only alphanumeric characters, hyphens, and underscores")
	}

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	// Step 1: Get current policy
	currentPolicy, version, err := getPermissionPolicy(client, serviceID)
	if err != nil {
		return err
	}

	// Step 2: Add the claim to the operation
	if currentPolicy == nil {
		currentPolicy = &ServicePermissionPolicy{
			Permissions: make(map[string]OperationPermission),
		}
	}
	if currentPolicy.Permissions == nil {
		currentPolicy.Permissions = make(map[string]OperationPermission)
	}

	opPerm, exists := currentPolicy.Permissions[grantOperation]
	if !exists {
		opPerm = OperationPermission{AnyOfPermissions: []string{}}
	}

	// Check if permission already exists
	for _, p := range opPerm.AnyOfPermissions {
		if p == grantPermission {
			fmt.Printf("Permission '%s' already exists for operation '%s'\n", grantPermission, grantOperation)
			return nil
		}
	}

	opPerm.AnyOfPermissions = append(opPerm.AnyOfPermissions, grantPermission)
	currentPolicy.Permissions[grantOperation] = opPerm

	// Step 3: Update the policy
	if err := updatePermissionPolicy(client, serviceID, currentPolicy, version); err != nil {
		return err
	}

	fmt.Printf("Granted permission '%s' for operation '%s' on service '%s'\n", grantPermission, grantOperation, serviceID)
	return nil
}

func getPermissionPolicy(client *api.Client, serviceID string) (*ServicePermissionPolicy, int64, error) {
	resp, err := client.DoJSON(http.MethodGet, "/admin/services/"+serviceID+"/permissions", nil)
	if err != nil {
		return nil, 0, err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return nil, 0, fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return nil, 0, fmt.Errorf("insufficient permissions to read service permissions")
	}
	if resp.StatusCode == http.StatusNotFound {
		return nil, 0, fmt.Errorf("service not found: %s", serviceID)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, 0, fmt.Errorf("unexpected response: %s", resp.Detail())
	}

	var response PermissionPolicyResponse
	if err := resp.DecodeJSON(&response); err != nil {
		return nil, 0, fmt.Errorf("failed to parse response: %w", err)
	}

	return response.PermissionPolicy, response.Version, nil
}

func updatePermissionPolicy(client *api.Client, serviceID string, policy *ServicePermissionPolicy, version int64) error {
	resp, err := client.DoJSONWithHeaders(http.MethodPut, "/admin/services/"+serviceID+"/permissions", policy, http.Header{
		"If-Match": {fmt.Sprintf("%d", version)},
	})
	if err != nil {
		return err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions to update service permissions")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("service not found: %s", serviceID)
	}
	if resp.StatusCode == http.StatusPreconditionFailed {
		return fmt.Errorf("version conflict: the service has been modified. Retry the operation")
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Detail())
	}

	return nil
}
