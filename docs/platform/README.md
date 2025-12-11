# Aussie Platform Guide

This guide is for platform teams deploying and operating the Aussie API Gateway.

## Table of Contents
- [Setup](#setup)
- [Authentication Configuration](#authentication-configuration)
- [Bootstrap Mode](#bootstrap-mode-first-time-setup)
- [Access Control](#access-control)
- [Request Forwarding](#request-forwarding)
- [Request Size Limits](#request-size-limits)
- [Per-Route Authentication](#per-route-authentication)
- [WebSocket Configuration](websocket-configuration.md)
- [Admin API](#admin-api)

## Setup

### Running in dev mode
```shell
make up
```
> Note: Quarkus is available at http://localhost:1234/

### Building
```shell
cd api
./gradlew build
```

### Running tests
```shell
cd api
./gradlew test
```

### Native executable
```shell
cd api
./gradlew build -Dquarkus.native.enabled=true
```

## Authentication Configuration

Aussie requires authentication for all admin endpoints (`/admin/*`). Gateway and pass-through routes remain open for public traffic.

### Configuration
Authentication is enabled by default. Configure it with environment variables:
```bash
# Enable/disable authentication (default: true)
export AUSSIE_AUTH_ENABLED=true

# Only require auth for admin paths (default: true)
export AUSSIE_AUTH_ADMIN_PATHS_ONLY=true

# DANGEROUS: Disable authentication entirely (NEVER use in production!)
# This is only for local development
export AUSSIE_AUTH_DANGEROUS_NOOP=false
```

### Creating API Keys
Create an API key using the CLI:
```bash
# Create a key with full permissions
./aussie keys create --name my-team-key --description "API key for My Team" --ttl 90

# Create a key with specific permissions
./aussie keys create --name read-only-key --permissions admin:read --ttl 30
```
**Output:**
```
API key created successfully!

Key ID:      ak_abc123
Name:        my-team-key
Permissions: *
Expires:     2025-06-06
Created By:  bootstrap

API Key (save this - it won't be shown again):
  aussie_xxxxxxxxxxxxxxxxxxxx
```
> Important: Save the API key immediately. It is only shown once and cannot be retrieved later.

### Managing API Keys
```bash
# List all API keys
./aussie keys list

# Revoke a key
./aussie keys revoke ak_abc123
```

### Permissions
| Permission | Description |
|------------|-------------|
| `admin:read` | Read access to admin endpoints (GET, HEAD, OPTIONS) |
| `admin:write` | Write access to admin endpoints (POST, PUT, DELETE) |
| `*` | Full access to all operations |

## Bootstrap Mode (First-Time Setup)

When deploying Aussie for the first time, you need an admin API key to access the admin endpoints, but you can't create one without authentication - a classic chicken-and-egg problem. Bootstrap mode solves this.

### Enabling Bootstrap
Set the following environment variables before starting Aussie:
```bash
# Enable bootstrap mode
export AUSSIE_BOOTSTRAP_ENABLED=true

# Provide a secure bootstrap key (minimum 32 characters)
export AUSSIE_BOOTSTRAP_KEY="your-secure-bootstrap-key-at-least-32-chars"

# Optional: Set TTL (default: 24 hours, maximum: 24 hours)
export AUSSIE_BOOTSTRAP_TTL=PT24H

# Start the gateway
make up
```
On startup, Aussie will:
1. Check if bootstrap mode is enabled
2. Verify no admin keys already exist
3. Create a time-limited admin key using your provided bootstrap key
4. Log the key ID and expiration (never the key itself)

### Using the Bootstrap Key
Once created, use your bootstrap key to login and create a permanent admin key:
```bash
# Login with the bootstrap key
./aussie auth login --key your-secure-bootstrap-key-at-least-32-chars

# Create a permanent admin key
./aussie keys create --name primary-admin --permissions "*" --ttl 365
```
Save the returned key securely - the bootstrap key will expire automatically within 24 hours.

### Recovery Mode
If you've lost all admin keys, you can use recovery mode to create a new bootstrap key even when admin keys exist:
```bash
# WARNING: Use only for emergency recovery
export AUSSIE_BOOTSTRAP_ENABLED=true
export AUSSIE_BOOTSTRAP_KEY="new-secure-recovery-key-at-least-32-chars"
export AUSSIE_BOOTSTRAP_RECOVERY_MODE=true
```
Recovery mode is logged with a security warning - review your system if you didn't initiate this.

### Security Considerations
| Practice | Description |
|----------|-------------|
| **Use a strong key** | Minimum 32 characters, randomly generated |
| **Short-lived keys** | Bootstrap keys expire in ≤24 hours by design |
| **Immediate rotation** | Create a permanent key and disable bootstrap immediately |
| **Audit logs** | All bootstrap operations are logged to `aussie.audit.bootstrap` |
| **Recovery mode caution** | Only use when absolutely necessary |

### Configuration Reference
| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `AUSSIE_BOOTSTRAP_ENABLED` | `false` | Enable bootstrap mode |
| `AUSSIE_BOOTSTRAP_KEY` | - | Bootstrap key (min 32 chars) |
| `AUSSIE_BOOTSTRAP_TTL` | `PT24H` | Bootstrap key TTL (max: 24h) |
| `AUSSIE_BOOTSTRAP_RECOVERY_MODE` | `false` | Allow bootstrap with existing keys |

## Access Control

Private endpoints are protected by access control rules. Configure globally with environment variables:
```bash
# Allow specific IPs and CIDR ranges
export AUSSIE_GATEWAY_ACCESS_CONTROL_ALLOWED_IPS=10.0.0.0/8,192.168.0.0/16,127.0.0.1

# Allow specific domains
export AUSSIE_GATEWAY_ACCESS_CONTROL_ALLOWED_DOMAINS=internal.example.com

# Allow subdomain patterns
export AUSSIE_GATEWAY_ACCESS_CONTROL_ALLOWED_SUBDOMAINS=*.internal.example.com
```

## Request Forwarding

By default, Aussie uses RFC 7239 `Forwarded` headers:
```
Forwarded: for=192.0.2.60;proto=https;host=api.example.com
```
To use legacy `X-Forwarded-*` headers instead, configure:
```bash
export AUSSIE_GATEWAY_FORWARDING_USE_RFC7239=false
```
This sends:
```
X-Forwarded-For: 192.0.2.60
X-Forwarded-Proto: https
X-Forwarded-Host: api.example.com
```

## Request Size Limits

Aussie enforces configurable request size limits:
```bash
# Maximum request body size (default: 10 MB)
export AUSSIE_GATEWAY_LIMITS_MAX_BODY_SIZE=10485760

# Maximum single header size (default: 8 KB)
export AUSSIE_GATEWAY_LIMITS_MAX_HEADER_SIZE=8192

# Maximum total headers size (default: 32 KB)
export AUSSIE_GATEWAY_LIMITS_MAX_TOTAL_HEADERS_SIZE=32768
```

## Per-Route Authentication

Aussie supports per-route authentication for endpoints that require user identity. When enabled, Aussie validates incoming JWT tokens against configured identity providers and forwards authenticated requests with a signed Aussie token.

### Enabling Per-Route Authentication
Configure token providers and JWS signing with environment variables:
```bash
# Enable per-route authentication
export AUSSIE_AUTH_ROUTE_AUTH_ENABLED=true

# Configure an OIDC provider (e.g., Auth0, Okta, Keycloak)
export AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_MY_IDP_ISSUER=https://example.auth0.com/
export AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_MY_IDP_JWKS_URI=https://example.auth0.com/.well-known/jwks.json
export AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_MY_IDP_AUDIENCES=aussie-gateway

# Configure JWS token issuance for backends
export AUSSIE_AUTH_ROUTE_AUTH_JWS_ISSUER=aussie-gateway
export AUSSIE_AUTH_ROUTE_AUTH_JWS_KEY_ID=v1
export AUSSIE_AUTH_ROUTE_AUTH_JWS_TOKEN_TTL=PT5M
export AUSSIE_AUTH_ROUTE_AUTH_JWS_FORWARDED_CLAIMS=sub,email,name,groups,roles

# RSA signing key (base64-encoded PKCS#8 PEM)
export AUSSIE_JWS_SIGNING_KEY=<base64-encoded-key>
```

### How It Works
1. Client sends request with `Authorization: Bearer <token>` header
2. Aussie validates the token against configured OIDC providers (JWKS signature, issuer, audience, expiration)
3. Aussie issues a new signed JWS token containing forwarded claims
4. Backend receives `Authorization: Bearer <aussie-token>` with validated identity

### Backend Integration
Backends only need to trust Aussie's signing key. The forwarded token includes:
| Claim | Description |
|-------|-------------|
| `iss` | Aussie's issuer (e.g., "aussie-gateway") |
| `sub` | Original token subject |
| `original_iss` | Original token issuer |
| `iat`, `exp` | Issued/expiration times |
| Forwarded claims | Configurable (email, name, groups, roles, etc.) |

To verify tokens in your backend, configure your JWT library to trust Aussie's public key:
```bash
# Generate an RSA key pair
openssl genrsa -out aussie-private.pem 2048
openssl rsa -in aussie-private.pem -pubout -out aussie-public.pem

# Base64 encode for configuration
cat aussie-private.pem | base64 -w0 > aussie-private.b64
```

## Admin API

All admin endpoints require authentication. See [Authentication Configuration](#authentication-configuration) for details.

### Service Management
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/services` | GET | List all registered services |
| `/admin/services` | POST | Register a new service |
| `/admin/services/{id}` | GET | Get a specific service |
| `/admin/services/{id}` | DELETE | Unregister a service |

**CLI equivalents:**
```bash
# List all services
./aussie service list

# Register a service
./aussie register -f my-service.json

# Preview a service
./aussie service preview <service-id>
```

### API Key Management
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/api-keys` | GET | List all API keys (hashes redacted) |
| `/admin/api-keys` | POST | Create a new API key |
| `/admin/api-keys/{id}` | GET | Get a specific API key |
| `/admin/api-keys/{id}` | DELETE | Revoke an API key |

**CLI equivalents:**
```bash
# List all keys
./aussie keys list

# Create a key
./aussie keys create --name my-key --ttl 90

# Revoke a key
./aussie keys revoke <key-id>
```
