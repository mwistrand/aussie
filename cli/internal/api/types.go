package api

// ServiceRegistration represents the full service registration request.
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

// VisibilityRule defines visibility for a path pattern.
type VisibilityRule struct {
	Pattern    string   `json:"pattern"`
	Methods    []string `json:"methods,omitempty"`
	Visibility string   `json:"visibility,omitempty"`
}

// EndpointConfig defines an endpoint configuration.
type EndpointConfig struct {
	Path            string                   `json:"path"`
	Methods         []string                 `json:"methods,omitempty"`
	Visibility      string                   `json:"visibility,omitempty"`
	PathRewrite     string                   `json:"pathRewrite,omitempty"`
	AuthRequired    *bool                    `json:"authRequired,omitempty"`
	Type            string                   `json:"type,omitempty"`
	RateLimitConfig *EndpointRateLimitConfig `json:"rateLimitConfig,omitempty"`
}

// ServiceAccessConfig defines access control settings.
type ServiceAccessConfig struct {
	AllowedIPs        []string `json:"allowedIps,omitempty"`
	AllowedDomains    []string `json:"allowedDomains,omitempty"`
	AllowedSubdomains []string `json:"allowedSubdomains,omitempty"`
}

// CorsConfig defines CORS settings.
type CorsConfig struct {
	AllowedOrigins   []string `json:"allowedOrigins,omitempty"`
	AllowedMethods   []string `json:"allowedMethods,omitempty"`
	AllowedHeaders   []string `json:"allowedHeaders,omitempty"`
	ExposedHeaders   []string `json:"exposedHeaders,omitempty"`
	AllowCredentials *bool    `json:"allowCredentials,omitempty"`
	MaxAge           *int64   `json:"maxAge,omitempty"`
}

// ServicePermissionPolicy defines permission policies for service operations.
type ServicePermissionPolicy struct {
	Permissions map[string]OperationPermission `json:"permissions,omitempty"`
}

// OperationPermission defines permission rules for a single operation.
type OperationPermission struct {
	AnyOfPermissions []string `json:"anyOfPermissions,omitempty"`
}

// PermissionPolicyResponse represents the response from the permissions API.
type PermissionPolicyResponse struct {
	PermissionPolicy *ServicePermissionPolicy `json:"permissionPolicy"`
	Version          int64                    `json:"version"`
}

// RateLimitConfig defines rate limiting configuration for a service.
type RateLimitConfig struct {
	RequestsPerWindow *int64                    `json:"requestsPerWindow,omitempty"`
	WindowSeconds     *int64                    `json:"windowSeconds,omitempty"`
	BurstCapacity     *int64                    `json:"burstCapacity,omitempty"`
	WebSocket         *WebSocketRateLimitConfig `json:"websocket,omitempty"`
}

// EndpointRateLimitConfig defines rate limiting configuration for an endpoint.
type EndpointRateLimitConfig struct {
	RequestsPerWindow *int64 `json:"requestsPerWindow,omitempty"`
	WindowSeconds     *int64 `json:"windowSeconds,omitempty"`
	BurstCapacity     *int64 `json:"burstCapacity,omitempty"`
}

// WebSocketRateLimitConfig defines rate limiting for WebSocket connections and messages.
type WebSocketRateLimitConfig struct {
	Connection *RateLimitValues `json:"connection,omitempty"`
	Message    *RateLimitValues `json:"message,omitempty"`
}

// RateLimitValues defines rate limit values for one context.
type RateLimitValues struct {
	RequestsPerWindow *int64 `json:"requestsPerWindow,omitempty"`
	WindowSeconds     *int64 `json:"windowSeconds,omitempty"`
	BurstCapacity     *int64 `json:"burstCapacity,omitempty"`
}

// ServiceResponse represents the API response for a service.
type ServiceResponse struct {
	ServiceID         string               `json:"serviceId"`
	DisplayName       string               `json:"displayName"`
	BaseURL           string               `json:"baseUrl"`
	RoutePrefix       string               `json:"routePrefix"`
	DefaultVisibility string               `json:"defaultVisibility"`
	VisibilityRules   []VisibilityRule     `json:"visibilityRules"`
	Endpoints         []EndpointConfig     `json:"endpoints"`
	AccessConfig      *ServiceAccessConfig `json:"accessConfig"`
}

// Role is the wire representation returned by the role endpoints.
type Role struct {
	ID          string   `json:"id"`
	DisplayName string   `json:"displayName"`
	Description string   `json:"description"`
	Permissions []string `json:"permissions"`
	TeamID      string   `json:"teamId,omitempty"`
	CreatedAt   string   `json:"createdAt,omitempty"`
	UpdatedAt   string   `json:"updatedAt,omitempty"`
	Version     int64    `json:"version,omitempty"`
}

// RoleCreateRequest is the request body for creating a role.
type RoleCreateRequest struct {
	ID          string   `json:"id"`
	DisplayName string   `json:"displayName,omitempty"`
	Description string   `json:"description,omitempty"`
	Permissions []string `json:"permissions,omitempty"`
}

