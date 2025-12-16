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
	createGroupID          string
	createGroupDisplayName string
	createGroupDescription string
	createGroupPermissions []string
)

var groupsCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a new RBAC group",
	Long: `Create a new RBAC group that maps to a set of permissions.

Groups are used to expand token group claims into effective permissions
at validation time. This allows centralized permission management without
regenerating tokens.

Examples:
  aussie groups create --id service-admin --permissions "apikeys.write,service.config.*"
  aussie groups create --id platform-team --display-name "Platform Team" --description "Core platform engineering" --permissions "admin"`,
	RunE: runGroupsCreate,
}

func init() {
	groupsCmd.AddCommand(groupsCreateCmd)
	groupsCreateCmd.Flags().StringVar(&createGroupID, "id", "", "Unique identifier for the group (required)")
	groupsCreateCmd.Flags().StringVarP(&createGroupDisplayName, "display-name", "d", "", "Human-readable name for the group")
	groupsCreateCmd.Flags().StringVar(&createGroupDescription, "description", "", "Description of the group's purpose")
	groupsCreateCmd.Flags().StringSliceVarP(&createGroupPermissions, "permissions", "p", nil, "Permissions granted to this group (comma-separated)")
	groupsCreateCmd.MarkFlagRequired("id")
}

func runGroupsCreate(cmd *cobra.Command, args []string) error {
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

	// Build request body
	reqBody := map[string]interface{}{
		"id": createGroupID,
	}
	if createGroupDisplayName != "" {
		reqBody["displayName"] = createGroupDisplayName
	}
	if createGroupDescription != "" {
		reqBody["description"] = createGroupDescription
	}
	if len(createGroupPermissions) > 0 {
		reqBody["permissions"] = createGroupPermissions
	}

	jsonBody, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("failed to marshal request: %w", err)
	}

	url := fmt.Sprintf("%s/admin/groups", cfg.Host)
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
		return fmt.Errorf("insufficient permissions to create groups (requires admin)")
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

	fmt.Println("Group created successfully!")
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
