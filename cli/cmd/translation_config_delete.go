package cmd

import (
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
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
	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	versionID := args[0]

	resp, err := client.DoJSON(http.MethodDelete, "/admin/translation-config/"+versionID, nil)
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
	if resp.StatusCode == http.StatusBadRequest {
		return fmt.Errorf("cannot delete active version. Activate a different version first")
	}
	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	fmt.Printf("Deleted version: %s\n", versionID)
	return nil
}
