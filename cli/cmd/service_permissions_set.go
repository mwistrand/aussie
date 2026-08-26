package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"

	"github.com/spf13/cobra"
)

var servicePermissionsSetCmd = &cobra.Command{
	Use:   "set <service-id>",
	Short: "Set the permission policy for a service",
	Long: `Replace the entire permission policy for a service.

This command requires the current version number for optimistic locking.
Use 'aussie service permissions get' to retrieve the current version.

The policy file should be a JSON object with the following structure:
{
  "permissions": {
    "service.config.read": {
      "anyOfClaims": ["my-service.readonly", "my-service.admin"]
    },
    "service.config.update": {
      "anyOfClaims": ["my-service.admin"]
    }
  }
}

Examples:
  aussie service permissions set my-service -f policy.json --version 1
  aussie service permissions set my-service -f policy.json -v 3`,
	Args: cobra.ExactArgs(1),
	RunE: runServicePermissionsSet,
}

var (
	permissionsPolicyFile string
	permissionsVersion    int64
)

func init() {
	servicePermissionsSetCmd.Flags().StringVarP(&permissionsPolicyFile, "file", "f", "", "Path to the policy JSON file (required)")
	servicePermissionsSetCmd.Flags().Int64VarP(&permissionsVersion, "version", "v", 0, "Current version for optimistic locking (required)")
	servicePermissionsSetCmd.MarkFlagRequired("file")
	servicePermissionsSetCmd.MarkFlagRequired("version")
	servicePermissionsCmd.AddCommand(servicePermissionsSetCmd)
}

func runServicePermissionsSet(cmd *cobra.Command, args []string) error {
	serviceID := args[0]

	// Validate service ID
	if !validServiceIDPattern.MatchString(serviceID) {
		return fmt.Errorf("invalid service ID format: must contain only alphanumeric characters, hyphens, and underscores")
	}

	// Read and validate the policy file
	policyData, err := os.ReadFile(permissionsPolicyFile)
	if err != nil {
		return fmt.Errorf("failed to read policy file: %w", err)
	}

	// Validate JSON structure
	var policy ServicePermissionPolicy
	if err := json.Unmarshal(policyData, &policy); err != nil {
		return fmt.Errorf("invalid policy JSON: %w", err)
	}

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	resp, err := client.DoJSONWithHeaders(http.MethodPut, "/admin/services/"+serviceID+"/permissions", &policy, http.Header{
		"If-Match": {fmt.Sprintf("%d", permissionsVersion)},
	})
	if err != nil {
		return err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions to update service permissions")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("service not found: %s", serviceID)
	}
	if resp.StatusCode == http.StatusPreconditionFailed {
		return fmt.Errorf("version conflict: the service has been modified. Reload and try again")
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Detail())
	}

	// Pretty print the response
	var response PermissionPolicyResponse
	if err := resp.DecodeJSON(&response); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Printf("Permission policy updated for service '%s' (version: %d)\n", serviceID, response.Version)

	output, err := json.MarshalIndent(response.PermissionPolicy, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to format response: %w", err)
	}

	fmt.Println(string(output))
	return nil
}
