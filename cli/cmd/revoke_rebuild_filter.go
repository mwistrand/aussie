package cmd

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var revokeRebuildFilterCmd = &cobra.Command{
	Use:   "rebuild-filter",
	Short: "Force rebuild of the revocation bloom filter",
	Long: `Force a rebuild of the revocation bloom filter from the remote store.

This is useful after:
  - Manual manipulation of the revocation store
  - Recovering from bloom filter desync
  - Debugging revocation issues

Note: Bloom filters are automatically rebuilt periodically. This command
forces an immediate rebuild.

Examples:
  aussie auth revoke rebuild-filter
  aussie auth revoke rebuild-filter --force`,
	RunE: runRevokeRebuildFilter,
}

var revokeRebuildFilterForce bool
var revokeRebuildFilterFormat string

func init() {
	revokeCmd.AddCommand(revokeRebuildFilterCmd)
	revokeRebuildFilterCmd.Flags().BoolVar(&revokeRebuildFilterForce, "force", false, "Skip confirmation prompt")
	revokeRebuildFilterCmd.Flags().StringVar(&revokeRebuildFilterFormat, "format", "table", "Output format (table|json)")
}

func runRevokeRebuildFilter(cmd *cobra.Command, args []string) error {
	// Confirm unless --force is used
	if !revokeRebuildFilterForce {
		fmt.Println("This will rebuild the bloom filter from the remote store.")
		fmt.Println("The operation may take a few seconds for large revocation lists.")
		fmt.Print("Continue? [y/N]: ")
		reader := bufio.NewReader(os.Stdin)
		response, _ := reader.ReadString('\n')
		response = strings.TrimSpace(strings.ToLower(response))
		if response != "y" && response != "yes" {
			fmt.Println("Aborted.")
			return nil
		}
	}

	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Override with server flag if provided
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	// Get authentication token
	token, err := auth.GetAuthToken(cfg.ApiKey)
	if err != nil {
		return err
	}

	url := fmt.Sprintf("%s/admin/tokens/bloom-filter/rebuild", cfg.Host)
	req, err := http.NewRequest("POST", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)

	client := &http.Client{Timeout: 60 * time.Second} // Longer timeout for rebuild
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK:
		body, err := io.ReadAll(resp.Body)
		if err != nil {
			return fmt.Errorf("failed to read response: %w", err)
		}

		var result map[string]interface{}
		if err := json.Unmarshal(body, &result); err != nil {
			return fmt.Errorf("failed to parse response: %w", err)
		}

		if revokeRebuildFilterFormat == "json" {
			output, _ := json.MarshalIndent(result, "", "  ")
			fmt.Println(string(output))
		} else {
			status, _ := result["status"].(string)
			rebuiltAt, _ := result["rebuiltAt"].(string)

			fmt.Printf("✓ Bloom filter %s\n", status)
			fmt.Printf("  Completed: %s\n", rebuiltAt)
		}
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to rebuild bloom filter (requires admin)")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("token revocation is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}
