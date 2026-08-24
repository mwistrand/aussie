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

var rolesListCmd = &cobra.Command{
	Use:   "list",
	Short: "List RBAC roles",
	Long: `List RBAC roles configured in the system.

Displays role ID, display name, permissions, and last update time.

Examples:
  aussie roles list
  aussie roles list --limit 25 --offset 50`,
	RunE: runRolesList,
}

func init() {
	rolesListCmd.Flags().IntP("limit", "l", 50, "Maximum number of roles to return")
	rolesListCmd.Flags().IntP("offset", "o", 0, "Number of roles to skip")
	rolesCmd.AddCommand(rolesListCmd)
}

func runRolesList(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override with server flag if provided
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	// Get authentication token (JWT first, then API key fallback)
	token, err := auth.GetAuthTokenForHost(cfg.ApiKey, cfg.Host)
	if err != nil {
		return err
	}

	limit, _ := cmd.Flags().GetInt("limit")
	offset, _ := cmd.Flags().GetInt("offset")
	url := fmt.Sprintf("%s/admin/roles?limit=%d&offset=%d", cfg.Host, limit, offset)
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
		return fmt.Errorf("insufficient permissions to list roles (requires admin)")
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var roles []struct {
		ID          string   `json:"id"`
		DisplayName string   `json:"displayName"`
		Description string   `json:"description"`
		Permissions []string `json:"permissions"`
		UpdatedAt   string   `json:"updatedAt"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&roles); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	if len(roles) == 0 {
		fmt.Println("No roles found.")
		return nil
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "ID\tDISPLAY NAME\tPERMISSIONS\tUPDATED")
	fmt.Fprintln(w, "--\t------------\t-----------\t-------")

	for _, role := range roles {
		displayName := role.DisplayName
		if displayName == "" {
			displayName = "-"
		}

		perms := "-"
		if len(role.Permissions) > 0 {
			if len(role.Permissions) == 1 && role.Permissions[0] == "*" {
				perms = "*"
			} else if len(role.Permissions) <= 2 {
				perms = fmt.Sprintf("%v", role.Permissions)
			} else {
				perms = fmt.Sprintf("%d permissions", len(role.Permissions))
			}
		}

		updated := "-"
		if role.UpdatedAt != "" && len(role.UpdatedAt) >= 10 {
			updated = role.UpdatedAt[:10]
		}

		fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", role.ID, displayName, perms, updated)
	}
	w.Flush()

	return nil
}
