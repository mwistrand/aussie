package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
)

var servicePermissionsGetCmd = &cobra.Command{
	Use:   "get <service-id>",
	Short: "Get the permission policy for a service",
	Long: `Retrieve the current permission policy for a service.

The response includes the permission policy (if set) and the current version
number for optimistic locking.

Examples:
  aussie service permissions get my-service
  aussie service permissions get payment-api -s http://aussie.example.com:8080`,
	Args: cobra.ExactArgs(1),
	RunE: runServicePermissionsGet,
}

func init() {
	servicePermissionsCmd.AddCommand(servicePermissionsGetCmd)
}

func runServicePermissionsGet(cmd *cobra.Command, args []string) error {
	serviceID := args[0]

	// Validate service ID
	if !validServiceIDPattern.MatchString(serviceID) {
		return fmt.Errorf("invalid service ID format: must contain only alphanumeric characters, hyphens, and underscores")
	}

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	resp, err := client.DoJSON(http.MethodGet, "/admin/services/"+serviceID+"/permissions", nil)
	if err != nil {
		return err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions to read service permissions")
	}
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("service not found: %s", serviceID)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Detail())
	}

	// Pretty print the JSON response
	var response PermissionPolicyResponse
	if err := resp.DecodeJSON(&response); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	output, err := json.MarshalIndent(response, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to format response: %w", err)
	}

	fmt.Println(string(output))
	return nil
}
