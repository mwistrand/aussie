package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"

	"github.com/spf13/cobra"
)

var translationConfigGetCmd = &cobra.Command{
	Use:   "get [version-id]",
	Short: "Get translation configuration",
	Long: `Get the active translation configuration or a specific version.

If no version ID is provided, returns the currently active configuration.
If a version ID is provided, returns that specific version.

Examples:
  aussie translation-config get
  aussie translation-config get abc123
  aussie translation-config get --output json`,
	RunE: runTranslationConfigGet,
}

func init() {
	translationConfigGetCmd.Flags().StringP("output", "o", "json", "Output format (json)")
	translationConfigCmd.AddCommand(translationConfigGetCmd)
}

func runTranslationConfigGet(cmd *cobra.Command, args []string) error {
	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	var path string
	if len(args) > 0 {
		path = "/admin/translation-config/" + args[0]
	} else {
		path = "/admin/translation-config/active"
	}

	resp, err := client.DoJSON(http.MethodGet, path, nil)
	if err != nil {
		return err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions (requires translation.config.read or admin)")
	}
	if resp.StatusCode == http.StatusNotFound {
		if len(args) > 0 {
			return fmt.Errorf("configuration version not found: %s", args[0])
		}
		return fmt.Errorf("no active translation configuration")
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var version map[string]interface{}
	if err := resp.DecodeJSON(&version); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	// Pretty print JSON
	output, err := json.MarshalIndent(version, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to format output: %w", err)
	}

	fmt.Fprintln(os.Stdout, string(output))
	return nil
}
