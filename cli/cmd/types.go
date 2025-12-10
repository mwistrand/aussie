package cmd

// ServiceRegistration represents the full service registration request
type ServiceRegistration struct {
	ServiceID           string               `json:"serviceId"`
	DisplayName         string               `json:"displayName,omitempty"`
	BaseURL             string               `json:"baseUrl"`
	RoutePrefix         string               `json:"routePrefix,omitempty"`
	DefaultVisibility   string               `json:"defaultVisibility,omitempty"`
	DefaultAuthRequired *bool                `json:"defaultAuthRequired,omitempty"`
	VisibilityRules     []VisibilityRule     `json:"visibilityRules,omitempty"`
	Endpoints           []EndpointConfig     `json:"endpoints,omitempty"`
	AccessConfig        *ServiceAccessConfig `json:"accessConfig,omitempty"`
	Cors                *CorsConfig          `json:"cors,omitempty"`
}

// VisibilityRule defines visibility for a path pattern
type VisibilityRule struct {
	Pattern    string   `json:"pattern"`
	Methods    []string `json:"methods,omitempty"`
	Visibility string   `json:"visibility,omitempty"`
}

// EndpointConfig defines an endpoint configuration
type EndpointConfig struct {
	Path         string   `json:"path"`
	Methods      []string `json:"methods,omitempty"`
	Visibility   string   `json:"visibility,omitempty"`
	PathRewrite  string   `json:"pathRewrite,omitempty"`
	AuthRequired *bool    `json:"authRequired,omitempty"`
	Type         string   `json:"type,omitempty"` // "HTTP" (default) or "WEBSOCKET"
}

// ServiceAccessConfig defines access control settings
type ServiceAccessConfig struct {
	AllowedIPs        []string `json:"allowedIps,omitempty"`
	AllowedDomains    []string `json:"allowedDomains,omitempty"`
	AllowedSubdomains []string `json:"allowedSubdomains,omitempty"`
}

// CorsConfig defines CORS settings
type CorsConfig struct {
	AllowedOrigins   []string `json:"allowedOrigins,omitempty"`
	AllowedMethods   []string `json:"allowedMethods,omitempty"`
	AllowedHeaders   []string `json:"allowedHeaders,omitempty"`
	ExposedHeaders   []string `json:"exposedHeaders,omitempty"`
	AllowCredentials *bool    `json:"allowCredentials,omitempty"`
	MaxAge           *int64   `json:"maxAge,omitempty"`
}
