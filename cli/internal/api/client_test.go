package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestClientSendsBoundOriginAndBearerToken(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/admin/roles" || r.URL.RawQuery != "limit=2" {
			t.Errorf("request URL = %s, want /admin/roles?limit=2", r.URL.RequestURI())
		}
		if got := r.Header.Get("Authorization"); got != "Bearer token" {
			t.Errorf("Authorization = %q, want bearer token", got)
		}
		if got := r.Header.Values("Authorization"); len(got) != 1 {
			t.Errorf("Authorization values = %q, want only the bearer token", got)
		}
		if got := r.Header.Get("If-Match"); got != "2" {
			t.Errorf("If-Match = %q, want 2", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"detail":"ok"}`))
	}))
	defer server.Close()

	client, err := New(server.URL, "token")
	if err != nil {
		t.Fatal(err)
	}
	response, err := client.DoJSONWithHeaders(http.MethodGet, "/admin/roles?limit=2", nil, http.Header{
		"authorization": {"Bearer caller-supplied"},
		"If-Match":      {"2"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || response.Detail() != "ok" {
		t.Fatalf("response = %#v, detail = %q", response, response.Detail())
	}
}

func TestClientRejectsInvalidPaths(t *testing.T) {
	client, err := New("http://localhost", "")
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"https://evil.example", "roles", "/roles#fragment"} {
		if _, err := client.DoJSON(http.MethodGet, path, nil); err == nil {
			t.Fatalf("DoJSON(%q) accepted invalid path", path)
		}
	}
}

func TestClientRejectsCrossOriginRedirect(t *testing.T) {
	targetHit := make(chan string, 1)
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		targetHit <- r.Header.Get("Authorization")
	}))
	defer target.Close()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL+"/admin/roles", http.StatusFound)
	}))
	defer server.Close()

	client, err := New(server.URL, "token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.DoJSON(http.MethodGet, "/admin/roles", nil); err == nil {
		t.Error("DoJSON followed a cross-origin redirect")
	}
	select {
	case authorization := <-targetHit:
		t.Errorf("cross-origin target received Authorization %q", authorization)
	default:
	}
}
