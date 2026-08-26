package cmd

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
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

const (
	deviceClientID        = "aussie-cli"
	deviceGrantType       = "urn:ietf:params:oauth:grant-type:device_code"
	devicePendingError    = "authorization_pending"
	defaultPollInterval   = 5 * time.Second
	maxPollInterval       = 5 * time.Minute
	slowDownIncrement     = 5 * time.Second
	maxDeviceCodeLifetime = 24 * time.Hour
)

type deviceGrantError struct {
	code string
}

func (e *deviceGrantError) Error() string { return e.code }

type deviceCodeResponse struct {
	DeviceCode              string `json:"device_code"`
	UserCode                string `json:"user_code"`
	VerificationURI         string `json:"verification_uri"`
	VerificationURL         string `json:"verification_url"`
	VerificationURIComplete string `json:"verification_uri_complete"`
	ExpiresIn               int    `json:"expires_in"`
	Interval                int    `json:"interval"`
}

type deviceTokenResponse struct {
	Token       string `json:"token"`
	AccessToken string `json:"access_token"`
	Error       string `json:"error"`
}

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
  authorization endpoint. Set auth.token_url when code exchange uses a
  separate endpoint; otherwise it defaults to auth.login_url.

Examples:
  aussie login                     # Uses mode from config (default: browser)
  aussie login --mode device_code  # Override config for this invocation`,
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

// browserLogin opens the browser and completes an authorization-code callback.
func browserLogin(cfg *config.Config) error {
	return oauthCallbackLogin(cfg, true)
}

