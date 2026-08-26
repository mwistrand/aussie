package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"

	"github.com/spf13/cobra"
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
	client, err := newAuthenticatedAPIClient(cmd)
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

	resp, err := client.DoJSON(http.MethodPost, "/admin/translation-config/validate", configSchema)
	if err != nil {
		return err
	}

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
	if err := resp.DecodeJSON(&result); err != nil {
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
