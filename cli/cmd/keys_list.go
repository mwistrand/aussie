package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"text/tabwriter"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/config"
)

var keysListCmd = &cobra.Command{
	Use:   "list",
	Short: "List all API keys",
	Long: `List all API keys registered with the Aussie API gateway.

Displays key ID, name, permissions, creation date, expiration, and status.

Examples:
  aussie keys list`,
	RunE: runKeysList,
}

func init() {
	keysCmd.AddCommand(keysListCmd)
}

func runKeysList(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override with server flag if provided
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	if !cfg.IsAuthenticated() {
		return fmt.Errorf("not authenticated. Run 'aussie login' to authenticate")
	}

	url := fmt.Sprintf("%s/admin/api-keys", cfg.Host)
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+cfg.ApiKey)

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
		return fmt.Errorf("insufficient permissions to list API keys")
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var keys []struct {
		Id          string   `json:"id"`
		Name        string   `json:"name"`
		Description string   `json:"description"`
		Permissions []string `json:"permissions"`
		CreatedBy   string   `json:"createdBy"`
		CreatedAt   string   `json:"createdAt"`
		ExpiresAt   string   `json:"expiresAt,omitempty"`
		Revoked     bool     `json:"revoked"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&keys); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	if len(keys) == 0 {
		fmt.Println("No API keys found.")
		return nil
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "ID\tNAME\tPERMISSIONS\tCREATED BY\tEXPIRES\tSTATUS")
	fmt.Fprintln(w, "--\t----\t-----------\t----------\t-------\t------")

	for _, key := range keys {
		status := "active"
		if key.Revoked {
			status = "revoked"
		}

		expires := "-"
		if key.ExpiresAt != "" {
			if len(key.ExpiresAt) >= 10 {
				expires = key.ExpiresAt[:10] // Just the date part
			} else {
				expires = key.ExpiresAt
			}
		}

		perms := "-"
		if len(key.Permissions) > 0 {
			if len(key.Permissions) == 1 && key.Permissions[0] == "*" {
				perms = "*"
			} else {
				perms = fmt.Sprintf("%d permissions", len(key.Permissions))
			}
		}

		createdBy := key.CreatedBy
		if createdBy == "" {
			createdBy = "-"
		}

		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\n",
			key.Id, key.Name, perms, createdBy, expires, status)
	}
	w.Flush()

	return nil
}
