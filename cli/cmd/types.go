package cmd

// ServiceRegistration represents the full service registration request
type ServiceRegistration struct {
	Version             int64                    `json:"version"`
	ServiceID           string                   `json:"serviceId"`
	DisplayName         string                   `json:"displayName,omitempty"`
	BaseURL             string                   `json:"baseUrl"`
	RoutePrefix         string                   `json:"routePrefix,omitempty"`
	DefaultVisibility   string                   `json:"defaultVisibility,omitempty"`
	DefaultAuthRequired *bool                    `json:"defaultAuthRequired,omitempty"`
	VisibilityRules     []VisibilityRule         `json:"visibilityRules,omitempty"`
	Endpoints           []EndpointConfig         `json:"endpoints,omitempty"`
	AccessConfig        *ServiceAccessConfig     `json:"accessConfig,omitempty"`
	Cors                *CorsConfig              `json:"cors,omitempty"`
	PermissionPolicy    *ServicePermissionPolicy `json:"permissionPolicy,omitempty"`
	RateLimitConfig     *RateLimitConfig         `json:"rateLimitConfig,omitempty"`
}

// VisibilityRule defines visibility for a path pattern
type VisibilityRule struct {
	Pattern    string   `json:"pattern"`
	Methods    []string `json:"methods,omitempty"`
	Visibility string   `json:"visibility,omitempty"`
}

// EndpointConfig defines an endpoint configuration
type EndpointConfig struct {
	Path            string                   `json:"path"`
	Methods         []string                 `json:"methods,omitempty"`
	Visibility      string                   `json:"visibility,omitempty"`
	PathRewrite     string                   `json:"pathRewrite,omitempty"`
	AuthRequired    *bool                    `json:"authRequired,omitempty"`
	Type            string                   `json:"type,omitempty"` // "HTTP" (default) or "WEBSOCKET"
	RateLimitConfig *EndpointRateLimitConfig `json:"rateLimitConfig,omitempty"`
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

// ServicePermissionPolicy defines permission policies for service operations
type ServicePermissionPolicy struct {
	Permissions map[string]OperationPermission `json:"permissions,omitempty"`
}

// OperationPermission defines permission rules for a single operation
type OperationPermission struct {
	AnyOfPermissions []string `json:"anyOfPermissions,omitempty"`
}

// PermissionPolicyResponse represents the response from the permissions API
type PermissionPolicyResponse struct {
	PermissionPolicy *ServicePermissionPolicy `json:"permissionPolicy"`
	Version          int64                    `json:"version"`
}

// RateLimitConfig defines rate limiting configuration for a service
type RateLimitConfig struct {
	RequestsPerWindow *int64                    `json:"requestsPerWindow,omitempty"`
	WindowSeconds     *int64                    `json:"windowSeconds,omitempty"`
	BurstCapacity     *int64                    `json:"burstCapacity,omitempty"`
	WebSocket         *WebSocketRateLimitConfig `json:"websocket,omitempty"`
}

// EndpointRateLimitConfig defines rate limiting configuration for a specific endpoint
type EndpointRateLimitConfig struct {
	RequestsPerWindow *int64 `json:"requestsPerWindow,omitempty"`
	WindowSeconds     *int64 `json:"windowSeconds,omitempty"`
	BurstCapacity     *int64 `json:"burstCapacity,omitempty"`
}

// WebSocketRateLimitConfig defines rate limiting for WebSocket connections and messages
type WebSocketRateLimitConfig struct {
	Connection *RateLimitValues `json:"connection,omitempty"`
	Message    *RateLimitValues `json:"message,omitempty"`
}

// RateLimitValues defines the rate limit values for a specific context
type RateLimitValues struct {
	RequestsPerWindow *int64 `json:"requestsPerWindow,omitempty"`
	WindowSeconds     *int64 `json:"windowSeconds,omitempty"`
	BurstCapacity     *int64 `json:"burstCapacity,omitempty"`
}
