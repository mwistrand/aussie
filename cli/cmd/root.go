package cmd

import (
	"github.com/spf13/cobra"
)

var rootCmd = &cobra.Command{
	Use:   "aussie",
	Short: "Aussie CLI - Command line interface for the Aussie API gateway",
	Long: `Aussie CLI is a command line tool for interacting with
and managing the Aussie API gateway.

Use this CLI to configure routes, manage upstreams,
and monitor your API gateway.`,
}

func Execute() error {
	return rootCmd.Execute()
}

func init() {
	rootCmd.PersistentFlags().StringP("config", "c", "", "config file (default is $HOME/.aussie.yaml)")
	rootCmd.PersistentFlags().StringP("server", "s", "http://localhost:8080", "Aussie API server URL")
}
