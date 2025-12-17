package cmd

import (
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
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

	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override with server flag if provided
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	// Get authentication token (JWT first, then API key fallback)
	token, err := auth.GetAuthToken(cfg.ApiKey)
	if err != nil {
		return err
	}

	url := fmt.Sprintf("%s/admin/roles/%s", cfg.Host, roleID)
	req, err := http.NewRequest("DELETE", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

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
