package cmd

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os/exec"
	"runtime"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

var loginCmd = &cobra.Command{
	Use:   "login",
	Short: "Authenticate with your organization's identity provider",
	Long: `Authenticate with your organization's IdP to obtain a short-lived token.

This command triggers your organization's authentication flow. Aussie does not
handle credentials directly - authentication is delegated to your IdP.

Authentication Modes:
  browser      Opens a browser for OAuth/SAML login (default)
  device_code  Uses device code flow for headless environments
  cli_callback Starts a local server to receive the callback

The default mode can be configured in .aussierc:

  [auth]
  mode = "device_code"  # For headless environments

Configuration:
  Set auth.login_url in .aussierc to point to your organization's
  authentication endpoint (translation layer).

Examples:
  aussie auth login                     # Uses mode from config (default: browser)
  aussie auth login --mode device_code  # Override config for this invocation`,
	RunE: runLogin,
}

func init() {
	authCmd.AddCommand(loginCmd)
	loginCmd.Flags().String("mode", "", "Auth mode: browser, device_code, cli_callback (overrides config)")
}

func runLogin(cmd *cobra.Command, args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	if cfg.Auth.LoginURL == "" {
		return fmt.Errorf(`auth.login_url not configured in .aussierc

Please configure your organization's authentication endpoint:

[auth]
login_url = "https://your-org.example.com/auth/aussie/login"`)
	}

	// Determine auth mode: flag overrides config, config defaults to "browser"
	var mode config.AuthMode
	if modeFlag, _ := cmd.Flags().GetString("mode"); modeFlag != "" {
		mode = config.AuthMode(modeFlag)
		if !mode.IsValid() {
			return fmt.Errorf("invalid auth mode: %s\nValid modes: browser, device_code, cli_callback", modeFlag)
		}
	} else {
		mode = cfg.Auth.GetMode()
	}

	switch mode {
	case config.AuthModeBrowser:
		return browserLogin(cfg)
	case config.AuthModeDeviceCode:
		return deviceCodeLogin(cfg)
	case config.AuthModeCLICallback:
		return callbackLogin(cfg)
	default:
		return fmt.Errorf("unknown auth mode: %s", mode)
	}
}

// browserLogin opens the browser to the login URL with a callback parameter.
// The translation layer handles the IdP flow and redirects back with a token.
func browserLogin(cfg *config.Config) error {
	// Start local server to receive callback
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("failed to start callback server: %w", err)
	}
	defer listener.Close()

	port := listener.Addr().(*net.TCPAddr).Port
	callbackURL := fmt.Sprintf("http://127.0.0.1:%d/callback", port)

	// Build login URL with callback
	loginURL, err := url.Parse(cfg.Auth.LoginURL)
	if err != nil {
		return fmt.Errorf("invalid login URL: %w", err)
	}
	q := loginURL.Query()
	q.Set("callback", callbackURL)
	loginURL.RawQuery = q.Encode()

	fmt.Printf("Opening browser for authentication...\n")
	fmt.Printf("If the browser doesn't open, visit:\n  %s\n\n", loginURL.String())

	// Open browser
	if err := openBrowser(loginURL.String()); err != nil {
		fmt.Printf("Could not open browser: %v\n", err)
	}

	// Wait for callback with channels
	tokenChan := make(chan string)
	errChan := make(chan error)

	server := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/callback" {
				http.NotFound(w, r)
				return
			}

			token := r.URL.Query().Get("token")
			if token == "" {
				errMsg := r.URL.Query().Get("error")
				if errMsg == "" {
					errMsg = "no token received"
				}
				w.Header().Set("Content-Type", "text/html")
				w.Write([]byte(`<html><body><h1>Authentication Failed</h1><p>You may close this window.</p></body></html>`))
				errChan <- fmt.Errorf("authentication failed: %s", errMsg)
				return
			}

			w.Header().Set("Content-Type", "text/html")
			w.Write([]byte(`<html><body><h1>Authentication Successful!</h1><p>You may close this window.</p></body></html>`))
			tokenChan <- token
		}),
	}

	go server.Serve(listener)

	// Wait for result with timeout
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	var result error
	var token string

	select {
	case token = <-tokenChan:
		result = nil
	case result = <-errChan:
	case <-ctx.Done():
		result = fmt.Errorf("authentication timed out after 5 minutes")
	}

	// Gracefully shutdown the server
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer shutdownCancel()
	_ = server.Shutdown(shutdownCtx)

	if result != nil {
		return result
	}
	return storeAndPrintCredentials(token)
}

