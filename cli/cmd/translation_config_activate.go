package cmd

import (
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var translationConfigActivateCmd = &cobra.Command{
	Use:   "activate <version-id>",
	Short: "Activate a specific configuration version",
	Long: `Activate a specific translation configuration version by its ID.

Use 'translation-config list' to see available version IDs.
For rollback by version number, use 'translation-config rollback'.

Examples:
  aussie translation-config activate abc123
  aussie translation-config activate 550e8400-e29b-41d4-a716-446655440000`,
	Args: cobra.ExactArgs(1),
	RunE: runTranslationConfigActivate,
}

func init() {
	translationConfigCmd.AddCommand(translationConfigActivateCmd)
}

func runTranslationConfigActivate(cmd *cobra.Command, args []string) error {
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

	versionID := args[0]

	url := fmt.Sprintf("%s/admin/translation-config/%s/activate", cfg.Host, versionID)
	req, err := http.NewRequest("PUT", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)

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
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("version not found: %s", versionID)
	}
	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	fmt.Printf("Activated version: %s\n", versionID)
	return nil
}
