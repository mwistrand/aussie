package cmd

import (
	"fmt"
	"net/http"
	"strconv"

	"github.com/spf13/cobra"
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
	versionNumber, err := strconv.Atoi(args[0])
	if err != nil {
		return fmt.Errorf("invalid version number: %s", args[0])
	}

	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}
	resp, err := client.DoJSON(http.MethodPost, fmt.Sprintf("/admin/translation-config/rollback/%d", versionNumber), nil)
	if err != nil {
		return err
	}

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
	if err := resp.DecodeJSON(&version); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Printf("Rolled back to version %d\n", version.Version)
	if version.Comment != "" {
		fmt.Printf("  Comment: %s\n", version.Comment)
	}

	return nil
}
