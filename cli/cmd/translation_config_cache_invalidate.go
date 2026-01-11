package cmd

import (
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var translationConfigCacheInvalidateCmd = &cobra.Command{
	Use:     "cache-invalidate",
	Aliases: []string{"cache-clear"},
	Short:   "Invalidate the translation cache",
	Long: `Invalidate all cached token translation results.

This forces re-translation for all subsequent token validations.
Use this after updating translation configuration to ensure
the new configuration takes effect immediately.

Examples:
  aussie translation-config cache-invalidate`,
	RunE: runTranslationConfigCacheInvalidate,
}

func init() {
	translationConfigCmd.AddCommand(translationConfigCacheInvalidateCmd)
}

func runTranslationConfigCacheInvalidate(cmd *cobra.Command, args []string) error {
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

	url := fmt.Sprintf("%s/admin/translation-config/cache/invalidate", cfg.Host)

	req, err := http.NewRequest("POST", url, nil)
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
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	fmt.Println("Translation cache invalidated successfully")
	return nil
}
