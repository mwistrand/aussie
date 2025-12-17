package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"text/tabwriter"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var groupsListCmd = &cobra.Command{
	Use:   "list",
	Short: "List all RBAC groups",
	Long: `List all RBAC groups configured in the system.

Displays group ID, display name, permissions, and last update time.

Examples:
  aussie groups list`,
	RunE: runGroupsList,
}

func init() {
	groupsCmd.AddCommand(groupsListCmd)
}

func runGroupsList(cmd *cobra.Command, args []string) error {
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

	url := fmt.Sprintf("%s/admin/groups", cfg.Host)
	req, err := http.NewRequest("GET", url, nil)
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
		return fmt.Errorf("insufficient permissions to list groups (requires admin)")
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var groups []struct {
		ID          string   `json:"id"`
		DisplayName string   `json:"displayName"`
		Description string   `json:"description"`
		Permissions []string `json:"permissions"`
		UpdatedAt   string   `json:"updatedAt"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&groups); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	if len(groups) == 0 {
		fmt.Println("No groups found.")
		return nil
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "ID\tDISPLAY NAME\tPERMISSIONS\tUPDATED")
	fmt.Fprintln(w, "--\t------------\t-----------\t-------")

	for _, group := range groups {
		displayName := group.DisplayName
		if displayName == "" {
			displayName = "-"
		}

		perms := "-"
		if len(group.Permissions) > 0 {
			if len(group.Permissions) == 1 && group.Permissions[0] == "*" {
				perms = "*"
			} else if len(group.Permissions) <= 2 {
				perms = fmt.Sprintf("%v", group.Permissions)
			} else {
				perms = fmt.Sprintf("%d permissions", len(group.Permissions))
			}
		}

		updated := "-"
		if group.UpdatedAt != "" && len(group.UpdatedAt) >= 10 {
			updated = group.UpdatedAt[:10]
		}

		fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", group.ID, displayName, perms, updated)
	}
	w.Flush()

	return nil
}
