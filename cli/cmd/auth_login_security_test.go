package cmd

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

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

func mustURL(raw string) *url.URL {
	value, err := url.Parse(raw)
	if err != nil {
		panic(err)
	}
	return value
}