// deviceCodeLogin uses device code flow for headless environments.
func deviceCodeLogin(cfg *config.Config) error {
	// Request device code from translation layer
	deviceURL, err := url.Parse(cfg.Auth.LoginURL)
	if err != nil {
		return fmt.Errorf("invalid login URL: %w", err)
	}
	q := deviceURL.Query()
	q.Set("flow", "device_code")
	deviceURL.RawQuery = q.Encode()

	resp, err := http.Post(deviceURL.String(), "application/json", nil)
	if err != nil {
		return fmt.Errorf("failed to initiate device code flow: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("device code request failed with status %d", resp.StatusCode)
	}

	var deviceResp struct {
		DeviceCode      string `json:"device_code"`
		UserCode        string `json:"user_code"`
		VerificationURL string `json:"verification_url"`
		ExpiresIn       int    `json:"expires_in"`
		Interval        int    `json:"interval"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&deviceResp); err != nil {
		return fmt.Errorf("failed to parse device code response: %w", err)
	}

	fmt.Printf("\nTo authenticate, visit:\n  %s\n\n", deviceResp.VerificationURL)
	fmt.Printf("And enter code: %s\n\n", deviceResp.UserCode)
	fmt.Printf("Waiting for authentication...\n")

	// Poll for token
	interval := time.Duration(deviceResp.Interval) * time.Second
	if interval < time.Second {
		interval = 5 * time.Second
	}

	deadline := time.Now().Add(time.Duration(deviceResp.ExpiresIn) * time.Second)

	for time.Now().Before(deadline) {
		time.Sleep(interval)

		token, err := pollForToken(cfg.Auth.LoginURL, deviceResp.DeviceCode)
		if err != nil {
			// Check if it's a "pending" error (user hasn't authenticated yet)
			if strings.Contains(err.Error(), "pending") || strings.Contains(err.Error(), "authorization_pending") {
				continue
			}
			return err
		}

		return storeAndPrintCredentials(token)
	}

	return fmt.Errorf("device code expired")
}

// callbackLogin starts a local server and waits for callback (similar to browser but no browser open).
func callbackLogin(cfg *config.Config) error {
	// Start local server to receive callback
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("failed to start callback server: %w", err)
	}
	defer listener.Close()

	port := listener.Addr().(*net.TCPAddr).Port
	callbackURL := fmt.Sprintf("http://127.0.0.1:%d/callback", port)

	// Build login URL with callback
	loginURL, err := url.Parse(cfg.Auth.LoginURL)
	if err != nil {
		return fmt.Errorf("invalid login URL: %w", err)
	}
	q := loginURL.Query()
	q.Set("callback", callbackURL)
	loginURL.RawQuery = q.Encode()

	fmt.Printf("\nAuthentication required.\n")
	fmt.Printf("Visit this URL to authenticate:\n  %s\n\n", loginURL.String())
	fmt.Printf("Waiting for callback on port %d...\n", port)

	// Wait for callback with channels
	tokenChan := make(chan string)
	errChan := make(chan error)

	server := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/callback" {
				http.NotFound(w, r)
				return
			}

			token := r.URL.Query().Get("token")
			if token == "" {
				errMsg := r.URL.Query().Get("error")
				if errMsg == "" {
					errMsg = "no token received"
				}
				w.Header().Set("Content-Type", "text/plain")
				w.Write([]byte("Authentication failed. You may close this window."))
				errChan <- fmt.Errorf("authentication failed: %s", errMsg)
				return
			}

			w.Header().Set("Content-Type", "text/plain")
			w.Write([]byte("Authentication successful! You may close this window."))
			tokenChan <- token
		}),
	}

	go server.Serve(listener)

	// Wait for result with timeout
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()

	var result error
	var token string

	select {
	case token = <-tokenChan:
		result = nil
	case result = <-errChan:
	case <-ctx.Done():
		result = fmt.Errorf("authentication timed out after 10 minutes")
	}

	// Gracefully shutdown the server
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer shutdownCancel()
	_ = server.Shutdown(shutdownCtx)

	if result != nil {
		return result
	}
	return storeAndPrintCredentials(token)
}

// pollForToken polls the translation layer for a token using the device code.
func pollForToken(loginURL, deviceCode string) (string, error) {
	pollURL, err := url.Parse(loginURL)
	if err != nil {
		return "", fmt.Errorf("invalid login URL: %w", err)
	}
	q := pollURL.Query()
	q.Set("flow", "device_code")
	q.Set("device_code", deviceCode)
	pollURL.RawQuery = q.Encode()

	resp, err := http.Get(pollURL.String())
	if err != nil {
		return "", fmt.Errorf("poll request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusAccepted || resp.StatusCode == http.StatusTooEarly {
		return "", fmt.Errorf("authorization_pending")
	}

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("poll failed with status %d", resp.StatusCode)
	}

	var tokenResp struct {
		Token string `json:"token"`
		Error string `json:"error,omitempty"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&tokenResp); err != nil {
		return "", fmt.Errorf("failed to parse poll response: %w", err)
	}

	if tokenResp.Error != "" {
		return "", fmt.Errorf(tokenResp.Error)
	}

	if tokenResp.Token == "" {
		return "", fmt.Errorf("authorization_pending")
	}

	return tokenResp.Token, nil
}

// storeAndPrintCredentials parses the token, stores credentials, and prints status.
func storeAndPrintCredentials(token string) error {
	claims, err := auth.ParseTokenClaims(token)
	if err != nil {
		return fmt.Errorf("failed to parse token: %w", err)
	}

	if claims.IsExpired() {
		return fmt.Errorf("received token is already expired")
	}

	creds := claims.ToStoredCredentials(token)
	if err := auth.StoreCredentials(creds); err != nil {
		return fmt.Errorf("failed to store credentials: %w", err)
	}

	fmt.Printf("\nLogged in successfully!\n")
	fmt.Printf("  User:    %s\n", claims.Subject)
	if claims.Name != "" {
		fmt.Printf("  Name:    %s\n", claims.Name)
	}
	if len(claims.Groups) > 0 {
		fmt.Printf("  Groups:  %s\n", strings.Join(claims.Groups, ", "))
	}
	fmt.Printf("  Expires: %s\n", claims.ExpiryTime().Format(time.RFC3339))

	return nil
}

// openBrowser opens the default browser to the given URL.
func openBrowser(url string) error {
	var cmd *exec.Cmd

	switch runtime.GOOS {
	case "darwin":
		cmd = exec.Command("open", url)
	case "linux":
		cmd = exec.Command("xdg-open", url)
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	default:
		return fmt.Errorf("unsupported platform: %s", runtime.GOOS)
	}

	return cmd.Start()
}
