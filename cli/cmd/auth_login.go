package cmd

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/config"
)

var (
	loginServer string
	loginKey    string
)

var loginCmd = &cobra.Command{
	Use:   "login",
	Short: "Configure API key credentials",
	Long: `Configure your API key for authenticating with the Aussie API gateway.

You can provide the API key via the --key flag or enter it interactively.
The credentials are stored in ~/.aussie.

Examples:
  aussie auth login
  aussie auth login --key your-api-key
  aussie auth login --server https://aussie.example.com --key your-api-key`,
	RunE: runLogin,
}

func init() {
	authCmd.AddCommand(loginCmd)
	loginCmd.Flags().StringVarP(&loginServer, "server", "s", "", "API server URL")
	loginCmd.Flags().StringVarP(&loginKey, "key", "k", "", "API key")
}

func runLogin(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override with flag if provided
	if loginServer != "" {
		cfg.Host = loginServer
	}

	// If key not provided via flag, prompt for it
	apiKey := loginKey
	if apiKey == "" {
		reader := bufio.NewReader(os.Stdin)

		// Prompt for server if not provided
		if loginServer == "" {
			fmt.Printf("Server URL [%s]: ", cfg.Host)
			serverInput, err := reader.ReadString('\n')
			if err != nil {
				return fmt.Errorf("failed to read input: %w", err)
			}
			serverInput = strings.TrimSpace(serverInput)
			if serverInput != "" {
				cfg.Host = serverInput
			}
		}

		fmt.Print("API Key: ")
		keyInput, err := reader.ReadString('\n')
		if err != nil {
			return fmt.Errorf("failed to read input: %w", err)
		}
		apiKey = strings.TrimSpace(keyInput)
	}

	if apiKey == "" {
		return fmt.Errorf("API key is required")
	}

	// Validate the credentials by calling /admin/whoami
	url := fmt.Sprintf("%s/admin/whoami", cfg.Host)
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+apiKey)

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("invalid API key")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("API key does not have sufficient permissions")
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response from server: %s", resp.Status)
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

	// Save the configuration
	cfg.ApiKey = apiKey
	if err := cfg.Save(); err != nil {
		return fmt.Errorf("failed to save config: %w", err)
	}

	fmt.Printf("Logged in successfully!\n")
	fmt.Printf("  Key ID:      %s\n", whoami.KeyId)
	fmt.Printf("  Name:        %s\n", whoami.Name)
	fmt.Printf("  Permissions: %s\n", strings.Join(whoami.Permissions, ", "))
	if whoami.ExpiresAt != "" {
		fmt.Printf("  Expires:     %s\n", whoami.ExpiresAt)
	}
	fmt.Printf("\nCredentials saved to ~/.aussie\n")

	return nil
}
