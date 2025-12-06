# Aussie API Gateway

<p align="center">
  <img src="static/img/aussie.svg" alt="Aussie Logo" width="150">
</p>

Aussie is an experimental API Gateway built with Quarkus, designed for exploring modern gateway patterns and proxy architectures.

## Table of Contents
- [Overview](#overview)
- [Setup](#setup)
- [Authentication](#authentication)
  - [Configuration](#configuration)
  - [Creating API Keys](#creating-api-keys)
  - [Managing API Keys](#managing-api-keys)
  - [Permissions](#permissions)
  - [Authenticating with the API](#authenticating-with-the-api)
  - [Using the CLI with Authentication](#using-the-cli-with-authentication)
  - [Checking Credentials](#checking-credentials)
  - [Obtaining an API Key](#obtaining-an-api-key)
- [Bootstrap Mode](#bootstrap-mode-first-time-setup)
  - [Enabling Bootstrap](#enabling-bootstrap)
  - [Using the Bootstrap Key](#using-the-bootstrap-key)
  - [Recovery Mode](#recovery-mode)
  - [Security Considerations](#security-considerations)
  - [Configuration Reference](#configuration-reference)
- [Usage](#usage-onboarding-your-service)
  - [Registering a Service](#registering-a-service)
  - [Making Requests Through the Gateway](#making-requests-through-the-gateway)
  - [Endpoint Visibility](#endpoint-visibility)
  - [Restricting Access to Private Endpoints](#restricting-access-to-private-endpoints)
  - [Managing Services](#managing-services)
  - [Request Forwarding Headers](#request-forwarding-headers)
  - [Request Size Limits](#request-size-limits)
- [Routing Strategies](#routing-strategies)
- [Endpoint Configuration](#endpoint-configuration)
- [Admin API](#admin-api)
- [CLI](#cli)
  - [Building the CLI](#building-the-cli)
  - [Configuration](#configuration-1)
  - [Authentication Commands](#authentication-commands)
    - [`auth login`](#auth-login)
    - [`auth status`](#auth-status)
    - [`auth logout`](#auth-logout)
  - [API Key Management Commands](#api-key-management-commands)
    - [`keys create`](#keys-create)
    - [`keys list`](#keys-list)
    - [`keys revoke`](#keys-revoke)
  - [Service Commands](#service-commands)
    - [`register`](#register)
    - [`service validate`](#service-validate)
    - [`service preview`](#service-preview)
  - [CLI Command Reference](#cli-command-reference)
  - [Global Flags](#global-flags)
- [Project Structure](#project-structure)
- [Development](#development)

## Overview
Aussie is a lightweight API gateway for microservices. It provides two routing strategies to fit different architectural needs.

## Setup

### Running in dev mode
```shell
cd api
./gradlew quarkusDev
```
> Note: Quarkus Dev UI is available at http://localhost:8080/q/dev/

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

## Authentication

Aussie requires authentication for all admin endpoints (`/admin/*`). Gateway and pass-through routes remain open for public traffic.

### For Platform Teams Running Aussie

#### Configuration
Authentication is enabled by default. Configure it in `application.properties`:
```properties
# Enable/disable authentication (default: true)
aussie.auth.enabled=true

# Only require auth for admin paths (default: true)
aussie.auth.admin-paths-only=true

# DANGEROUS: Disable authentication entirely (NEVER use in production!)
# This is only for local development
aussie.auth.dangerous-noop=false
```
For local development, you can disable authentication:
```properties
%dev.aussie.auth.dangerous-noop=true
```

#### Creating API Keys
Create an API key via the admin endpoint:
```bash
curl -X POST http://localhost:8080/admin/api-keys \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-team-key",
    "description": "API key for My Team",
    "permissions": ["admin:read", "admin:write"],
    "ttlDays": 90
  }'
```
Response:
```json
{
  "keyId": "ak_abc123",
  "key": "aussie_xxxxxxxxxxxx",
  "name": "my-team-key",
  "permissions": ["admin:read", "admin:write"],
  "expiresAt": "2025-03-06T10:30:00Z"
}
```
> Important: Save the `key` value immediately. It is only shown once and cannot be retrieved later.

#### Managing API Keys
```bash
# List all API keys (hashes are redacted)
curl http://localhost:8080/admin/api-keys \
  -H "Authorization: Bearer aussie_xxxxxxxxxxxx"

# Get a specific key
curl http://localhost:8080/admin/api-keys/{keyId} \
  -H "Authorization: Bearer aussie_xxxxxxxxxxxx"

# Revoke a key
curl -X DELETE http://localhost:8080/admin/api-keys/{keyId} \
  -H "Authorization: Bearer aussie_xxxxxxxxxxxx"
```

#### Permissions
| Permission | Description |
|------------|-------------|
| `admin:read` | Read access to admin endpoints (GET, HEAD, OPTIONS) |
| `admin:write` | Write access to admin endpoints (POST, PUT, DELETE) |
| `*` | Full access to all operations |

### For App Developers Using the CLI

#### Authenticating with the API
All admin requests require a Bearer token:
```bash
curl http://localhost:8080/admin/services \
  -H "Authorization: Bearer aussie_xxxxxxxxxxxx"
```

#### Using the CLI with Authentication
The recommended approach is to use `auth login` to configure your credentials:
```bash
# Login interactively
./aussie auth login

# Or login with your API key directly
./aussie auth login --key aussie_xxxxxxxxxxxx

# Check your authentication status
./aussie auth status

# Now all commands will use your stored credentials
./aussie register -f my-service.json
./aussie keys list
```
Credentials are stored in `~/.aussie` and used automatically for subsequent commands.

#### Checking Credentials
Use the `/admin/whoami` endpoint or CLI to verify your credentials:
```bash
# Via CLI
./aussie auth status

# Via curl
curl http://localhost:8080/admin/whoami \
  -H "Authorization: Bearer aussie_xxxxxxxxxxxx"
```

#### Obtaining an API Key
Contact your platform team to obtain an API key with appropriate permissions:
- Read-only access (`admin:read`): For viewing registered services
- Read/write access (`admin:read`, `admin:write`): For registering and managing services

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
./gradlew quarkusDev
```
On startup, Aussie will:
1. Check if bootstrap mode is enabled
2. Verify no admin keys already exist
3. Create a time-limited admin key using your provided bootstrap key
4. Log the key ID and expiration (never the key itself)

### Using the Bootstrap Key
Once created, use your bootstrap key to create a permanent admin key:
```bash
# Create a permanent admin key using the bootstrap key
curl -X POST http://localhost:8080/admin/api-keys \
  -H "Authorization: Bearer your-secure-bootstrap-key-at-least-32-chars" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "primary-admin",
    "permissions": ["*"],
    "ttlDays": 365
  }'
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
| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `aussie.bootstrap.enabled` | `AUSSIE_BOOTSTRAP_ENABLED` | `false` | Enable bootstrap mode |
| `aussie.bootstrap.key` | `AUSSIE_BOOTSTRAP_KEY` | - | Bootstrap key (min 32 chars) |
| `aussie.bootstrap.ttl` | `AUSSIE_BOOTSTRAP_TTL` | `PT24H` | Bootstrap key TTL (max: 24h) |
| `aussie.bootstrap.recovery-mode` | `AUSSIE_BOOTSTRAP_RECOVERY_MODE` | `false` | Allow bootstrap with existing keys |

## Usage: Onboarding Your Service

Aussie provides both a CLI and REST API for registering your services. Once registered, requests to the gateway are automatically proxied to your backend.

### Registering a Service

#### Using the CLI (Recommended)
1. Create a service configuration file (e.g., `my-service.json`):
```json
{
  "serviceId": "user-service",
  "displayName": "User Service",
  "baseUrl": "http://localhost:3001",
  "routePrefix": "/users",
  "defaultVisibility": "PRIVATE",
  "visibilityRules": [
    {
      "pattern": "/api/users",
      "methods": ["GET"],
      "visibility": "PUBLIC"
    },
    {
      "pattern": "/api/users/**",
      "visibility": "PUBLIC"
    },
    {
      "pattern": "/api/admin/**",
      "visibility": "PRIVATE"
    }
  ]
}
```
2. Register the service:
```bash
# Start the gateway in dev mode
cd api
./gradlew quarkusDev

# Register the service via CLI
cd ../cli
./aussie register -f ../my-service.json
```
To use a different Aussie server:
```bash
./aussie register -f ../my-service.json -s http://aussie.example.com:8080
```

#### Using the REST API
Alternatively, send a POST request to `/admin/services`:
```bash
curl -X POST http://localhost:8080/admin/services \
  -H "Content-Type: application/json" \
  -d @my-service.json
```
Or inline:
```bash
curl -X POST http://localhost:8080/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "user-service",
    "displayName": "User Service",
    "baseUrl": "http://localhost:3001",
    "defaultVisibility": "PRIVATE",
    "visibilityRules": [
      {
        "pattern": "/api/users/**",
        "visibility": "PUBLIC"
      }
    ]
  }'
```

### Making Requests Through the Gateway
Once registered, access your service through the `/gateway` prefix:
```bash
# Public endpoint - accessible by anyone
curl http://localhost:8080/gateway/api/users

# POST with body
curl -X POST http://localhost:8080/gateway/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Alice", "email": "alice@example.com"}'
```

### Endpoint Visibility
Aussie supports two visibility levels:
| Visibility | Description |
|------------|-------------|
| `PUBLIC` | Accessible by anyone |
| `PRIVATE` | Restricted to configured IP addresses, domains, or subdomains |

### Restricting Access to Private Endpoints
Private endpoints are protected by access control rules. Configure globally in `application.properties`:
```properties
# Allow specific IPs and CIDR ranges
aussie.gateway.access-control.allowed-ips=10.0.0.0/8,192.168.0.0/16,127.0.0.1

# Allow specific domains
aussie.gateway.access-control.allowed-domains=internal.example.com

# Allow subdomain patterns
aussie.gateway.access-control.allowed-subdomains=*.internal.example.com
```
Or restrict per-service by including `accessConfig` in your registration:
```json
{
  "serviceId": "admin-service",
  "displayName": "Admin Service",
  "baseUrl": "http://localhost:3002",
  "defaultVisibility": "PRIVATE",
  "visibilityRules": [
    { "pattern": "/api/admin/**", "visibility": "PRIVATE" }
  ],
  "accessConfig": {
    "allowedIps": ["10.0.0.0/8"],
    "allowedDomains": ["admin.internal.example.com"]
  }
}
```

### Managing Services
```bash
# List all registered services
curl http://localhost:8080/admin/services

# Get a specific service
curl http://localhost:8080/admin/services/user-service

# Unregister a service
curl -X DELETE http://localhost:8080/admin/services/user-service
```

### Request Forwarding Headers
By default, Aussie uses RFC 7239 `Forwarded` headers:
```
Forwarded: for=192.0.2.60;proto=https;host=api.example.com
```
To use legacy `X-Forwarded-*` headers instead, configure:
```properties
aussie.gateway.forwarding.use-rfc7239=false
```
This sends:
```
X-Forwarded-For: 192.0.2.60
X-Forwarded-Proto: https
X-Forwarded-Host: api.example.com
```

### Request Size Limits
Aussie enforces configurable request size limits:
```properties
# Maximum request body size (default: 10 MB)
aussie.gateway.limits.max-body-size=10485760

# Maximum single header size (default: 8 KB)
aussie.gateway.limits.max-header-size=8192

# Maximum total headers size (default: 32 KB)
aussie.gateway.limits.max-total-headers-size=32768
```

## Routing Strategies

### 1. Pass-Through Routing (`/{serviceId}/{path}`)
Routes requests directly to a service by its ID. The service ID is part of the URL.
```
┌─────────────────────────────────────────────────────────────────┐
│  Client Request                                                 │
│  GET /user-service/api/users/123                               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Aussie Gateway                                                 │
│  1. Extract serviceId: "user-service"                          │
│  2. Look up service by ID                                       │
│  3. Forward: GET http://user-service-host/api/users/123        │
└─────────────────────────────────────────────────────────────────┘
```
**When to use:**
- Service discovery pattern where clients know which service to call
- Simple setups where each service has its own namespace
- Microservices that need direct, unambiguous routing

**Example:**
```bash
# Register the service
curl -X POST http://localhost:8080/admin/services \
  -H "Content-Type: application/json" \
  -d '{"serviceId": "user-service", "baseUrl": "http://localhost:3001"}'

# Access via pass-through (serviceId in URL)
curl http://localhost:8080/user-service/api/users
curl http://localhost:8080/user-service/api/users/123
curl -X POST http://localhost:8080/user-service/api/users -d '{"name": "Alice"}'
```
**Characteristics:**
- No endpoint registration required (just the service)
- All paths are forwarded to the service
- Visibility rules applied per service configuration
- Service ID is visible in the URL

### 2. Gateway Routing (`/gateway/{path}`)
Routes requests based on registered endpoint patterns. Multiple services can share a single URL namespace.
```
┌─────────────────────────────────────────────────────────────────┐
│  Client Request                                                 │
│  GET /gateway/api/users/123                                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Aussie Gateway                                                 │
│  1. Match path "/api/users/123" against registered endpoints   │
│  2. Find matching route: /api/users/{id} → user-service        │
│  3. Forward: GET http://user-service-host/api/users/123        │
└─────────────────────────────────────────────────────────────────┘
```
**When to use:**
- API gateway pattern where clients don't know about backend services
- Multiple services need to share a unified API namespace
- You need path rewriting (e.g., `/v2/users` → `/users`)
- Fine-grained routing control per endpoint

**Example:**
```bash
# Register services with explicit endpoints
curl -X POST http://localhost:8080/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "user-service",
    "baseUrl": "http://localhost:3001",
    "endpoints": [
      {"path": "/api/users", "methods": ["GET", "POST"], "visibility": "PUBLIC"},
      {"path": "/api/users/{id}", "methods": ["GET", "PUT", "DELETE"], "visibility": "PUBLIC"}
    ]
  }'

curl -X POST http://localhost:8080/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "order-service",
    "baseUrl": "http://localhost:3002",
    "endpoints": [
      {"path": "/api/orders", "methods": ["GET", "POST"], "visibility": "PUBLIC"},
      {"path": "/api/orders/{id}", "methods": ["GET"], "visibility": "PUBLIC"}
    ]
  }'

# Access via gateway (unified namespace, no serviceId in URL)
curl http://localhost:8080/gateway/api/users        # → user-service
curl http://localhost:8080/gateway/api/users/123    # → user-service
curl http://localhost:8080/gateway/api/orders       # → order-service
curl http://localhost:8080/gateway/api/orders/456   # → order-service
```
**Characteristics:**
- Requires explicit endpoint registration
- Supports path variables (`{id}`) and wildcards (`**`)
- Supports path rewriting
- Multiple services can handle different paths
- Service topology hidden from clients

## Endpoint Configuration
When registering a service, you can configure endpoints for gateway routing:
```json
{
  "serviceId": "user-service",
  "baseUrl": "http://localhost:3001",
  "defaultVisibility": "PRIVATE",
  "endpoints": [
    {
      "path": "/api/users",
      "methods": ["GET", "POST"],
      "visibility": "PUBLIC"
    },
    {
      "path": "/api/users/{id}",
      "methods": ["GET", "PUT", "DELETE"],
      "visibility": "PUBLIC",
      "pathRewrite": "/users/{id}"
    },
    {
      "path": "/api/admin/**",
      "methods": ["*"],
      "visibility": "PRIVATE"
    }
  ],
  "accessConfig": {
    "allowedIps": ["10.0.0.0/8"],
    "allowedDomains": ["internal.example.com"]
  }
}
```

### Path Patterns
- Exact: `/api/users` - matches only `/api/users`
- Variables: `/api/users/{id}` - matches `/api/users/123`, captures `id=123`
- Single wildcard: `/api/*/info` - matches `/api/users/info`, `/api/orders/info`
- Multi wildcard: `/api/**` - matches `/api/anything/here/deeply/nested`

### Visibility
- PUBLIC: Accessible from any source
- PRIVATE: Restricted by `accessConfig` (IP/domain allowlists)

### Per-Route Authentication
Aussie supports per-route authentication for endpoints that require user identity. When enabled, Aussie validates incoming JWT tokens against configured identity providers and forwards authenticated requests with a signed Aussie token.

#### Enabling Per-Route Authentication
Configure token providers and JWS signing in `application.properties`:
```properties
# Enable per-route authentication
aussie.auth.route-auth.enabled=true

# Configure an OIDC provider (e.g., Auth0, Okta, Keycloak)
aussie.auth.route-auth.providers.my-idp.issuer=https://example.auth0.com/
aussie.auth.route-auth.providers.my-idp.jwks-uri=https://example.auth0.com/.well-known/jwks.json
aussie.auth.route-auth.providers.my-idp.audiences=aussie-gateway

# Configure JWS token issuance for backends
aussie.auth.route-auth.jws.issuer=aussie-gateway
aussie.auth.route-auth.jws.key-id=v1
aussie.auth.route-auth.jws.token-ttl=PT5M
aussie.auth.route-auth.jws.forwarded-claims=sub,email,name,groups,roles

# RSA signing key (base64-encoded PKCS#8 PEM)
aussie.auth.route-auth.jws.signing-key=${AUSSIE_JWS_SIGNING_KEY}
```

#### Marking Endpoints as Requiring Authentication
Add `authRequired: true` to endpoint configurations:
```json
{
  "serviceId": "user-service",
  "baseUrl": "http://user-service:8080",
  "endpoints": [
    {
      "path": "/api/users",
      "methods": ["GET"],
      "visibility": "PUBLIC",
      "authRequired": false
    },
    {
      "path": "/api/users/{userId}/profile",
      "methods": ["GET", "PUT"],
      "visibility": "PUBLIC",
      "authRequired": true
    }
  ]
}
```

#### How It Works
1. Client sends request with `Authorization: Bearer <token>` header
2. Aussie validates the token against configured OIDC providers (JWKS signature, issuer, audience, expiration)
3. Aussie issues a new signed JWS token containing forwarded claims
4. Backend receives `Authorization: Bearer <aussie-token>` with validated identity

#### Backend Integration
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
All admin endpoints require authentication. See [Authentication](#authentication) for details.

### Service Management
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/services` | GET | List all registered services |
| `/admin/services` | POST | Register a new service |
| `/admin/services/{id}` | GET | Get a specific service |
| `/admin/services/{id}` | DELETE | Unregister a service |

### API Key Management
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/api-keys` | GET | List all API keys (hashes redacted) |
| `/admin/api-keys` | POST | Create a new API key |
| `/admin/api-keys/{id}` | GET | Get a specific API key |
| `/admin/api-keys/{id}` | DELETE | Revoke an API key |

## CLI
The Aussie CLI provides commands for managing authentication, API keys, and service registrations.

### Building the CLI
```bash
cd cli
go build -o aussie
```

### Configuration
The CLI uses configuration files to store settings like the server URL and API key.

**Configuration locations (in order of precedence):**
1. Local `.aussierc` file in the current directory
2. Global `~/.aussie` file in your home directory
3. Default values

**Configuration format (TOML):**
```toml
host = "http://localhost:8080"
api_key = "your-api-key"
```

### Authentication Commands
Manage your CLI credentials for accessing the Aussie API.

#### `auth login`
Configure your API key credentials interactively or via flags.
```bash
# Interactive login (prompts for server and API key)
./aussie auth login

# Login with API key flag
./aussie auth login --key your-api-key

# Login with both server and key
./aussie auth login --server https://aussie.example.com --key your-api-key
```
The command validates your credentials by calling `/admin/whoami` before saving.

**Flags:**
| Flag | Short | Description |
|------|-------|-------------|
| `--server` | `-s` | API server URL |
| `--key` | `-k` | API key |

#### `auth status`
Check your current authentication status.
```bash
./aussie auth status
```
**Output:**
```
Server: http://localhost:8080
Status: Authenticated
Key ID: abc123
Name:   my-api-key
Permissions: admin:read, admin:write
Expires: 2025-06-06T10:30:00Z
```

#### `auth logout`
Remove stored credentials from your configuration.
```bash
./aussie auth logout
```

### API Key Management Commands
Manage API keys for the Aussie gateway. Requires authentication.

#### `keys create`
Create a new API key.
```bash
# Create a key with a name
./aussie keys create --name my-service-key

# Create with TTL (days until expiration)
./aussie keys create --name ci-pipeline --ttl 7

# Create with specific permissions
./aussie keys create --name read-only --permissions admin:read

# Create with description
./aussie keys create --name prod-key --description "Production deployment key" --ttl 90
```
**Flags:**
| Flag | Short | Default | Description |
|------|-------|---------|-------------|
| `--name` | `-n` | (required) | Name for the API key |
| `--description` | `-d` | | Description of the key's purpose |
| `--ttl` | `-t` | 0 | TTL in days (0 = no expiration) |
| `--permissions` | `-p` | `*` | Permissions (comma-separated) |
**Output:**
```
API key created successfully!

Key ID:      abc123
Name:        my-service-key
Permissions: *
Expires:     2025-06-06
Created By:  def456

API Key (save this - it won't be shown again):
  aussie_xxxxxxxxxxxxxxxxxxxx
```
> Important: The plaintext key is only shown once. Save it immediately.

#### `keys list`
List all API keys.
```bash
./aussie keys list
```
**Output:**
```
ID        NAME              PERMISSIONS    CREATED BY   EXPIRES      STATUS
--        ----              -----------    ----------   -------      ------
abc123    my-service-key    *              bootstrap    2025-06-06   active
def456    ci-key            2 permissions  abc123       2025-03-06   active
ghi789    old-key           *              bootstrap    2025-01-01   revoked
```

#### `keys revoke`
Revoke an API key by its ID.
```bash
./aussie keys revoke <key-id>

# Example
./aussie keys revoke abc123
```
**Output:**
```
API key abc123 has been revoked.
```

### Service Commands
Manage service registrations.

#### `register`
Register a service with the gateway.
```bash
# Register from a JSON file
./aussie register -f my-service.json

# Register with a specific server
./aussie register -f my-service.json -s https://aussie.example.com
```
**Flags:**
| Flag | Short | Description |
|------|-------|-------------|
| `--file` | `-f` | Path to service configuration JSON file (required) |
| `--server` | `-s` | Override server URL |

#### `service validate`
Validate a service configuration file without registering it.
```bash
./aussie service validate -f my-service.json
```
This checks:
- Required fields (`serviceId`, `displayName`, `baseUrl`)
- Field types and formats
- Visibility rules and endpoint configurations
- Optional fields like `routePrefix`, `defaultVisibility`, and `accessConfig`
**Output (success):**
```
✓ Service configuration is valid

Service ID:     user-service
Display Name:   User Service
Base URL:       http://localhost:3001
Route Prefix:   /users
Visibility:     2 PUBLIC, 1 PRIVATE rules
```
**Output (error):**
```
✗ Validation failed

Errors:
  - serviceId is required
  - baseUrl must be a valid URL
```

#### `service preview`
Preview visibility settings for a registered service.
```bash
./aussie service preview <service-id>

# Example
./aussie service preview user-service
```
**Output:**
```
Service: user-service (User Service)
Base URL: http://localhost:3001
Default Visibility: PRIVATE

Visibility Rules:
  PUBLIC   /api/users         GET
  PUBLIC   /api/users/**      *
  PRIVATE  /api/admin/**      *
```

### CLI Command Reference
| Command | Description |
|---------|-------------|
| `auth login` | Configure API key credentials |
| `auth logout` | Remove stored credentials |
| `auth status` | Show current authentication status |
| `keys create` | Create a new API key |
| `keys list` | List all API keys |
| `keys revoke <id>` | Revoke an API key |
| `register -f <file>` | Register a service |
| `service validate -f <file>` | Validate a service configuration |
| `service preview <id>` | Preview service visibility settings |

### Global Flags
These flags are available for all commands:
| Flag | Short | Description |
|------|-------|-------------|
| `--server` | `-s` | Override the server URL |
| `--help` | `-h` | Show help for the command |

## Project Structure
```
aussie/
├── api/          # Quarkus REST API (Java 21)
├── cli/          # Command-line interface (Go)
└── demo/         # Demo application (Next.js)
```

## Development
```bash
# Run in dev mode with live reload
cd api
./gradlew quarkusDev

# Run tests
./gradlew test

# Build
./gradlew build
```
