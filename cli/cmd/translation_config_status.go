package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"

	"github.com/spf13/cobra"
)

var translationConfigStatusCmd = &cobra.Command{
	Use:   "status",
	Short: "Get token translation service status",
	Long: `Get the current status of the token translation service.

Returns information about:
  - Whether token translation is enabled
  - The active translation provider
  - Provider health status
  - Translation cache statistics (size, TTL, max size)

Examples:
  aussie translation-config status`,
	RunE: runTranslationConfigStatus,
}

func init() {
	translationConfigStatusCmd.Flags().StringP("output", "o", "table", "Output format (table, json)")
	translationConfigCmd.AddCommand(translationConfigStatusCmd)
}

type translationStatus struct {
	Enabled         bool        `json:"enabled"`
	ActiveProvider  string      `json:"activeProvider"`
	ProviderHealthy bool        `json:"providerHealthy"`
	Cache           cacheStatus `json:"cache"`
}

type cacheStatus struct {
	CurrentSize int64 `json:"currentSize"`
	MaxSize     int64 `json:"maxSize"`
	TtlSeconds  int   `json:"ttlSeconds"`
}

func runTranslationConfigStatus(cmd *cobra.Command, args []string) error {
	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	resp, err := client.DoJSON(http.MethodGet, "/admin/translation-config/status", nil)
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

	var status translationStatus
	if err := resp.DecodeJSON(&status); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	outputFormat, _ := cmd.Flags().GetString("output")
	if outputFormat == "json" {
		output, err := json.MarshalIndent(status, "", "  ")
		if err != nil {
			return fmt.Errorf("failed to format output: %w", err)
		}
		fmt.Fprintln(os.Stdout, string(output))
	} else {
		printStatusTable(status)
	}

	return nil
}

func printStatusTable(status translationStatus) {
	enabledStr := "disabled"
	if status.Enabled {
		enabledStr = "enabled"
	}

	healthStr := "unhealthy"
	if status.ProviderHealthy {
		healthStr = "healthy"
	}

	fmt.Println("Token Translation Status")
	fmt.Println("========================")
	fmt.Printf("Status:          %s\n", enabledStr)
	fmt.Printf("Active Provider: %s (%s)\n", status.ActiveProvider, healthStr)
	fmt.Println()
	fmt.Println("Cache Statistics")
	fmt.Println("----------------")
	fmt.Printf("Current Size:    %d entries\n", status.Cache.CurrentSize)
	fmt.Printf("Max Size:        %d entries\n", status.Cache.MaxSize)
	fmt.Printf("TTL:             %d seconds\n", status.Cache.TtlSeconds)
}
