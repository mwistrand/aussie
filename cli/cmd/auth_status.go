package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/config"
)

var statusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show current authentication status",
	Long: `Display information about the currently configured API key.

This command checks if credentials are configured and validates them
against the Aussie API gateway.

Examples:
  aussie auth status`,
	RunE: runStatus,
}

func init() {
	authCmd.AddCommand(statusCmd)
}

func runStatus(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	fmt.Printf("Server: %s\n", cfg.Host)

	if !cfg.IsAuthenticated() {
		fmt.Println("Status: Not authenticated")
		fmt.Println("\nRun 'aussie auth login' to configure your API key.")
		return nil
	}

	// Validate the credentials by calling /admin/whoami
	url := fmt.Sprintf("%s/admin/whoami", cfg.Host)
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+cfg.ApiKey)

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		fmt.Println("Status: Unable to connect")
		fmt.Printf("Error: %v\n", err)
		return nil
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		fmt.Println("Status: Invalid or expired API key")
		fmt.Println("\nRun 'aussie auth login' to configure a new API key.")
		return nil
	}
	if resp.StatusCode == http.StatusForbidden {
		fmt.Println("Status: Insufficient permissions")
		return nil
	}
	if resp.StatusCode != http.StatusOK {
		fmt.Printf("Status: Error (HTTP %d)\n", resp.StatusCode)
		return nil
	}

	// Parse the response to show key info
	var whoami struct {
		KeyId       string   `json:"keyId"`
		Name        string   `json:"name"`
		Permissions []string `json:"permissions"`
		ExpiresAt   string   `json:"expiresAt,omitempty"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&whoami); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Println("Status: Authenticated")
	fmt.Printf("Key ID: %s\n", whoami.KeyId)
	fmt.Printf("Name:   %s\n", whoami.Name)
	fmt.Printf("Permissions: %s\n", strings.Join(whoami.Permissions, ", "))
	if whoami.ExpiresAt != "" {
		fmt.Printf("Expires: %s\n", whoami.ExpiresAt)
	}

	return nil
}
