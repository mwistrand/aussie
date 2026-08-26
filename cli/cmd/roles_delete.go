package cmd

import (
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
)

var rolesDeleteCmd = &cobra.Command{
	Use:   "delete <role-id>",
	Short: "Delete a role",
	Long: `Delete an RBAC role.

This removes the role definition. Any users with this role in their token
claims will no longer receive the role's permissions.

Examples:
  aussie roles delete old-team`,
	Args: cobra.ExactArgs(1),
	RunE: runRolesDelete,
}

func init() {
	rolesCmd.AddCommand(rolesDeleteCmd)
}

func runRolesDelete(cmd *cobra.Command, args []string) error {
	roleID := args[0]

	// Validate role ID to prevent path traversal attacks
	if !validRoleIDPattern.MatchString(roleID) {
		return fmt.Errorf("invalid role ID format: must contain only alphanumeric characters, hyphens, underscores, and dots")
	}

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	resp, err := client.DoJSON(http.MethodDelete, "/admin/roles/"+roleID, nil)
	if err != nil {
		return err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions to delete roles (requires admin)")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("role not found: %s", roleID)
	}
	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	fmt.Printf("Role %s has been deleted.\n", roleID)
	return nil
}
