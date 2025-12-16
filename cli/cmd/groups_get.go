package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

// validGroupIDPattern matches alphanumeric characters, hyphens, underscores, and dots
var validGroupIDPattern = regexp.MustCompile(`^[a-zA-Z0-9._-]+$`)

var groupsGetCmd = &cobra.Command{
	Use:   "get <group-id>",
	Short: "Get details of a specific group",
	Long: `Get detailed information about a specific RBAC group.

Displays all group properties including permissions.

Examples:
  aussie groups get service-admin
  aussie groups get platform-team`,
	Args: cobra.ExactArgs(1),
	RunE: runGroupsGet,
}

func init() {
	groupsCmd.AddCommand(groupsGetCmd)
}

func runGroupsGet(cmd *cobra.Command, args []string) error {
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

	// Check for JWT credentials first, then fall back to API key
	var token string
	if t, err := auth.GetToken(); err == nil {
		token = t
	} else if cfg.IsAuthenticated() {
		token = cfg.ApiKey
	} else {
		return fmt.Errorf("not authenticated. Run 'aussie login' to authenticate")
	}

	url := fmt.Sprintf("%s/admin/groups/%s", cfg.Host, groupID)
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
		return fmt.Errorf("insufficient permissions to view groups (requires admin)")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("group not found: %s", groupID)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var group struct {
		ID          string   `json:"id"`
		DisplayName string   `json:"displayName"`
		Description string   `json:"description"`
		Permissions []string `json:"permissions"`
		CreatedAt   string   `json:"createdAt"`
		UpdatedAt   string   `json:"updatedAt"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&group); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Printf("ID:           %s\n", group.ID)
	if group.DisplayName != "" {
		fmt.Printf("Display Name: %s\n", group.DisplayName)
	}
	if group.Description != "" {
		fmt.Printf("Description:  %s\n", group.Description)
	}
	if len(group.Permissions) > 0 {
		fmt.Printf("Permissions:  %s\n", strings.Join(group.Permissions, ", "))
	} else {
		fmt.Printf("Permissions:  (none)\n")
	}
	if group.CreatedAt != "" {
		fmt.Printf("Created:      %s\n", group.CreatedAt)
	}
	if group.UpdatedAt != "" {
		fmt.Printf("Updated:      %s\n", group.UpdatedAt)
	}

	return nil
}
