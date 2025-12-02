package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"

	"github.com/aussie/cli/internal/config"
	"github.com/spf13/cobra"
)

var (
	registerFile string
)

var registerCmd = &cobra.Command{
	Use:   "register",
	Short: "Register a service with the Aussie API gateway",
	Long: `Register a service with the Aussie API gateway using a JSON configuration file.

The configuration file should contain the service definition including:
- serviceId: Unique identifier for the service
- displayName: Human-readable name
- baseUrl: The backend service URL
- endpoints: List of endpoints with path, methods, and visibility

Example:
  aussie register -f service.json
  aussie register --file ./my-service.json`,
	RunE: runRegister,
}

func init() {
	rootCmd.AddCommand(registerCmd)
	registerCmd.Flags().StringVarP(&registerFile, "file", "f", "", "Path to the service configuration JSON file (required)")
	registerCmd.MarkFlagRequired("file")
}

func runRegister(cmd *cobra.Command, args []string) error {
	// Load configuration
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Check if server flag was provided (overrides config)
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" && serverFlag != "http://localhost:8080" {
		cfg.Host = serverFlag
	}

	// Read the service configuration file
	data, err := os.ReadFile(registerFile)
	if err != nil {
		return fmt.Errorf("failed to read file %s: %w", registerFile, err)
	}

	// Validate it's valid JSON
	var serviceConfig map[string]interface{}
	if err := json.Unmarshal(data, &serviceConfig); err != nil {
		return fmt.Errorf("invalid JSON in %s: %w", registerFile, err)
	}

	// Check required fields
	if _, ok := serviceConfig["serviceId"]; !ok {
		return fmt.Errorf("missing required field 'serviceId' in %s", registerFile)
	}
	if _, ok := serviceConfig["baseUrl"]; !ok {
		return fmt.Errorf("missing required field 'baseUrl' in %s", registerFile)
	}

	// Send registration request to Aussie
	url := fmt.Sprintf("%s/admin/services", cfg.Host)
	req, err := http.NewRequest("POST", url, bytes.NewReader(data))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to Aussie at %s: %w", cfg.Host, err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	if resp.StatusCode == http.StatusCreated {
		// Pretty print the response
		var result map[string]interface{}
		if err := json.Unmarshal(body, &result); err == nil {
			prettyJSON, _ := json.MarshalIndent(result, "", "  ")
			fmt.Printf("Service registered successfully!\n\n%s\n", string(prettyJSON))
		} else {
			fmt.Printf("Service registered successfully!\n%s\n", string(body))
		}
		return nil
	}

	// Handle errors
	switch resp.StatusCode {
	case http.StatusBadRequest:
		return fmt.Errorf("invalid service configuration: %s", string(body))
	case http.StatusConflict:
		return fmt.Errorf("service already exists: %s", string(body))
	default:
		return fmt.Errorf("registration failed (HTTP %d): %s", resp.StatusCode, string(body))
	}
}
