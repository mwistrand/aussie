package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"

	"github.com/spf13/cobra"
)

var translationConfigUploadCmd = &cobra.Command{
	Use:   "upload <config-file>",
	Short: "Upload a new translation configuration",
	Long: `Upload a new translation configuration from a JSON file.

The configuration file should contain a valid translation configuration schema.
By default, the new configuration will be immediately activated.

Examples:
  aussie translation-config upload config.json
  aussie translation-config upload config.json --comment "Added admin role mapping"
  aussie translation-config upload config.json --no-activate`,
	Args: cobra.ExactArgs(1),
	RunE: runTranslationConfigUpload,
}

func init() {
	translationConfigUploadCmd.Flags().StringP("comment", "c", "", "Description of changes")
	translationConfigUploadCmd.Flags().Bool("no-activate", false, "Do not activate the new version")
	translationConfigCmd.AddCommand(translationConfigUploadCmd)
}

func runTranslationConfigUpload(cmd *cobra.Command, args []string) error {
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
		return fmt.Errorf("invalid JSON in config file: %w", err)
	}

	// Build request
	comment, _ := cmd.Flags().GetString("comment")
	noActivate, _ := cmd.Flags().GetBool("no-activate")

	requestBody := map[string]interface{}{
		"config":   configSchema,
		"comment":  comment,
		"activate": !noActivate,
	}

	resp, err := client.DoJSON(http.MethodPost, "/admin/translation-config", requestBody)
	if err != nil {
		return err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions (requires translation.config.write or admin)")
	}
	if resp.StatusCode == http.StatusBadRequest {
		return fmt.Errorf("validation failed: %s", resp.Detail())
	}
	if resp.StatusCode != http.StatusCreated {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var version struct {
		ID      string `json:"id"`
		Version int    `json:"version"`
		Active  bool   `json:"active"`
	}
	if err := resp.DecodeJSON(&version); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	status := "inactive"
	if version.Active {
		status = "active"
	}

	fmt.Printf("Configuration uploaded successfully.\n")
	fmt.Printf("  Version: %d\n", version.Version)
	fmt.Printf("  ID: %s\n", version.ID)
	fmt.Printf("  Status: %s\n", status)

	return nil
}