// RoleUpdateRequest is the request body for updating a role.
type RoleUpdateRequest struct {
	DisplayName       *string   `json:"displayName,omitempty"`
	Description       *string   `json:"description,omitempty"`
	Permissions       *[]string `json:"permissions,omitempty"`
	AddPermissions    []string  `json:"addPermissions,omitempty"`
	RemovePermissions []string  `json:"removePermissions,omitempty"`
}

// SigningKey is the summary returned by the signing-key list endpoint.
type SigningKey struct {
	KeyId        string `json:"keyId"`
	Status       string `json:"status"`
	CreatedAt    string `json:"createdAt"`
	ActivatedAt  string `json:"activatedAt,omitempty"`
	DeprecatedAt string `json:"deprecatedAt,omitempty"`
	RetiredAt    string `json:"retiredAt,omitempty"`
}

// SigningKeyDetail is the detailed signing-key response.
type SigningKeyDetail struct {
	SigningKey
	CanSign   bool           `json:"canSign"`
	CanVerify bool           `json:"canVerify"`
	PublicKey map[string]any `json:"publicKey,omitempty"`
}

// RotateSigningKeyRequest is the request body for signing-key rotation.
type RotateSigningKeyRequest struct {
	Reason string `json:"reason"`
}

// RotateSigningKeyResponse is the response from signing-key rotation.
type RotateSigningKeyResponse struct {
	KeyId       string `json:"keyId"`
	Status      string `json:"status"`
	CreatedAt   string `json:"createdAt"`
	ActivatedAt string `json:"activatedAt"`
	PreviousKey string `json:"previousKeyId,omitempty"`
}

// SigningKeyHealthStatus is the signing-key health response.
type SigningKeyHealthStatus struct {
	Enabled              bool   `json:"enabled"`
	Status               string `json:"status"`
	ActiveKeyId          string `json:"activeKeyId,omitempty"`
	VerificationKeyCount int    `json:"verificationKeyCount"`
	LastCacheRefresh     string `json:"lastCacheRefresh,omitempty"`
}

// APIKey is the wire representation returned by API-key list endpoints.
type APIKey struct {
	Id          string   `json:"id"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Permissions []string `json:"permissions"`
	CreatedBy   string   `json:"createdBy"`
	CreatedAt   string   `json:"createdAt"`
	ExpiresAt   string   `json:"expiresAt,omitempty"`
	Revoked     bool     `json:"revoked"`
}

// APIKeyCreateRequest is the request body for creating an API key.
type APIKeyCreateRequest struct {
	Name        string   `json:"name"`
	Description string   `json:"description,omitempty"`
	TtlDays     *int     `json:"ttlDays,omitempty"`
	Permissions []string `json:"permissions"`
}

// APIKeyCreateResponse is the response from creating an API key.
type APIKeyCreateResponse struct {
	KeyId       string   `json:"keyId"`
	Key         string   `json:"key"`
	Name        string   `json:"name"`
	Permissions []string `json:"permissions"`
	CreatedBy   string   `json:"createdBy"`
	ExpiresAt   string   `json:"expiresAt,omitempty"`
}

// TranslationConfigVersion is a translation-config version response.
type TranslationConfigVersion struct {
	ID        string `json:"id"`
	Version   int    `json:"version"`
	Active    bool   `json:"active"`
	CreatedBy string `json:"createdBy,omitempty"`
	CreatedAt string `json:"createdAt,omitempty"`
	Comment   string `json:"comment,omitempty"`
}

// TranslationStatus is the translation service status response.
type TranslationStatus struct {
	Enabled         bool                   `json:"enabled"`
	ActiveProvider  string                 `json:"activeProvider"`
	ProviderHealthy bool                   `json:"providerHealthy"`
	Cache           TranslationCacheStatus `json:"cache"`
}

// TranslationCacheStatus is translation cache status.
type TranslationCacheStatus struct {
	CurrentSize int64 `json:"currentSize"`
	MaxSize     int64 `json:"maxSize"`
	TtlSeconds  int   `json:"ttlSeconds"`
}

// TranslationConfigValidation is a translation-config validation response.
type TranslationConfigValidation struct {
	Valid  bool     `json:"valid"`
	Errors []string `json:"errors"`
}

// TranslationTestResult is the response from testing translation.
type TranslationTestResult struct {
	Roles       []string `json:"roles"`
	Permissions []string `json:"permissions"`
}

// WhoAmIResponse is the authenticated principal returned by the control plane.
type WhoAmIResponse struct {
	KeyId                string   `json:"keyId,omitempty"`
	Subject              string   `json:"sub,omitempty"`
	Name                 string   `json:"name,omitempty"`
	Permissions          []string `json:"permissions,omitempty"`
	Groups               []string `json:"groups,omitempty"`
	EffectivePermissions []string `json:"effectivePermissions,omitempty"`
	ExpiresAt            string   `json:"expiresAt,omitempty"`
}

// ProblemResponse is the RFC-style error detail used by control-plane endpoints.
type ProblemResponse struct {
	Detail string `json:"detail,omitempty"`
}

// ErrorResponse is the legacy validation error returned by API-key creation.
type ErrorResponse struct {
	Error string `json:"error,omitempty"`
}
