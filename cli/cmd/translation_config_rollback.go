package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var translationConfigRollbackCmd = &cobra.Command{
	Use:   "rollback <version-number>",
	Short: "Rollback to a previous configuration version",
	Long: `Rollback to a previous translation configuration version.

Activates the specified version number, making it the current active configuration.

Examples:
  aussie translation-config rollback 1
  aussie translation-config rollback 5`,
	Args: cobra.ExactArgs(1),
	RunE: runTranslationConfigRollback,
}

func init() {
	translationConfigCmd.AddCommand(translationConfigRollbackCmd)
}

func runTranslationConfigRollback(cmd *cobra.Command, args []string) error {
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

	versionNumber, err := strconv.Atoi(args[0])
	if err != nil {
		return fmt.Errorf("invalid version number: %s", args[0])
	}

	url := fmt.Sprintf("%s/admin/translation-config/rollback/%d", cfg.Host, versionNumber)
	req, err := http.NewRequest("POST", url, nil)
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
		return fmt.Errorf("version %d not found", versionNumber)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var version struct {
		ID      string `json:"id"`
		Version int    `json:"version"`
		Comment string `json:"comment"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&version); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Printf("Rolled back to version %d\n", version.Version)
	if version.Comment != "" {
		fmt.Printf("  Comment: %s\n", version.Comment)
	}

	return nil
}