// deviceCodeLogin uses device code flow for headless environments.
func deviceCodeLogin(cfg *config.Config) error {
	deviceURL, err := url.Parse(cfg.Auth.LoginURL)
	if err != nil {
		return fmt.Errorf("invalid login URL: %w", err)
	}
	if err := validateOAuthURL(deviceURL); err != nil {
		return err
	}
	q := deviceURL.Query()
	q.Set("flow", "device_code")
	deviceURL.RawQuery = q.Encode()

	client := &http.Client{
		Timeout: 30 * time.Second,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	request, err := http.NewRequest(http.MethodPost, deviceURL.String(), strings.NewReader(url.Values{
		"client_id": {deviceClientID},
	}.Encode()))
	if err != nil {
		return fmt.Errorf("failed to create device code request: %w", err)
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("failed to initiate device code flow: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return oauthDeviceError(resp, "device code request failed")
	}

	var deviceResp deviceCodeResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&deviceResp); err != nil {
		return fmt.Errorf("failed to parse device code response: %w", err)
	}
	_ = resp.Body.Close()
	if deviceResp.DeviceCode == "" || deviceResp.UserCode == "" || deviceResp.ExpiresIn <= 0 {
		return errors.New("device code response was incomplete")
	}
	if deviceResp.ExpiresIn > int(maxDeviceCodeLifetime/time.Second) {
		return errors.New("device code lifetime is too long")
	}
	verificationURI := deviceResp.VerificationURI
	if verificationURI == "" {
		verificationURI = deviceResp.VerificationURL
	}
	if verificationURI == "" {
		return errors.New("device code response did not contain a verification URI")
	}

	fmt.Printf("\nTo authenticate, visit:\n  %s\n\n", verificationURI)
	if deviceResp.VerificationURIComplete != "" {
		fmt.Printf("Or visit:\n  %s\n\n", deviceResp.VerificationURIComplete)
	}
	fmt.Printf("And enter code: %s\n\n", deviceResp.UserCode)
	fmt.Printf("Waiting for authentication...\n")

	interval := defaultPollInterval
	if deviceResp.Interval > 0 {
		if deviceResp.Interval > int(maxPollInterval/time.Second) {
			return errors.New("device poll interval is too long")
		}
		interval = time.Duration(deviceResp.Interval) * time.Second
	}

	deadline := time.Now().Add(time.Duration(deviceResp.ExpiresIn) * time.Second)
	pollURL := cfg.Auth.TokenURL
	if pollURL == "" {
		pollURL = cfg.Auth.LoginURL
	}
	ctx, cancel := context.WithDeadline(context.Background(), deadline)
	defer cancel()

	for {
		if err := waitForDevicePoll(ctx, interval); err != nil {
			return errors.New("device code expired")
		}

		token, err := pollForTokenContext(ctx, client, pollURL, deviceResp.DeviceCode)
		if err != nil {
			if ctx.Err() != nil {
				return errors.New("device code expired")
			}
			if nextInterval, retry := nextDevicePollInterval(interval, err); retry {
				interval = nextInterval
				continue
			}
			return err
		}

		return storeAndPrintCredentials(token, cfg.Host)
	}
}

func nextDevicePollInterval(interval time.Duration, err error) (time.Duration, bool) {
	var grantErr *deviceGrantError
	if errors.As(err, &grantErr) {
		switch grantErr.code {
		case devicePendingError:
			return interval, true
		case "slow_down":
			return min(interval+slowDownIncrement, maxPollInterval), true
		}
	}

	var networkErr net.Error
	if errors.As(err, &networkErr) && networkErr.Timeout() {
		return min(interval*2, maxPollInterval), true
	}
	return interval, false
}

func waitForDevicePoll(ctx context.Context, interval time.Duration) error {
	timer := time.NewTimer(interval)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// callbackLogin starts a local server and waits for callback (similar to browser but no browser open).
func callbackLogin(cfg *config.Config) error {
	return oauthCallbackLogin(cfg, false)
}

type callbackResult struct {
	code string
	err  string
}

func oauthCallbackLogin(cfg *config.Config, open bool) error {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("failed to start callback server: %w", err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	callbackURL := fmt.Sprintf("http://127.0.0.1:%d/callback", port)

	loginURL, err := url.Parse(cfg.Auth.LoginURL)
	if err != nil {
		return fmt.Errorf("invalid login URL: %w", err)
	}
	if err := validateOAuthURL(loginURL); err != nil {
		return err
	}
	state, err := randomOAuthValue()
	if err != nil {
		return fmt.Errorf("failed to create OAuth state: %w", err)
	}
	verifier, err := randomOAuthValue()
	if err != nil {
		return fmt.Errorf("failed to create PKCE verifier: %w", err)
	}
	hash := sha256.Sum256([]byte(verifier))
	query := loginURL.Query()
	query.Set("callback", callbackURL)
	query.Set("redirect_uri", callbackURL)
	query.Set("response_type", "code")
	query.Set("state", state)
	query.Set("code_challenge", base64.RawURLEncoding.EncodeToString(hash[:]))
	query.Set("code_challenge_method", "S256")
	loginURL.RawQuery = query.Encode()

	fmt.Printf("\nAuthentication required.\nVisit this URL to authenticate:\n  %s\n\n", loginURL)
	if open {
		if err := openBrowser(loginURL.String()); err != nil {
			fmt.Printf("Could not open browser: %v\n", err)
		}
	}
	fmt.Printf("Waiting for callback on port %d...\n", port)

	resultChan := make(chan callbackResult, 1)
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		result, accepted := parseOAuthCallback(r, listener.Addr().String(), state)
		if !accepted {
			http.NotFound(w, r)
			return
		}
		if result.err != "" {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = io.WriteString(w, "Authentication failed. You may close this window.")
		} else {
			_, _ = io.WriteString(w, "Authentication successful. You may close this window.")
		}
		select {
		case resultChan <- result:
		default:
		}
	})}
	go server.Serve(listener)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	var result callbackResult
	select {
	case result = <-resultChan:
	case <-ctx.Done():
		return fmt.Errorf("authentication timed out after 10 minutes")
	}
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer shutdownCancel()
	_ = server.Shutdown(shutdownCtx)
	if result.err != "" {
		return errors.New(result.err)
	}
	token, err := exchangeOAuthCode(cfg, result.code, verifier, state, callbackURL)
	if err != nil {
		return err
	}
	return storeAndPrintCredentials(token, cfg.Host)
}

func parseOAuthCallback(r *http.Request, expectedHost, expectedState string) (callbackResult, bool) {
	if r.Method != http.MethodGet || r.Host != expectedHost || r.URL.Path != "/callback" {
		return callbackResult{}, false
	}
	query := r.URL.Query()
	if query.Get("token") != "" || query.Get("access_token") != "" || query.Get("id_token") != "" {
		return callbackResult{}, false
	}
	result := callbackResult{code: query.Get("code"), err: query.Get("error")}
	if result.code == "" && result.err == "" {
		result.err = "authorization callback was incomplete"
	}
	if query.Get("state") != expectedState {
		return callbackResult{}, false
	}
	return result, true
}

func randomOAuthValue() (string, error) {
	value := make([]byte, 32)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}

