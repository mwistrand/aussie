package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/config"
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
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}

	// JWKS endpoint is public, no auth needed
	url := fmt.Sprintf("%s/auth/.well-known/jwks.json", cfg.Host)
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK:
		// Continue
	case http.StatusServiceUnavailable:
		return fmt.Errorf("key rotation is not enabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("failed to read response: %w", err)
	}

	// Pretty-print the JSON
	var jwks map[string]interface{}
	if err := json.Unmarshal(body, &jwks); err != nil {
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
