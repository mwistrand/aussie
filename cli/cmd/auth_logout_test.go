package cmd

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

func TestRunLogoutAllowsGlobalEndpointPath(t *testing.T) {
	request := make(chan *http.Request, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		request <- r
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	home := t.TempDir()
	project := filepath.Join(home, "project")
	if err := os.Mkdir(project, 0700); err != nil {
		t.Fatal(err)
	}
	originalDir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(project); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chdir(originalDir) })
	t.Setenv("HOME", home)

	cfg := config.Config{
		Host: server.URL,
		Auth: config.AuthConfig{LogoutURL: server.URL + "/auth/logout"},
	}
	if err := cfg.Save(); err != nil {
		t.Fatal(err)
	}
	origin, err := auth.CanonicalOrigin(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	if err := auth.StoreCredentials(auth.StoredCredentials{
		Token:        "trusted-token",
		ServerOrigin: origin,
		ExpiresAt:    time.Now().Add(time.Hour),
	}); err != nil {
		t.Fatal(err)
	}

	cmd := &cobra.Command{}
	cmd.Flags().Bool("server", true, "")
	if err := runLogout(cmd, nil); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-request:
		if got.URL.Path != "/auth/logout" || got.Header.Get("Authorization") != "Bearer trusted-token" {
			t.Fatalf("unexpected logout request: %s %q", got.URL.Path, got.Header.Get("Authorization"))
		}
	default:
		t.Fatal("server logout request was not sent")
	}
}
