package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var lockoutClearCmd = &cobra.Command{
	Use:   "clear",
	Short: "Clear a lockout for an IP, user, or API key",
	Long: `Clear a lockout for a specific IP address, user identifier,
or API key prefix.

You must specify exactly one of --ip, --user, or --apikey.
Use --all to clear all lockouts (requires --force).

Examples:
  aussie auth lockout clear --ip 192.168.1.100
  aussie auth lockout clear --ip 192.168.1.100 --reason "User verified via support ticket #12345"
  aussie auth lockout clear --user john@example.com
  aussie auth lockout clear --apikey sk_live_
  aussie auth lockout clear --all --force`,
	RunE: runLockoutClear,
}

var lockoutClearIP string
var lockoutClearUser string
var lockoutClearApiKey string
var lockoutClearReason string
var lockoutClearAll bool
var lockoutClearForce bool

func init() {
	lockoutCmd.AddCommand(lockoutClearCmd)
	lockoutClearCmd.Flags().StringVar(&lockoutClearIP, "ip", "", "IP address to unlock")
	lockoutClearCmd.Flags().StringVar(&lockoutClearUser, "user", "", "User identifier to unlock")
	lockoutClearCmd.Flags().StringVar(&lockoutClearApiKey, "apikey", "", "API key prefix to unlock")
	lockoutClearCmd.Flags().StringVar(&lockoutClearReason, "reason", "", "Reason for clearing the lockout (for audit log)")
	lockoutClearCmd.Flags().BoolVar(&lockoutClearAll, "all", false, "Clear all lockouts (emergency use)")
	lockoutClearCmd.Flags().BoolVar(&lockoutClearForce, "force", false, "Required with --all to confirm clearing all lockouts")
}

func runLockoutClear(cmd *cobra.Command, args []string) error {
	// Handle --all case
	if lockoutClearAll {
		return runLockoutClearAll()
	}

	// Validate that exactly one identifier is provided
	identifierCount := 0
	if lockoutClearIP != "" {
		identifierCount++
	}
	if lockoutClearUser != "" {
		identifierCount++
	}
	if lockoutClearApiKey != "" {
		identifierCount++
	}

	if identifierCount == 0 {
		return fmt.Errorf("must specify one of --ip, --user, --apikey, or --all")
	}
	if identifierCount > 1 {
		return fmt.Errorf("must specify only one of --ip, --user, or --apikey")
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

	// Build URL based on identifier type
	var url string
	var identifierType, identifierValue string

	if lockoutClearIP != "" {
		url = fmt.Sprintf("%s/admin/lockouts/ips/%s", cfg.Host, lockoutClearIP)
		identifierType = "IP"
		identifierValue = lockoutClearIP
	} else if lockoutClearUser != "" {
		url = fmt.Sprintf("%s/admin/lockouts/users/%s", cfg.Host, lockoutClearUser)
		identifierType = "user"
		identifierValue = lockoutClearUser
	} else {
		url = fmt.Sprintf("%s/admin/lockouts/apikeys/%s", cfg.Host, lockoutClearApiKey)
		identifierType = "API key"
		identifierValue = lockoutClearApiKey
	}

	// Create request body
	body, _ := json.Marshal(map[string]string{
		"reason": lockoutClearReason,
	})

	req, err := http.NewRequest("DELETE", url, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusNoContent:
		fmt.Printf("✓ Cleared lockout for %s %s\n", identifierType, identifierValue)
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie auth login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to clear lockouts")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("authentication rate limiting is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}

func runLockoutClearAll() error {
	if !lockoutClearForce {
		return fmt.Errorf("clearing all lockouts requires --force flag")
	}

	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	// Get authentication token
	token, err := auth.GetAuthToken(cfg.ApiKey)
	if err != nil {
		return err
	}

	url := fmt.Sprintf("%s/admin/lockouts:reset", cfg.Host)

	// Create request body
	body, _ := json.Marshal(map[string]interface{}{
		"force":  true,
		"reason": lockoutClearReason,
	})

	req, err := http.NewRequest("POST", url, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK:
		var result map[string]interface{}
		json.NewDecoder(resp.Body).Decode(&result)
		count := int(result["count"].(float64))
		fmt.Printf("✓ Cleared %d lockouts\n", count)
		return nil
	case http.StatusBadRequest:
		return fmt.Errorf("must set force=true to clear all lockouts")
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie auth login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to clear all lockouts")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("authentication rate limiting is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}
