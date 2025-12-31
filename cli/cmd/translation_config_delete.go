package cmd

import (
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var translationConfigDeleteCmd = &cobra.Command{
	Use:   "delete <version-id>",
	Short: "Delete a configuration version",
	Long: `Delete a translation configuration version.

Note: Active versions cannot be deleted. Activate a different version first.

Examples:
  aussie translation-config delete abc123`,
	Args: cobra.ExactArgs(1),
	RunE: runTranslationConfigDelete,
}

func init() {
	translationConfigCmd.AddCommand(translationConfigDeleteCmd)
}

func runTranslationConfigDelete(cmd *cobra.Command, args []string) error {
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

	url := fmt.Sprintf("%s/admin/translation-config/%s", cfg.Host, versionID)
	req, err := http.NewRequest("DELETE", url, nil)
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
	if resp.StatusCode == http.StatusBadRequest {
		return fmt.Errorf("cannot delete active version. Activate a different version first")
	}
	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	fmt.Printf("Deleted version: %s\n", versionID)
	return nil
}
