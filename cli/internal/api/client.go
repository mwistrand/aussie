package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/aussie/cli/internal/auth"
)

const maxResponseSize = 10 << 20

// Client is the one authenticated HTTP transport used by CLI API commands.
type Client struct {
	baseURL    *url.URL
	token      string
	httpClient *http.Client
}

// New creates a client bound to one canonical server origin.
func New(host, token string) (*Client, error) {
	origin, err := auth.CanonicalOrigin(host)
	if err != nil {
		return nil, err
	}
	baseURL, err := url.Parse(origin)
	if err != nil {
		return nil, fmt.Errorf("invalid server origin: %w", err)
	}
	return &Client{
		baseURL: baseURL,
		token:   token,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
			CheckRedirect: func(request *http.Request, via []*http.Request) error {
				if len(via) >= 10 {
					return fmt.Errorf("stopped after 10 redirects")
				}
				redirectOrigin, err := auth.CanonicalOrigin(request.URL.Scheme + "://" + request.URL.Host)
				if err != nil || request.URL.User != nil || redirectOrigin != origin {
					return fmt.Errorf("refusing redirect outside server origin")
				}
				return nil
			},
		},
	}, nil
}

// Response contains a fully consumed server response.
type Response struct {
	StatusCode int
	Status     string
	Header     http.Header
	body       []byte
}

// DoJSON sends one API request and closes the response body before returning.
func (c *Client) DoJSON(method, path string, body any) (*Response, error) {
	endpoint, err := c.endpoint(path)
	if err != nil {
		return nil, err
	}

	var requestBody io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal request: %w", err)
		}
		requestBody = bytes.NewReader(encoded)
	}
	request, err := http.NewRequest(method, endpoint.String(), requestBody)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}
	if c.token != "" {
		request.Header.Set("Authorization", "Bearer "+c.token)
	}
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}

	response, err := c.httpClient.Do(request)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to server: %w", err)
	}
	defer response.Body.Close()

	data, err := io.ReadAll(io.LimitReader(response.Body, maxResponseSize+1))
	if err != nil {
		return nil, fmt.Errorf("failed to read server response: %w", err)
	}
	if len(data) > maxResponseSize {
		return nil, fmt.Errorf("server response exceeds %d bytes", maxResponseSize)
	}
	return &Response{StatusCode: response.StatusCode, Status: response.Status, Header: response.Header, body: data}, nil
}

// DecodeJSON decodes the response body into a caller-owned typed value.
func (r *Response) DecodeJSON(value any) error {
	return json.Unmarshal(r.body, value)
}

// Detail returns the RFC 7807 detail when the response supplies one.
func (r *Response) Detail() string {
	var problem struct {
		Detail string `json:"detail"`
	}
	if err := r.DecodeJSON(&problem); err != nil {
		return ""
	}
	return problem.Detail
}

func (c *Client) endpoint(path string) (*url.URL, error) {
	if path == "" || !strings.HasPrefix(path, "/") {
		return nil, fmt.Errorf("API path must be absolute and relative to the server origin")
	}
	relative, err := url.Parse(path)
	if err != nil || relative.IsAbs() || relative.Host != "" || relative.Fragment != "" {
		return nil, fmt.Errorf("invalid API path")
	}
	endpoint := *c.baseURL
	endpoint.Path = strings.TrimRight(endpoint.Path, "/") + relative.Path
	endpoint.RawPath = ""
	endpoint.RawQuery = relative.RawQuery
	return &endpoint, nil
}
