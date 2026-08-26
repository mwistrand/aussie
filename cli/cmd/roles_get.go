package cmd

import (
	"fmt"
	"net/http"
	"regexp"
	"strings"

	"github.com/spf13/cobra"
)

// validRoleIDPattern matches alphanumeric characters, hyphens, underscores, and dots
var validRoleIDPattern = regexp.MustCompile(`^[a-zA-Z0-9._-]+$`)

var rolesGetCmd = &cobra.Command{
	Use:   "get <role-id>",
	Short: "Get details of a specific role",
	Long: `Get detailed information about a specific RBAC role.

Displays all role properties including permissions.

Examples:
  aussie roles get service-admin
  aussie roles get platform-team`,
	Args: cobra.ExactArgs(1),
	RunE: runRolesGet,
}

func init() {
	rolesCmd.AddCommand(rolesGetCmd)
}

func runRolesGet(cmd *cobra.Command, args []string) error {
	roleID := args[0]

	// Validate role ID to prevent path traversal attacks
	if !validRoleIDPattern.MatchString(roleID) {
		return fmt.Errorf("invalid role ID format: must contain only alphanumeric characters, hyphens, underscores, and dots")
	}

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	resp, err := client.DoJSON(http.MethodGet, "/admin/roles/"+roleID, nil)
	if err != nil {
		return err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions to view roles (requires admin)")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("role not found: %s", roleID)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var role struct {
		ID          string   `json:"id"`
		DisplayName string   `json:"displayName"`
		Description string   `json:"description"`
		Permissions []string `json:"permissions"`
		CreatedAt   string   `json:"createdAt"`
		UpdatedAt   string   `json:"updatedAt"`
	}
	if err := resp.DecodeJSON(&role); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Printf("ID:           %s\n", role.ID)
	if role.DisplayName != "" {
		fmt.Printf("Display Name: %s\n", role.DisplayName)
	}
	if role.Description != "" {
		fmt.Printf("Description:  %s\n", role.Description)
	}
	if len(role.Permissions) > 0 {
		fmt.Printf("Permissions:  %s\n", strings.Join(role.Permissions, ", "))
	} else {
		fmt.Printf("Permissions:  (none)\n")
	}
	if role.CreatedAt != "" {
		fmt.Printf("Created:      %s\n", role.CreatedAt)
	}
	if role.UpdatedAt != "" {
		fmt.Printf("Updated:      %s\n", role.UpdatedAt)
	}

	return nil
}
