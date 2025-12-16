package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var (
	updateGroupDisplayName string
	updateGroupDescription string
	updateGroupPermissions []string
)

var groupsUpdateCmd = &cobra.Command{
	Use:   "update <group-id>",
	Short: "Update an existing group",
	Long: `Update an existing RBAC group's properties.

Only the specified fields are updated; others remain unchanged.

Examples:
  aussie groups update service-admin --permissions "apikeys.read,apikeys.write"
  aussie groups update platform-team --display-name "Platform Engineers"
  aussie groups update my-group --description "Updated description"`,
	Args: cobra.ExactArgs(1),
	RunE: runGroupsUpdate,
}

func init() {
	groupsCmd.AddCommand(groupsUpdateCmd)
	groupsUpdateCmd.Flags().StringVarP(&updateGroupDisplayName, "display-name", "d", "", "New display name for the group")
	groupsUpdateCmd.Flags().StringVar(&updateGroupDescription, "description", "", "New description for the group")
	groupsUpdateCmd.Flags().StringSliceVarP(&updateGroupPermissions, "permissions", "p", nil, "New permissions for the group (replaces existing)")
}

func runGroupsUpdate(cmd *cobra.Command, args []string) error {
	groupID := args[0]

	// Validate group ID to prevent path traversal attacks
	if !validGroupIDPattern.MatchString(groupID) {
		return fmt.Errorf("invalid group ID format: must contain only alphanumeric characters, hyphens, underscores, and dots")
	}

	// Check that at least one update field is provided
	displayNameSet := cmd.Flags().Changed("display-name")
	descriptionSet := cmd.Flags().Changed("description")
	permissionsSet := cmd.Flags().Changed("permissions")

	if !displayNameSet && !descriptionSet && !permissionsSet {
		return fmt.Errorf("at least one of --display-name, --description, or --permissions must be specified")
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

	// Build request body with only the fields that were set
	reqBody := make(map[string]interface{})
	if displayNameSet {
		reqBody["displayName"] = updateGroupDisplayName
	}
	if descriptionSet {
		reqBody["description"] = updateGroupDescription
	}
	if permissionsSet {
		reqBody["permissions"] = updateGroupPermissions
	}

	jsonBody, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("failed to marshal request: %w", err)
	}

	url := fmt.Sprintf("%s/admin/groups/%s", cfg.Host, groupID)
	req, err := http.NewRequest("PUT", url, bytes.NewBuffer(jsonBody))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

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
		return fmt.Errorf("insufficient permissions to update groups (requires admin)")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("group not found: %s", groupID)
	}
	if resp.StatusCode == http.StatusBadRequest {
		var errResp struct {
			Detail string `json:"detail"`
		}
		json.NewDecoder(resp.Body).Decode(&errResp)
		return fmt.Errorf("invalid request: %s", errResp.Detail)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var result struct {
		ID          string   `json:"id"`
		DisplayName string   `json:"displayName"`
		Description string   `json:"description"`
		Permissions []string `json:"permissions"`
		UpdatedAt   string   `json:"updatedAt"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Println("Group updated successfully!")
	fmt.Println()
	fmt.Printf("ID:           %s\n", result.ID)
	if result.DisplayName != "" {
		fmt.Printf("Display Name: %s\n", result.DisplayName)
	}
	if result.Description != "" {
		fmt.Printf("Description:  %s\n", result.Description)
	}
	if len(result.Permissions) > 0 {
		fmt.Printf("Permissions:  %s\n", strings.Join(result.Permissions, ", "))
	}

	return nil
}
