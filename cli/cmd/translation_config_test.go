package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var translationConfigTestCmd = &cobra.Command{
	Use:   "test",
	Short: "Test translation with sample claims",
	Long: `Test the translation configuration with sample token claims.

Uses the active configuration to translate sample claims, showing what roles
and permissions would be granted.

Examples:
  aussie translation-config test --claims '{"groups": ["admin", "users"]}'
  aussie translation-config test --claims-file sample.json
  aussie translation-config test --issuer "https://auth.example.com" --subject "user-123" --claims '{"scope": "openid profile"}'
  aussie translation-config test --config-file config.json --claims '{"groups": ["admin"]}'`,
	RunE: runTranslationConfigTest,
}

func init() {
	translationConfigTestCmd.Flags().StringP("issuer", "i", "test-issuer", "Token issuer")
	translationConfigTestCmd.Flags().StringP("subject", "s", "test-subject", "Token subject")
	translationConfigTestCmd.Flags().String("claims", "", "Claims as JSON string")
	translationConfigTestCmd.Flags().String("claims-file", "", "Path to JSON file containing claims")
	translationConfigTestCmd.Flags().String("config-file", "", "Path to config file to test (uses active config if not specified)")
	translationConfigCmd.AddCommand(translationConfigTestCmd)
}

func runTranslationConfigTest(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	token, err := auth.GetAuthToken(cfg.ApiKey)
	if err != nil {
		return err
	}

	// Parse claims
	var claims map[string]interface{}
	claimsStr, _ := cmd.Flags().GetString("claims")
	claimsFile, _ := cmd.Flags().GetString("claims-file")

	if claimsStr != "" {
		if err := json.Unmarshal([]byte(claimsStr), &claims); err != nil {
			return fmt.Errorf("invalid claims JSON: %w", err)
		}
	} else if claimsFile != "" {
		fileContent, err := os.ReadFile(claimsFile)
		if err != nil {
			return fmt.Errorf("failed to read claims file: %w", err)
		}
		if err := json.Unmarshal(fileContent, &claims); err != nil {
			return fmt.Errorf("invalid JSON in claims file: %w", err)
		}
	} else {
		return fmt.Errorf("either --claims or --claims-file must be provided")
	}

	// Parse optional config file
	var configSchema map[string]interface{}
	configFile, _ := cmd.Flags().GetString("config-file")
	if configFile != "" {
		fileContent, err := os.ReadFile(configFile)
		if err != nil {
			return fmt.Errorf("failed to read config file: %w", err)
		}
		if err := json.Unmarshal(fileContent, &configSchema); err != nil {
			return fmt.Errorf("invalid JSON in config file: %w", err)
		}
	}

	// Build request
	issuer, _ := cmd.Flags().GetString("issuer")
	subject, _ := cmd.Flags().GetString("subject")

	requestBody := map[string]interface{}{
		"issuer":  issuer,
		"subject": subject,
		"claims":  claims,
	}
	if configSchema != nil {
		requestBody["config"] = configSchema
	}

	body, err := json.Marshal(requestBody)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}

	url := fmt.Sprintf("%s/admin/translation-config/test", cfg.Host)
	req, err := http.NewRequest("POST", url, bytes.NewReader(body))
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
		return fmt.Errorf("insufficient permissions (requires translation.config.read or admin)")
	}
	if resp.StatusCode == http.StatusBadRequest {
		var errorResp map[string]interface{}
		json.NewDecoder(resp.Body).Decode(&errorResp)
		return fmt.Errorf("validation failed: %v", errorResp)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var result struct {
		Roles       []string `json:"roles"`
		Permissions []string `json:"permissions"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Println("Translation Result:")
	fmt.Println()

	fmt.Println("Roles:")
	if len(result.Roles) == 0 {
		fmt.Println("  (none)")
	} else {
		for _, r := range result.Roles {
			fmt.Printf("  - %s\n", r)
		}
	}

	fmt.Println()
	fmt.Println("Permissions:")
	if len(result.Permissions) == 0 {
		fmt.Println("  (none)")
	} else {
		for _, p := range result.Permissions {
			fmt.Printf("  - %s\n", p)
		}
	}

	return nil
}
