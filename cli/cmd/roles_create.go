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
	createRoleID          string
	createRoleDisplayName string
	createRoleDescription string
	createRolePermissions []string
)

var rolesCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a new RBAC role",
	Long: `Create a new RBAC role that maps to a set of permissions.

Roles are used to expand token role claims into effective permissions
at validation time. This allows centralized permission management without
regenerating tokens.

Examples:
  aussie roles create --id service-admin --permissions "apikeys.write,service.config.*"
  aussie roles create --id platform-team --display-name "Platform Team" --description "Core platform engineering" --permissions "admin"`,
	RunE: runRolesCreate,
}

func init() {
	rolesCmd.AddCommand(rolesCreateCmd)
	rolesCreateCmd.Flags().StringVar(&createRoleID, "id", "", "Unique identifier for the role (required)")
	rolesCreateCmd.Flags().StringVarP(&createRoleDisplayName, "display-name", "d", "", "Human-readable name for the role")
	rolesCreateCmd.Flags().StringVar(&createRoleDescription, "description", "", "Description of the role's purpose")
	rolesCreateCmd.Flags().StringSliceVarP(&createRolePermissions, "permissions", "p", nil, "Permissions granted to this role (comma-separated or multiple flags)")
	rolesCreateCmd.MarkFlagRequired("id")
}

func runRolesCreate(cmd *cobra.Command, args []string) error {
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

	// Build request body
	reqBody := map[string]interface{}{
		"id": createRoleID,
	}
	if createRoleDisplayName != "" {
		reqBody["displayName"] = createRoleDisplayName
	}
	if createRoleDescription != "" {
		reqBody["description"] = createRoleDescription
	}
	if len(createRolePermissions) > 0 {
		reqBody["permissions"] = createRolePermissions
	}

	jsonBody, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("failed to marshal request: %w", err)
	}

	url := fmt.Sprintf("%s/admin/roles", cfg.Host)
	req, err := http.NewRequest("POST", url, bytes.NewBuffer(jsonBody))
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
		return fmt.Errorf("insufficient permissions to create roles (requires admin)")
	}
	if resp.StatusCode == http.StatusBadRequest {
		var errResp struct {
			Detail string `json:"detail"`
		}
		json.NewDecoder(resp.Body).Decode(&errResp)
		return fmt.Errorf("invalid request: %s", errResp.Detail)
	}
	if resp.StatusCode != http.StatusCreated {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var result struct {
		ID          string   `json:"id"`
		DisplayName string   `json:"displayName"`
		Description string   `json:"description"`
		Permissions []string `json:"permissions"`
		CreatedAt   string   `json:"createdAt"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Println("Role created successfully!")
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
