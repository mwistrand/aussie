package cmd

import (
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
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
	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	versionID := args[0]

	resp, err := client.DoJSON(http.MethodPut, "/admin/translation-config/"+versionID+"/activate", nil)
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
		return fmt.Errorf("version not found: %s", versionID)
	}
	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	fmt.Printf("Activated version: %s\n", versionID)
	return nil
}
