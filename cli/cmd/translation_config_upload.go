package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
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

	body, err := json.Marshal(requestBody)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}

	url := fmt.Sprintf("%s/admin/translation-config", cfg.Host)
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
		return fmt.Errorf("insufficient permissions (requires translation.config.write or admin)")
	}
	if resp.StatusCode == http.StatusBadRequest {
		respBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("validation failed: %s", string(respBody))
	}
	if resp.StatusCode != http.StatusCreated {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var version struct {
		ID      string `json:"id"`
		Version int    `json:"version"`
		Active  bool   `json:"active"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&version); err != nil {
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
