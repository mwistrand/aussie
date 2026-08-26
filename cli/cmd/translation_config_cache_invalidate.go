package cmd

import (
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
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
	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	resp, err := client.DoJSON(http.MethodPost, "/admin/translation-config/cache/invalidate", nil)
	if err != nil {
		return err
	}

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
