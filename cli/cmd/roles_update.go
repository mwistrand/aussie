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
	updateRoleDisplayName string
	updateRoleDescription string
	updateRolePermissions []string
	addRolePermissions    []string
	removeRolePermissions []string
)

var rolesUpdateCmd = &cobra.Command{
	Use:   "update <role-id>",
	Short: "Update an existing role",
	Long: `Update an existing RBAC role's properties.

Only the specified fields are updated; others remain unchanged.

Permission update modes:
  --permissions       Replace all permissions with the specified list
  --add-permissions   Add permissions to the existing set
  --remove-permissions Remove permissions from the existing set

Note: --permissions cannot be combined with --add-permissions or --remove-permissions.

Examples:
  aussie roles update service-admin --permissions "apikeys.read,apikeys.write"
  aussie roles update platform-team --display-name "Platform Engineers"
  aussie roles update my-role --description "Updated description"
  aussie roles update dev-team --add-permissions "logs.read,metrics.read"
  aussie roles update dev-team --remove-permissions "admin"`,
	Args: cobra.ExactArgs(1),
	RunE: runRolesUpdate,
}

func init() {
	rolesCmd.AddCommand(rolesUpdateCmd)
	rolesUpdateCmd.Flags().StringVarP(&updateRoleDisplayName, "display-name", "d", "", "New display name for the role")
	rolesUpdateCmd.Flags().StringVar(&updateRoleDescription, "description", "", "New description for the role")
	rolesUpdateCmd.Flags().StringSliceVarP(&updateRolePermissions, "permissions", "p", nil, "New permissions for the role (replaces existing)")
	rolesUpdateCmd.Flags().StringSliceVar(&addRolePermissions, "add-permissions", nil, "Permissions to add to the role")
	rolesUpdateCmd.Flags().StringSliceVar(&removeRolePermissions, "remove-permissions", nil, "Permissions to remove from the role")
}

func runRolesUpdate(cmd *cobra.Command, args []string) error {
	roleID := args[0]

	// Validate role ID to prevent path traversal attacks
	if !validRoleIDPattern.MatchString(roleID) {
		return fmt.Errorf("invalid role ID format: must contain only alphanumeric characters, hyphens, underscores, and dots")
	}

	// Check that at least one update field is provided
	displayNameSet := cmd.Flags().Changed("display-name")
	descriptionSet := cmd.Flags().Changed("description")
	permissionsSet := cmd.Flags().Changed("permissions")
	addPermissionsSet := cmd.Flags().Changed("add-permissions")
	removePermissionsSet := cmd.Flags().Changed("remove-permissions")

	if !displayNameSet && !descriptionSet && !permissionsSet && !addPermissionsSet && !removePermissionsSet {
		return fmt.Errorf("at least one of --display-name, --description, --permissions, --add-permissions, or --remove-permissions must be specified")
	}

	// Validate that --permissions is mutually exclusive with --add-permissions and --remove-permissions
	if permissionsSet && (addPermissionsSet || removePermissionsSet) {
		return fmt.Errorf("--permissions cannot be combined with --add-permissions or --remove-permissions")
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

	// Build request body with only the fields that were set
	reqBody := make(map[string]interface{})
	if displayNameSet {
		reqBody["displayName"] = updateRoleDisplayName
	}
	if descriptionSet {
		reqBody["description"] = updateRoleDescription
	}
	if permissionsSet {
		reqBody["permissions"] = updateRolePermissions
	}
	if addPermissionsSet {
		reqBody["addPermissions"] = addRolePermissions
	}
	if removePermissionsSet {
		reqBody["removePermissions"] = removeRolePermissions
	}

	jsonBody, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("failed to marshal request: %w", err)
	}

	url := fmt.Sprintf("%s/admin/roles/%s", cfg.Host, roleID)
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
		return fmt.Errorf("insufficient permissions to update roles (requires admin)")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("role not found: %s", roleID)
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

	fmt.Println("Role updated successfully!")
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
