package cmd

import (
	"fmt"
	"net/http"
	"strings"

	"github.com/spf13/cobra"
)

var (
	createKeyName        string
	createKeyDescription string
	createKeyTtlDays     int
	createKeyPermissions []string
)

var keysCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a new API key",
	Long: `Create a new API key for authentication with the Aussie API gateway.

The plaintext key is only displayed once when created. Make sure to save it securely.

Permissions control what operations this key can perform:
  - "*" grants full admin access (Aussie-level and service-level)
  - Organization permissions like "my-service.admin" grant service-level access
    based on each service's permission policy

Examples:
  aussie keys create --name my-service
  aussie keys create --name ci-pipeline --ttl 7
  aussie keys create --name my-key --permissions "my-service.admin,my-service.lead"`,
	RunE: runKeysCreate,
}

func init() {
	keysCmd.AddCommand(keysCreateCmd)
	keysCreateCmd.Flags().StringVarP(&createKeyName, "name", "n", "", "Name for the API key (required)")
	keysCreateCmd.Flags().StringVarP(&createKeyDescription, "description", "d", "", "Description of the key's purpose")
	keysCreateCmd.Flags().IntVarP(&createKeyTtlDays, "ttl", "t", 0, "TTL in days (0 = no expiration)")
	keysCreateCmd.Flags().StringSliceVarP(&createKeyPermissions, "permissions", "p", []string{"*"}, "Permissions granted to this key (comma-separated)")
	keysCreateCmd.MarkFlagRequired("name")
}

func runKeysCreate(cmd *cobra.Command, args []string) error {
	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	// Build request body
	reqBody := map[string]interface{}{
		"name":        createKeyName,
		"permissions": createKeyPermissions,
	}
	if createKeyDescription != "" {
		reqBody["description"] = createKeyDescription
	}
	if createKeyTtlDays > 0 {
		reqBody["ttlDays"] = createKeyTtlDays
	}

	resp, err := client.DoJSON(http.MethodPost, "/admin/api-keys", reqBody)
	if err != nil {
		return err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions to create API keys")
	}
	if resp.StatusCode == http.StatusBadRequest {
		var errResp struct {
			Error string `json:"error"`
		}
		_ = resp.DecodeJSON(&errResp)
		return fmt.Errorf("invalid request: %s", errResp.Error)
	}
	if resp.StatusCode != http.StatusCreated {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var result struct {
		KeyId       string   `json:"keyId"`
		Key         string   `json:"key"`
		Name        string   `json:"name"`
		Permissions []string `json:"permissions"`
		CreatedBy   string   `json:"createdBy"`
		ExpiresAt   string   `json:"expiresAt,omitempty"`
	}
	if err := resp.DecodeJSON(&result); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Println("API key created successfully!")
	fmt.Println()
	fmt.Printf("Key ID:      %s\n", result.KeyId)
	fmt.Printf("Name:        %s\n", result.Name)
	fmt.Printf("Permissions: %s\n", strings.Join(result.Permissions, ", "))
	if result.ExpiresAt != "" {
		fmt.Printf("Expires:     %s\n", result.ExpiresAt)
	}
	fmt.Printf("Created By:  %s\n", result.CreatedBy)
	fmt.Println()
	fmt.Println("API Key (save this - it won't be shown again):")
	fmt.Printf("  %s\n", result.Key)

	return nil
}
