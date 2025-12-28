package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"text/tabwriter"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var authKeysListFormat string

var authKeysListCmd = &cobra.Command{
	Use:   "list",
	Short: "List all signing keys",
	Long: `List all signing keys and their statuses.

Displays key ID, status, creation date, and lifecycle timestamps.

Examples:
  aussie auth keys list
  aussie auth keys list --format json`,
	RunE: runAuthKeysList,
}

func init() {
	authKeysCmd.AddCommand(authKeysListCmd)
	authKeysListCmd.Flags().StringVar(&authKeysListFormat, "format", "table", "Output format: table or json")
}

// signingKey represents a signing key returned by the API.
type signingKey struct {
	KeyId        string `json:"keyId"`
	Status       string `json:"status"`
	CreatedAt    string `json:"createdAt"`
	ActivatedAt  string `json:"activatedAt,omitempty"`
	DeprecatedAt string `json:"deprecatedAt,omitempty"`
	RetiredAt    string `json:"retiredAt,omitempty"`
}

func runAuthKeysList(cmd *cobra.Command, args []string) error {
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

	url := fmt.Sprintf("%s/admin/keys", cfg.Host)
	req, err := http.NewRequest("GET", url, nil)
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

	switch resp.StatusCode {
	case http.StatusOK:
		// Continue
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to list signing keys (requires keys.read)")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("key rotation is not enabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var keys []signingKey
	if err := json.NewDecoder(resp.Body).Decode(&keys); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	if authKeysListFormat == "json" {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(keys)
	}

	if len(keys) == 0 {
		fmt.Println("No signing keys found.")
		return nil
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "KEY ID\tSTATUS\tCREATED\tACTIVATED\tDEPRECATED")
	fmt.Fprintln(w, "------\t------\t-------\t---------\t----------")

	for _, key := range keys {
		created := formatDate(key.CreatedAt)
		activated := formatDate(key.ActivatedAt)
		deprecated := formatDate(key.DeprecatedAt)

		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\n",
			key.KeyId, key.Status, created, activated, deprecated)
	}
	w.Flush()

	return nil
}

// formatDate formats an ISO timestamp as a date string for display.
func formatDate(ts string) string {
	if ts == "" {
		return "-"
	}
	// Try to parse ISO timestamp and return just the date
	t, err := time.Parse(time.RFC3339, ts)
	if err != nil {
		// Return first 10 chars if parsing fails
		if len(ts) >= 10 {
			return ts[:10]
		}
		return ts
	}
	return t.Format("2006-01-02")
}