func validateOAuthURL(value *url.URL) error {
	if value.User != nil || value.Hostname() == "" {
		return fmt.Errorf("OAuth endpoints must not contain credentials")
	}
	host := strings.ToLower(value.Hostname())
	if !strings.EqualFold(value.Scheme, "https") && !(strings.EqualFold(value.Scheme, "http") && (host == "localhost" || host == "127.0.0.1" || host == "::1")) {
		return fmt.Errorf("OAuth endpoints require HTTPS (HTTP is allowed only for localhost)")
	}
	return nil
}

func exchangeOAuthCode(cfg *config.Config, code, verifier, state, redirectURI string) (string, error) {
	tokenURL := cfg.Auth.TokenURL
	if tokenURL == "" {
		tokenURL = cfg.Auth.LoginURL
	}
	parsed, err := url.Parse(tokenURL)
	if err != nil {
		return "", fmt.Errorf("invalid OAuth token URL: %w", err)
	}
	if err := validateOAuthURL(parsed); err != nil {
		return "", err
	}
	form := url.Values{"grant_type": {"authorization_code"}, "code": {code}, "code_verifier": {verifier}, "state": {state}, "redirect_uri": {redirectURI}}
	req, err := http.NewRequest(http.MethodPost, parsed.String(), strings.NewReader(form.Encode()))
	if err != nil {
		return "", fmt.Errorf("failed to create OAuth token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	client := &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("OAuth token exchange failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("OAuth token exchange failed with status %d", resp.StatusCode)
	}
	var tokenResponse struct {
		Token       string `json:"token"`
		AccessToken string `json:"access_token"`
		Error       string `json:"error"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&tokenResponse); err != nil {
		return "", fmt.Errorf("invalid OAuth token response: %w", err)
	}
	if tokenResponse.Error != "" {
		return "", errors.New("OAuth token exchange was rejected")
	}
	if tokenResponse.Token != "" {
		return tokenResponse.Token, nil
	}
	if tokenResponse.AccessToken != "" {
		return tokenResponse.AccessToken, nil
	}
	return "", errors.New("OAuth token response did not contain a token")
}

// pollForToken polls the token endpoint using the OAuth device authorization grant.
func pollForToken(tokenURL, deviceCode string) (string, error) {
	client := &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
		return http.ErrUseLastResponse
	}}
	return pollForTokenContext(context.Background(), client, tokenURL, deviceCode)
}

func pollForTokenContext(ctx context.Context, client *http.Client, tokenURL, deviceCode string) (string, error) {
	pollURL, err := url.Parse(tokenURL)
	if err != nil {
		return "", fmt.Errorf("invalid token URL: %w", err)
	}
	if err := validateOAuthURL(pollURL); err != nil {
		return "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, pollURL.String(), strings.NewReader(url.Values{
		"grant_type":  {deviceGrantType},
		"device_code": {deviceCode},
		"client_id":   {deviceClientID},
	}.Encode()))
	if err != nil {
		return "", fmt.Errorf("failed to create poll request: %w", err)
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := client.Do(request)
	if err != nil {
		return "", fmt.Errorf("poll request failed: %w", err)
	}
	defer resp.Body.Close()

	var tokenResp deviceTokenResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&tokenResp); err != nil {
		return "", fmt.Errorf("failed to parse poll response: %w", err)
	}
	if tokenResp.Error != "" {
		return "", &deviceGrantError{code: tokenResp.Error}
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("poll failed with status %d", resp.StatusCode)
	}
	token := tokenResp.AccessToken
	if token == "" {
		token = tokenResp.Token
	}
	if token == "" {
		return "", errors.New("token response did not contain a token")
	}
	return token, nil
}

func oauthDeviceError(resp *http.Response, fallback string) error {
	var body struct {
		Error string `json:"error"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&body); err == nil && body.Error != "" {
		return &deviceGrantError{code: body.Error}
	}
	return fmt.Errorf("%s with status %d", fallback, resp.StatusCode)
}

// storeAndPrintCredentials parses the token, stores credentials, and prints status.
func storeAndPrintCredentials(token, host string) error {
	claims, err := auth.ParseTokenClaims(token)
	if err != nil {
		return fmt.Errorf("failed to parse token: %w", err)
	}

	if claims.IsExpired() {
		return fmt.Errorf("received token is already expired")
	}

	creds := claims.ToStoredCredentials(token)
	creds.ServerOrigin, err = auth.CanonicalOrigin(host)
	if err != nil {
		return fmt.Errorf("invalid configured server: %w", err)
	}
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
