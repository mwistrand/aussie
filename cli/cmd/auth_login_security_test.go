package cmd

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/aussie/cli/internal/config"
)

func TestParseOAuthCallbackRejectsUnsafeCallbacks(t *testing.T) {
	for _, request := range []*http.Request{
		{Method: http.MethodGet, Host: "127.0.0.1:1234", URL: mustURL("http://127.0.0.1:1234/callback?token=secret&state=ok")},
		{Method: http.MethodGet, Host: "localhost:1234", URL: mustURL("http://localhost:1234/callback?code=code&state=ok")},
		{Method: http.MethodGet, Host: "127.0.0.1:1234", URL: mustURL("http://127.0.0.1:1234/callback?code=code&state=wrong")},
	} {
		if _, accepted := parseOAuthCallback(request, "127.0.0.1:1234", "ok"); accepted {
			t.Fatal("unsafe callback was accepted")
		}
	}
}

func TestParseOAuthCallbackAcceptsBoundCode(t *testing.T) {
	request := &http.Request{Method: http.MethodGet, Host: "127.0.0.1:1234", URL: mustURL("http://127.0.0.1:1234/callback?code=code&state=ok")}
	result, accepted := parseOAuthCallback(request, "127.0.0.1:1234", "ok")
	if !accepted || result.code != "code" || result.err != "" {
		t.Fatal("valid authorization callback was rejected")
	}
}

func TestExchangeOAuthCodeSendsPKCETransaction(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
		}
		if err := r.ParseForm(); err != nil {
			t.Error(err)
		}
		for key, want := range map[string]string{
			"grant_type":    "authorization_code",
			"code":          "code",
			"code_verifier": "verifier",
			"state":         "state",
			"redirect_uri":  "http://127.0.0.1/callback",
		} {
			if got := r.Form.Get(key); got != want {
				t.Errorf("%s = %q, want %q", key, got, want)
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"access_token":"token"}`)
	}))
	defer server.Close()

	cfg := &config.Config{Auth: config.AuthConfig{TokenURL: server.URL}}
	token, err := exchangeOAuthCode(cfg, "code", "verifier", "state", "http://127.0.0.1/callback")
	if err != nil {
		t.Fatal(err)
	}
	if token != "token" {
		t.Fatalf("token = %q, want token", token)
	}
}

func TestPollForTokenUsesDeviceAuthorizationGrant(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
		}
		if err := r.ParseForm(); err != nil {
			t.Error(err)
		}
		for key, want := range map[string]string{
			"grant_type":  deviceGrantType,
			"device_code": "device-code",
			"client_id":   deviceClientID,
		} {
			if got := r.Form.Get(key); got != want {
				t.Errorf("%s = %q, want %q", key, got, want)
			}
		}
		w.WriteHeader(http.StatusBadRequest)
		_, _ = io.WriteString(w, `{"error":"authorization_pending"}`)
	}))
	defer server.Close()

	_, err := pollForToken(server.URL, "device-code")
	var grantErr *deviceGrantError
	if !errors.As(err, &grantErr) || grantErr.code != "authorization_pending" {
		t.Fatalf("error = %v, want typed authorization_pending", err)
	}
}

func TestPollForTokenDoesNotInferPendingFromErrorText(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = io.WriteString(w, `{"error":"invalid_grant","error_description":"still pending"}`)
	}))
	defer server.Close()

	_, err := pollForToken(server.URL, "device-code")
	var grantErr *deviceGrantError
	if !errors.As(err, &grantErr) || grantErr.code != "invalid_grant" {
		t.Fatalf("error = %v, want typed invalid_grant", err)
	}
}

func TestPollForTokenAcceptsStandardAccessToken(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"access_token":"token","token_type":"Bearer"}`)
	}))
	defer server.Close()

	token, err := pollForToken(server.URL, "device-code")
	if err != nil {
		t.Fatal(err)
	}
	if token != "token" {
		t.Fatalf("token = %q, want token", token)
	}
}

func TestNextDevicePollIntervalHandlesRetryableErrors(t *testing.T) {
	for name, test := range map[string]struct {
		err       error
		want      time.Duration
		wantRetry bool
	}{
		"pending":   {err: &deviceGrantError{code: devicePendingError}, want: 5 * time.Second, wantRetry: true},
		"slow down": {err: &deviceGrantError{code: "slow_down"}, want: 10 * time.Second, wantRetry: true},
		"timeout":   {err: fmt.Errorf("poll: %w", context.DeadlineExceeded), want: 10 * time.Second, wantRetry: true},
		"terminal":  {err: &deviceGrantError{code: "access_denied"}, want: 5 * time.Second},
	} {
		t.Run(name, func(t *testing.T) {
			got, retry := nextDevicePollInterval(5*time.Second, test.err)
			if got != test.want || retry != test.wantRetry {
				t.Fatalf("nextDevicePollInterval() = (%s, %t), want (%s, %t)", got, retry, test.want, test.wantRetry)
			}
		})
	}
}

func mustURL(raw string) *url.URL {
	value, err := url.Parse(raw)
	if err != nil {
		panic(err)
	}
	return value
}
