package cmd

import (
	"github.com/spf13/cobra"
)

var translationConfigCmd = &cobra.Command{
	Use:     "translation-config",
	Aliases: []string{"tc"},
	Short:   "Manage token translation configuration",
	Long: `Commands for managing token translation configurations.

Token translation controls how external IdP token claims are mapped to
Aussie's internal authorization model (roles and permissions).

Examples:
  aussie translation-config list
  aussie translation-config get
  aussie translation-config upload config.json --activate --comment "Initial config"
  aussie translation-config validate config.json
  aussie translation-config rollback 1
  aussie translation-config test --issuer "https://auth.example.com" --subject "user-123" --claims '{"groups": ["admin"]}'`,
}

func init() {
	rootCmd.AddCommand(translationConfigCmd)
}
