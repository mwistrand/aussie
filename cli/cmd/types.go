package cmd

import "github.com/aussie/cli/internal/api"

type ServiceRegistration = api.ServiceRegistration
type VisibilityRule = api.VisibilityRule
type EndpointConfig = api.EndpointConfig
type ServiceAccessConfig = api.ServiceAccessConfig
type CorsConfig = api.CorsConfig
type ServicePermissionPolicy = api.ServicePermissionPolicy
type OperationPermission = api.OperationPermission
type PermissionPolicyResponse = api.PermissionPolicyResponse
type RateLimitConfig = api.RateLimitConfig
type EndpointRateLimitConfig = api.EndpointRateLimitConfig
type WebSocketRateLimitConfig = api.WebSocketRateLimitConfig
type RateLimitValues = api.RateLimitValues
type ServiceResponse = api.ServiceResponse
type Role = api.Role
type RoleCreateRequest = api.RoleCreateRequest
type RoleUpdateRequest = api.RoleUpdateRequest
type signingKey = api.SigningKey
type signingKeyDetail = api.SigningKeyDetail
type rotateRequest = api.RotateSigningKeyRequest
type rotateResponse = api.RotateSigningKeyResponse
type keyHealthStatus = api.SigningKeyHealthStatus
type APIKey = api.APIKey
type APIKeyCreateRequest = api.APIKeyCreateRequest
type APIKeyCreateResponse = api.APIKeyCreateResponse
type ErrorResponse = api.ErrorResponse
type TranslationConfigVersion = api.TranslationConfigVersion
type translationStatus = api.TranslationStatus
type cacheStatus = api.TranslationCacheStatus
type TranslationConfigValidation = api.TranslationConfigValidation
type TranslationTestResult = api.TranslationTestResult
type WhoAmIResponse = api.WhoAmIResponse
type ProblemResponse = api.ProblemResponse
