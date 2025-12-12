package cmd

import (
	"github.com/spf13/cobra"
)

var rootCmd = &cobra.Command{
	Use:   "aussie",
	Short: "Aussie CLI - Command line interface for the Aussie API gateway",
	Long: `Aussie CLI is a command line tool for interacting with
and managing the Aussie API gateway.

Use this CLI to register services, manage routes, and monitor your API gateway.

Configuration is loaded from (in order of precedence):
  1. Command-line flags
  2. Local .aussierc file (current directory)
  3. Global ~/.aussierc config file

Example config file (.aussierc or ~/.aussierc):
  host = "http://localhost:8080"
  api_key = "your-api-key"`,
}

func Execute() error {
	return rootCmd.Execute()
}

func init() {
	rootCmd.PersistentFlags().StringP("server", "s", "", "Aussie API server URL (overrides config file)")
}
