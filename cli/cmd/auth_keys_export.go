package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/api"
)

var authKeysExportOutput string

var authKeysExportCmd = &cobra.Command{
	Use:   "export",
	Short: "Export public keys as JWKS",
	Long: `Export public signing keys in JWKS (JSON Web Key Set) format.

The JWKS format is suitable for use by downstream services that need to
verify tokens issued by Aussie.

This command fetches from the public JWKS endpoint and does not require
authentication.

Examples:
  aussie auth keys export
  aussie auth keys export --output jwks.json`,
	RunE: runAuthKeysExport,
}

func init() {
	authKeysCmd.AddCommand(authKeysExportCmd)
	authKeysExportCmd.Flags().StringVarP(&authKeysExportOutput, "output", "o", "", "Write JWKS to file instead of stdout")
}

func runAuthKeysExport(cmd *cobra.Command, args []string) error {
	cfg, err := loadConfigForServer(cmd)
	if err != nil {
		return err
	}
	client, err := api.New(cfg.Host, "")
	if err != nil {
		return err
	}
	resp, err := client.DoJSON(http.MethodGet, "/auth/.well-known/jwks.json", nil)
	if err != nil {
		return err
	}

	switch resp.StatusCode {
	case http.StatusOK:
		// Continue
	case http.StatusServiceUnavailable:
		return fmt.Errorf("key rotation is not enabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	// Pretty-print the JSON
	var jwks map[string]interface{}
	if err := resp.DecodeJSON(&jwks); err != nil {
		return fmt.Errorf("failed to parse JWKS: %w", err)
	}

	prettyJson, err := json.MarshalIndent(jwks, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to format JWKS: %w", err)
	}

	if authKeysExportOutput != "" {
		if err := os.WriteFile(authKeysExportOutput, prettyJson, 0644); err != nil {
			return fmt.Errorf("failed to write file: %w", err)
		}
		fmt.Printf("JWKS written to %s\n", authKeysExportOutput)

		// Print summary
		if keys, ok := jwks["keys"].([]interface{}); ok {
			fmt.Printf("Exported %d public key(s)\n", len(keys))
		}
	} else {
		fmt.Println(string(prettyJson))
	}

	return nil
}
