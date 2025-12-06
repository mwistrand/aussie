package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/config"
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

Examples:
  aussie keys create --name my-service
  aussie keys create --name ci-pipeline --ttl 7
  aussie keys create --name my-key --permissions admin:read,admin:write`,
	RunE: runKeysCreate,
}

func init() {
	keysCmd.AddCommand(keysCreateCmd)
	keysCreateCmd.Flags().StringVarP(&createKeyName, "name", "n", "", "Name for the API key (required)")
	keysCreateCmd.Flags().StringVarP(&createKeyDescription, "description", "d", "", "Description of the key's purpose")
	keysCreateCmd.Flags().IntVarP(&createKeyTtlDays, "ttl", "t", 0, "TTL in days (0 = no expiration)")
	keysCreateCmd.Flags().StringSliceVarP(&createKeyPermissions, "permissions", "p", []string{"*"}, "Permissions (comma-separated)")
	keysCreateCmd.MarkFlagRequired("name")
}

func runKeysCreate(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override with server flag if provided
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	if !cfg.IsAuthenticated() {
		return fmt.Errorf("not authenticated. Run 'aussie auth login' first")
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

	jsonBody, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("failed to marshal request: %w", err)
	}

	url := fmt.Sprintf("%s/admin/api-keys", cfg.Host)
	req, err := http.NewRequest("POST", url, bytes.NewBuffer(jsonBody))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+cfg.ApiKey)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie auth login' to refresh credentials")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions to create API keys")
	}
	if resp.StatusCode == http.StatusBadRequest {
		var errResp struct {
			Error string `json:"error"`
		}
		json.NewDecoder(resp.Body).Decode(&errResp)
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
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
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
