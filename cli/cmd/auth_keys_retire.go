package cmd

import (
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var authKeysRetireForce bool

var authKeysRetireCmd = &cobra.Command{
	Use:   "retire <key-id>",
	Short: "Retire a signing key",
	Long: `Retire a signing key, removing it from active use.

Retired keys can no longer sign or verify tokens. This action should only
be taken after all tokens signed by this key have expired.

Use --force to retire a key that is still ACTIVE (not recommended).

Examples:
  aussie auth keys retire k-2024-q1-abc123
  aussie auth keys retire k-2024-q1-abc123 --force`,
	Args: cobra.ExactArgs(1),
	RunE: runAuthKeysRetire,
}

func init() {
	authKeysCmd.AddCommand(authKeysRetireCmd)
	authKeysRetireCmd.Flags().BoolVar(&authKeysRetireForce, "force", false, "Force retire an active key (dangerous)")
}

func runAuthKeysRetire(cmd *cobra.Command, args []string) error {
	keyId := args[0]

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

	url := fmt.Sprintf("%s/admin/keys/%s", cfg.Host, keyId)
	if authKeysRetireForce {
		url += "?force=true"
	}

	req, err := http.NewRequest("DELETE", url, nil)
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
	case http.StatusNoContent:
		fmt.Printf("Key %s has been retired.\n", keyId)
		fmt.Println("The key can no longer sign or verify tokens.")
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to retire signing keys (requires keys.write)")
	case http.StatusNotFound:
		return fmt.Errorf("signing key not found: %s", keyId)
	case http.StatusConflict:
		return fmt.Errorf("key %s is still active. Use --force to retire an active key (not recommended)", keyId)
	case http.StatusServiceUnavailable:
		return fmt.Errorf("key rotation is not enabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}
