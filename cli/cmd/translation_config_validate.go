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

var translationConfigValidateCmd = &cobra.Command{
	Use:   "validate <config-file>",
	Short: "Validate a translation configuration",
	Long: `Validate a translation configuration without uploading it.

Checks that the configuration is valid JSON and conforms to the
translation configuration schema.

Examples:
  aussie translation-config validate config.json`,
	Args: cobra.ExactArgs(1),
	RunE: runTranslationConfigValidate,
}

func init() {
	translationConfigCmd.AddCommand(translationConfigValidateCmd)
}

func runTranslationConfigValidate(cmd *cobra.Command, args []string) error {
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

	// Read config file
	configFile := args[0]
	fileContent, err := os.ReadFile(configFile)
	if err != nil {
		return fmt.Errorf("failed to read config file: %w", err)
	}

	// Parse config to validate JSON
	var configSchema map[string]interface{}
	if err := json.Unmarshal(fileContent, &configSchema); err != nil {
		fmt.Printf("❌ Invalid JSON: %v\n", err)
		return fmt.Errorf("validation failed")
	}

	body, err := json.Marshal(configSchema)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}

	url := fmt.Sprintf("%s/admin/translation-config/validate", cfg.Host)
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
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var result struct {
		Valid  bool     `json:"valid"`
		Errors []string `json:"errors"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	if result.Valid {
		fmt.Printf("✓ Configuration is valid\n")
		return nil
	}

	fmt.Printf("❌ Configuration validation failed:\n")
	for _, e := range result.Errors {
		fmt.Printf("  - %s\n", e)
	}
	return fmt.Errorf("validation failed")
}
