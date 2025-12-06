package cmd

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/config"
)

var logoutCmd = &cobra.Command{
	Use:   "logout",
	Short: "Remove stored credentials",
	Long: `Remove the stored API key from your configuration.

This command clears the API key from ~/.aussie. You will need to run
'aussie auth login' again to authenticate.

Examples:
  aussie auth logout`,
	RunE: runLogout,
}

func init() {
	authCmd.AddCommand(logoutCmd)
}

func runLogout(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	if !cfg.IsAuthenticated() {
		fmt.Println("Not currently authenticated.")
		return nil
	}

	if err := cfg.ClearApiKey(); err != nil {
		return fmt.Errorf("failed to clear credentials: %w", err)
	}

	fmt.Println("Logged out successfully.")
	return nil
}
