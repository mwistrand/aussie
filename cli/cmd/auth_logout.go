package cmd

import (
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var logoutCmd = &cobra.Command{
	Use:   "logout",
	Short: "Clear stored authentication credentials",
	Long: `Remove locally stored authentication tokens.

Optionally calls the IdP logout endpoint to invalidate server-side sessions
if configured and --server flag is provided.

Examples:
  aussie auth logout           # Clear local credentials only
  aussie auth logout --server  # Also invalidate server session`,
	RunE: runLogout,
}

func init() {
	authCmd.AddCommand(logoutCmd)
	logoutCmd.Flags().Bool("server", false, "Also logout from IdP server (if configured)")
}

func runLogout(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	serverLogout, _ := cmd.Flags().GetBool("server")

	// Load current credentials for server logout (if needed)
	creds, _ := auth.LoadCredentials()

	// Clear local credentials
	if err := auth.ClearCredentials(); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to clear credentials: %w", err)
	}

	fmt.Println("Local credentials cleared.")

	// Optionally call server logout
	if serverLogout && cfg.Auth.LogoutURL != "" && creds != nil {
		if err := serverSideLogout(cfg.Auth.LogoutURL, creds.Token); err != nil {
			fmt.Printf("Warning: Server logout failed: %v\n", err)
		} else {
			fmt.Println("Server session invalidated.")
		}
	} else if serverLogout && cfg.Auth.LogoutURL == "" {
		fmt.Println("Note: Server logout not configured (auth.logout_url not set).")
	} else if serverLogout && creds == nil {
		fmt.Println("Note: No active session to invalidate on server.")
	}

	return nil
}

// serverSideLogout calls the server logout endpoint to invalidate the session.
func serverSideLogout(logoutURL, token string) error {
	req, err := http.NewRequest("POST", logoutURL, nil)
	if err != nil {
		return fmt.Errorf("failed to create logout request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("logout request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("server returned status %d", resp.StatusCode)
	}

	return nil
}
