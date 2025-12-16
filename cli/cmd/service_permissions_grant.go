package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/config"
)

var servicePermissionsGrantCmd = &cobra.Command{
	Use:   "grant <service-id>",
	Short: "Grant a permission for an operation",
	Long: `Add a permission to the allowed permissions for a specific operation.

This is a convenience command that fetches the current policy, adds the permission
to the specified operation, and updates the policy with optimistic locking.

If the operation doesn't exist in the policy, it will be created.
If the permission already exists, no changes are made.

Operations are Aussie-defined:
  - service.config.create      - Register new services
  - service.config.read        - View service configuration
  - service.config.update      - Modify service configuration
  - service.config.delete      - Remove service registration
  - service.permissions.read   - Read permission policy
  - service.permissions.write  - Update permission policy

Examples:
  aussie service permissions grant my-service --operation service.config.update --permission "my-service.lead"
  aussie service permissions grant my-service -o service.config.read -p "team:platform"`,
	Args: cobra.ExactArgs(1),
	RunE: runServicePermissionsGrant,
}

var (
	grantOperation  string
	grantPermission string
)

func init() {
	servicePermissionsGrantCmd.Flags().StringVarP(&grantOperation, "operation", "o", "", "Operation to grant permission for (required)")
	servicePermissionsGrantCmd.Flags().StringVarP(&grantPermission, "permission", "p", "", "Permission to grant (required)")
	servicePermissionsGrantCmd.MarkFlagRequired("operation")
	servicePermissionsGrantCmd.MarkFlagRequired("permission")
	servicePermissionsCmd.AddCommand(servicePermissionsGrantCmd)
}

func runServicePermissionsGrant(cmd *cobra.Command, args []string) error {
	serviceID := args[0]

	// Validate service ID
	if !validServiceIDPattern.MatchString(serviceID) {
		return fmt.Errorf("invalid service ID format: must contain only alphanumeric characters, hyphens, and underscores")
	}

	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override with server flag if provided
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	if !cfg.IsAuthenticated() {
		return fmt.Errorf("not authenticated. Run 'aussie login' to authenticate")
	}

	// Step 1: Get current policy
	currentPolicy, version, err := getPermissionPolicy(cfg, serviceID)
	if err != nil {
		return err
	}

	// Step 2: Add the claim to the operation
	if currentPolicy == nil {
		currentPolicy = &ServicePermissionPolicy{
			Permissions: make(map[string]OperationPermission),
		}
	}
	if currentPolicy.Permissions == nil {
		currentPolicy.Permissions = make(map[string]OperationPermission)
	}

	opPerm, exists := currentPolicy.Permissions[grantOperation]
	if !exists {
		opPerm = OperationPermission{AnyOfPermissions: []string{}}
	}

	// Check if permission already exists
	for _, p := range opPerm.AnyOfPermissions {
		if p == grantPermission {
			fmt.Printf("Permission '%s' already exists for operation '%s'\n", grantPermission, grantOperation)
			return nil
		}
	}

	opPerm.AnyOfPermissions = append(opPerm.AnyOfPermissions, grantPermission)
	currentPolicy.Permissions[grantOperation] = opPerm

	// Step 3: Update the policy
	if err := updatePermissionPolicy(cfg, serviceID, currentPolicy, version); err != nil {
		return err
	}

	fmt.Printf("Granted permission '%s' for operation '%s' on service '%s'\n", grantPermission, grantOperation, serviceID)
	return nil
}

func getPermissionPolicy(cfg *config.Config, serviceID string) (*ServicePermissionPolicy, int64, error) {
	url := fmt.Sprintf("%s/admin/services/%s/permissions", cfg.Host, serviceID)
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+cfg.ApiKey)
	req.Header.Set("Accept", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to read response: %w", err)
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return nil, 0, fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return nil, 0, fmt.Errorf("insufficient permissions to read service permissions")
	}
	if resp.StatusCode == http.StatusNotFound {
		return nil, 0, fmt.Errorf("service not found: %s", serviceID)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, 0, fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var response PermissionPolicyResponse
	if err := json.Unmarshal(body, &response); err != nil {
		return nil, 0, fmt.Errorf("failed to parse response: %w", err)
	}

	return response.PermissionPolicy, response.Version, nil
}

func updatePermissionPolicy(cfg *config.Config, serviceID string, policy *ServicePermissionPolicy, version int64) error {
	policyData, err := json.Marshal(policy)
	if err != nil {
		return fmt.Errorf("failed to serialize policy: %w", err)
	}

	url := fmt.Sprintf("%s/admin/services/%s/permissions", cfg.Host, serviceID)
	req, err := http.NewRequest("PUT", url, bytes.NewReader(policyData))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+cfg.ApiKey)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("If-Match", fmt.Sprintf("%d", version))

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
		return fmt.Errorf("insufficient permissions to update service permissions")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("service not found: %s", serviceID)
	}
	if resp.StatusCode == http.StatusConflict {
		return fmt.Errorf("version conflict: the service has been modified. Retry the operation")
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("unexpected response: %s - %s", resp.Status, string(body))
	}

	return nil
}
