package cmd

import (
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var groupsDeleteCmd = &cobra.Command{
	Use:   "delete <group-id>",
	Short: "Delete a group",
	Long: `Delete an RBAC group.

This removes the group definition. Any users with this group in their token
claims will no longer receive the group's permissions.

Examples:
  aussie groups delete old-team`,
	Args: cobra.ExactArgs(1),
	RunE: runGroupsDelete,
}

func init() {
	groupsCmd.AddCommand(groupsDeleteCmd)
}

func runGroupsDelete(cmd *cobra.Command, args []string) error {
	groupID := args[0]

	// Validate group ID to prevent path traversal attacks
	if !validGroupIDPattern.MatchString(groupID) {
		return fmt.Errorf("invalid group ID format: must contain only alphanumeric characters, hyphens, underscores, and dots")
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

	url := fmt.Sprintf("%s/admin/groups/%s", cfg.Host, groupID)
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
		return fmt.Errorf("insufficient permissions to delete groups (requires admin)")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("group not found: %s", groupID)
	}
	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	fmt.Printf("Group %s has been deleted.\n", groupID)
	return nil
}
