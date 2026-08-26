package cmd

import (
	"fmt"
	"net/http"
	"os"
	"text/tabwriter"

	"github.com/spf13/cobra"
)

var translationConfigListCmd = &cobra.Command{
	Use:   "list",
	Short: "List translation configuration versions",
	Long: `List all translation configuration versions.

Displays version number, creation time, status (active/inactive), and comments.

Examples:
  aussie translation-config list
  aussie translation-config list --limit 10`,
	RunE: runTranslationConfigList,
}

func init() {
	translationConfigListCmd.Flags().IntP("limit", "l", 50, "Maximum number of versions to return")
	translationConfigListCmd.Flags().IntP("offset", "o", 0, "Number of versions to skip")
	translationConfigCmd.AddCommand(translationConfigListCmd)
}

func runTranslationConfigList(cmd *cobra.Command, args []string) error {
	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	limit, _ := cmd.Flags().GetInt("limit")
	offset, _ := cmd.Flags().GetInt("offset")

	resp, err := client.DoJSON(http.MethodGet, fmt.Sprintf("/admin/translation-config?limit=%d&offset=%d", limit, offset), nil)
	if err != nil {
		return err
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	}
	if resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("insufficient permissions (requires translation.config.read or admin)")
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var versions []struct {
		ID        string `json:"id"`
		Version   int    `json:"version"`
		Active    bool   `json:"active"`
		CreatedBy string `json:"createdBy"`
		CreatedAt string `json:"createdAt"`
		Comment   string `json:"comment"`
	}
	if err := resp.DecodeJSON(&versions); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	if len(versions) == 0 {
		fmt.Println("No translation configurations found.")
		return nil
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "VERSION\tSTATUS\tCREATED BY\tCREATED AT\tCOMMENT")
	fmt.Fprintln(w, "-------\t------\t----------\t----------\t-------")

	for _, v := range versions {
		status := "inactive"
		if v.Active {
			status = "ACTIVE"
		}

		createdAt := "-"
		if v.CreatedAt != "" && len(v.CreatedAt) >= 10 {
			createdAt = v.CreatedAt[:10]
		}

		createdBy := v.CreatedBy
		if createdBy == "" {
			createdBy = "-"
		}

		comment := v.Comment
		if comment == "" {
			comment = "-"
		}
		if len(comment) > 40 {
			comment = comment[:37] + "..."
		}

		fmt.Fprintf(w, "%d\t%s\t%s\t%s\t%s\n", v.Version, status, createdBy, createdAt, comment)
	}
	w.Flush()

	return nil
}
