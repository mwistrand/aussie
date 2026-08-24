# Aussie Consumer Guide

This guide is for developers onboarding their applications to the Aussie API Gateway.

## Table of Contents
- [Getting Started](#getting-started)
- [Authentication](#authentication)
- [CLI Installation](#cli-installation)
- [Registering Your Service](#registering-your-service)
- [Making Requests Through the Gateway](#making-requests-through-the-gateway)
- [Routing Strategies](#routing-strategies)
- [Endpoint Configuration](#endpoint-configuration)
- [Request Timeouts](timeouts.md)
- [WebSocket Endpoints](websocket-onboarding.md)
- [Problem Details Error Bodies](problem-details.md)
- [CLI Reference](#cli-reference)

## Getting Started

To onboard your service to Aussie, you'll need:
1. Authentication configured (IdP login or API key from your platform team)
2. The Aussie CLI
3. A service configuration file describing your endpoints

## Authentication

Aussie supports two authentication methods:
1. **IdP Authentication** (recommended) - Authenticate with your organization's SSO
2. **API Key** (fallback) - Use a pre-shared API key

### IdP Authentication (Recommended)

Authenticate using your organization's identity provider (SAML, OIDC, etc.):

**1. Configure your `.aussierc`:**
```toml
host = "http://localhost:1234"

[auth]
login_url = "https://sso.yourcompany.com/auth/aussie/login"
logout_url = "https://sso.yourcompany.com/auth/aussie/logout"   # Optional
refresh_url = "https://sso.yourcompany.com/auth/aussie/refresh" # Optional
mode = "browser"  # Options: browser, device_code, cli_callback
```

**2. Login:**
```bash
./aussie login
```
This opens your browser for SSO authentication. After successful login, your token is stored locally.

**3. Check status:**
```bash
./aussie auth status
```
**Output:**
```
Server: http://localhost:1234

Authentication: JWT Token (IdP)
  User:   alice@example.com
  Name:   Alice Smith
  Groups: demo-service.admin, demo-service.dev
  Expires: 2025-03-08T18:30:00Z

Server Status: Authenticated
  Groups: demo-service.admin, demo-service.dev
  Effective Permissions: demo-service.admin, demo-service.lead, demo-service.dev
```

**4. Logout:**
```bash
./aussie auth logout           # Clear local credentials only
./aussie auth logout --server  # Also invalidate server session
```

### Authentication Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| `browser` | Opens browser for OAuth/SAML login (default) | Workstations with browsers |
| `device_code` | Displays code to enter at verification URL | CI/CD, SSH sessions, headless environments |
| `cli_callback` | CLI starts local server, displays URL | Environments where browser can't auto-open |

**Using device code mode (for headless environments):**
```bash
# Override mode for this invocation
./aussie login --mode device_code
```
**Output:**
```
To authenticate, visit:
  https://sso.yourcompany.com/device

And enter code: ABCD-1234

Waiting for authentication...
```

### API Key Authentication (Fallback)

If your organization has API keys enabled, add the key to your configuration:

Store API keys only in **~/.aussierc** (global). Project-local `.aussierc` files may select a host but cannot supply credentials:
```toml
host = "http://localhost:1234"
api_key = "your-api-key"
```

```bash
# Check your authentication status
./aussie auth status

# Now all commands will use your stored credentials
./aussie service register -f my-service.json
./aussie keys list
```

**Note:** API keys are disabled by default. Contact your platform team if you need one.

### Authentication Precedence

When both IdP credentials and API key are available, the CLI uses this precedence:
1. JWT token from `aussie login` (stored in `~/.aussie/credentials`)
2. API key from `~/.aussierc`, only when the project-local configuration has not changed its host

### Checking Credentials
Verify your credentials using the CLI:
```bash
./aussie auth status
```
**Output (JWT token):**
```
Server: http://localhost:1234

Authentication: JWT Token (IdP)
  User:   alice@example.com
  Groups: demo-service.admin, demo-service.dev
  Expires: 2025-03-08T18:30:00Z

Server Status: Authenticated
  Effective Permissions: demo-service.admin, demo-service.lead, demo-service.dev
```

**Output (API key):**
```
Server: http://localhost:1234

Authentication: API Key (fallback)

Server Status: Authenticated
  Key ID: abc123
  Name: my-api-key
  Permissions: admin:read, admin:write
  Expires: 2025-06-06T10:30:00Z
```

## CLI Installation

### Building the CLI
```bash
cd cli
go build -o aussie
```

### Configuration
The CLI uses configuration files for the server URL and authentication settings. Secrets such as API keys belong only in the global file.

**Configuration locations (in order of precedence):**
1. Local `.aussierc` non-secret settings in the current directory
2. Global `~/.aussierc` file in your home directory
3. Default values

**Global configuration format (`~/.aussierc`, TOML):**
```toml
host = "http://localhost:1234"
api_key = "your-api-key"
```

## Registering Your Service

### Using the CLI
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
2. Validate your configuration (optional but recommended):
```bash
./aussie service validate -f my-service.json
```
3. Register the service:
```bash
./aussie service register -f my-service.json
```
To use a different Aussie server:
```bash
./aussie service register -f my-service.json -s http://aussie.example.com:8080
```

### Managing Services
```bash
# List the first page of registered services
./aussie service list

# Preview a specific service's visibility settings
./aussie service preview user-service
```

## Making Requests Through the Gateway

Once registered, access your service through the gateway:
```bash
# Public endpoint - accessible by anyone
curl http://localhost:1234/gateway/api/users

# POST with body
curl -X POST http://localhost:1234/gateway/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Alice", "email": "alice@example.com"}'
```

### Endpoint Visibility
Aussie supports two visibility levels:
| Visibility | Description |
|------------|-------------|
| `PUBLIC` | Accessible by anyone |
| `PRIVATE` | Restricted to configured IP addresses or CIDR ranges |

### Restricting Access to Private Endpoints
You can restrict per-service by including `accessConfig` in your registration:
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
    "allowedIps": ["10.20.0.0/16"]
  }
}
```

The service ranges must be contained by the platform's global allowed-IP boundary.
Host and forwarded-host headers are routing metadata and cannot authorize callers.

## Routing Strategies

Aussie provides two routing strategies to fit different architectural needs.

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

Create `user-service.json`:
```json
{
  "serviceId": "user-service",
  "displayName": "User Service",
  "baseUrl": "http://localhost:3001"
}
```
Register and access:
```bash
# Register the service
./aussie service register -f user-service.json

# Access via pass-through (serviceId in URL)
curl http://localhost:1234/user-service/api/users
curl http://localhost:1234/user-service/api/users/123
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

Create `user-service.json` with explicit endpoints:
```json
{
  "serviceId": "user-service",
  "displayName": "User Service",
  "baseUrl": "http://localhost:3001",
  "endpoints": [
    {"path": "/api/users", "methods": ["GET", "POST"], "visibility": "PUBLIC"},
    {"path": "/api/users/{id}", "methods": ["GET", "PUT", "DELETE"], "visibility": "PUBLIC"}
  ]
}
```
Register and access:
```bash
# Register the service
./aussie service register -f user-service.json

# Access via gateway (unified namespace)
curl http://localhost:1234/gateway/api/users
curl http://localhost:1234/gateway/api/users/123
```
**Characteristics:**
- Requires explicit endpoint registration
- Supports path variables (`{id}`) and wildcards (`**`)
- Supports path rewriting
- Multiple services can handle different paths
- Service topology hidden from clients

### Choosing the Right Strategy
| Scenario | Recommended | Why |
|----------|-------------|-----|
| Microservices with clear boundaries | Pass-Through | Simple, explicit service addressing |
| Public API with unified namespace | Gateway | Hide internal service topology |
| API versioning (`/v1`, `/v2`) | Gateway | Path rewriting support |
| Service mesh / internal routing | Pass-Through | Direct, low overhead |
| Multi-tenant with service isolation | Pass-Through | Clear tenant→service mapping |
| BFF (Backend for Frontend) | Gateway | Aggregate multiple services |

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
    "allowedIps": ["10.20.0.0/16"]
  }
}
```

### Path Patterns
- Exact: `/api/users` - matches only `/api/users`
- Variables: `/api/users/{id}` - matches `/api/users/123`, captures `id=123`
- Single wildcard: `/api/*/info` - matches `/api/users/info`, `/api/orders/info`
- Multi wildcard: `/api/**` - matches `/api/anything/here/deeply/nested`

Gateway routes must have unambiguous ownership. Registration returns `409 Conflict` when a route can match the same path and HTTP method as a route owned by another service. Use disjoint methods or paths to resolve the conflict.

### Visibility
- PUBLIC: Accessible from any source
- PRIVATE: Restricted by `accessConfig` (IP/domain allowlists)

### Authenticated Endpoints
If your platform has per-route authentication enabled, you can mark endpoints as requiring authentication:
```json
{
  "path": "/api/users/{userId}/profile",
  "methods": ["GET", "PUT"],
  "visibility": "PUBLIC",
  "authRequired": true
}
```
When `authRequired: true`:
1. Clients must include an `Authorization: Bearer <token>` header
2. Aussie validates the token against configured identity providers
3. Your backend receives a signed Aussie token with the validated identity

### Audience Validation
To prevent cross-service token replay attacks, you can configure an `audience` claim for authenticated endpoints. When configured, the Aussie token issued to your backend will include an `aud` (audience) claim that your service can validate.

```json
{
  "path": "/api/billing/{invoiceId}",
  "methods": ["GET", "PUT"],
  "visibility": "PUBLIC",
  "authRequired": true,
  "audience": "billing-service"
}
```

When `audience` is configured:
1. The issued Aussie token includes an `aud` claim with the specified value
2. Your backend should validate the `aud` claim matches its expected audience
3. Tokens intended for other services will have different audience values and should be rejected

**Example validation in your backend (Java/Quarkus):**
```java
@Inject
JsonWebToken jwt;

@GET
@Path("/api/billing/{id}")
public Response getBilling(@PathParam("id") String id) {
    // Validate the token was issued for this service
    if (!jwt.getAudience().contains("billing-service")) {
        return Response.status(403)
            .entity("Token not intended for this service")
            .build();
    }
    // Process the request...
}
```

**Benefits of audience validation:**
- Prevents tokens issued for one service from being replayed against another
- Enables per-service authorization boundaries
- Provides defense-in-depth when services have different permission models

See [Token Audience Validation](token-audience.md) for detailed implementation guidance.

### Security Header Overrides

Services can override the gateway's default security response headers by including `securityHeadersConfig` in their registration. Only the headers you specify are overridden; all others use the global defaults.

```json
{
  "serviceId": "dashboard-service",
  "baseUrl": "http://localhost:3005",
  "securityHeadersConfig": {
    "contentSecurityPolicy": "default-src 'self'; script-src 'self' 'unsafe-inline'",
    "frameOptions": "SAMEORIGIN",
    "customHeaders": {
      "X-Dashboard-Version": "2.0"
    }
  }
}
```

Available override fields: `contentTypeOptions`, `frameOptions`, `contentSecurityPolicy`, `referrerPolicy`, `permittedCrossDomainPolicies`, `strictTransportSecurity`, `permissionsPolicy`.

Setting a field to an empty string (`""`) suppresses that header entirely for the service. The `customHeaders` map adds arbitrary additional response headers.

## CLI Reference

### Authentication Commands

#### Configuration
Configure login settings in `~/.aussierc` (global) or `.aussierc` (project-local). Store API keys and the token-bearing `auth.logout_url` only in `~/.aussierc`:

**For IdP authentication:**
```toml
host = "http://localhost:1234"

[auth]
login_url = "https://sso.yourcompany.com/auth/aussie/login"
logout_url = "https://sso.yourcompany.com/auth/aussie/logout"
mode = "browser"  # or "device_code" for headless environments
```

**For API key authentication (if enabled):**
```toml
host = "http://localhost:1234"
api_key = "your-api-key"
```

#### `login` / `auth login`
Authenticate with your organization's identity provider.

> **Note:** `login` and `logout` are available as both top-level commands and under `auth`:
> `aussie login` is equivalent to `aussie auth login`.

```bash
# Uses configured mode (default: browser)
./aussie login

# Override mode for this invocation
./aussie login --mode device_code
./aussie login --mode cli_callback
```
| Flag | Default | Description |
|------|---------|-------------|
| `--mode` | (from config) | Auth mode: `browser`, `device_code`, `cli_callback` |

#### `logout` / `auth logout`
Clear stored authentication credentials.
```bash
# Clear local credentials only
./aussie logout

# Also invalidate server session (if logout_url configured)
./aussie logout --server
```
| Flag | Default | Description |
|------|---------|-------------|
| `--server` | `false` | Also logout from IdP server |

#### `auth status`
Check your current authentication status.
```bash
./aussie auth status
```
**Output (IdP authentication):**
```
Server: http://localhost:1234

Authentication: JWT Token (IdP)
  User:   alice@example.com
  Groups: demo-service.admin, demo-service.dev
  Expires: 2025-03-08T18:30:00Z

Server Status: Authenticated
  Effective Permissions: demo-service.admin, demo-service.lead, demo-service.dev
```

**Output (API key):**
```
Server: http://localhost:1234

Authentication: API Key (fallback)

Server Status: Authenticated
  Key ID: abc123
  Permissions: admin:read, admin:write
  Expires: 2025-06-06T10:30:00Z
```

### API Key Management Commands

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

# Create with team ID for traffic attribution
./aussie keys create --name platform-key --team platform-team
```
| Flag | Short | Default | Description |
|------|-------|---------|-------------|
| `--name` | `-n` | (required) | Name for the API key |
| `--description` | `-d` | | Description of the key's purpose |
| `--team` | | | Team ID for traffic attribution |
| `--ttl` | `-t` | 0 | TTL in days (0 = no expiration) |
| `--permissions` | `-p` | `*` | Permissions (comma-separated) |

#### `keys list`
List API keys. Results default to 50 entries; use `--limit` and `--offset` to select a page.
```bash
./aussie keys list --limit 50 --offset 0
```

#### `keys revoke`
Revoke an API key by its ID.
```bash
./aussie keys revoke <key-id>
```

### Service Commands

#### `service register`
Register a service with the gateway.
```bash
# Register from a JSON file
./aussie service register -f my-service.json

# Register with a specific server
./aussie service register -f my-service.json -s https://aussie.example.com
```
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
- `baseUrl` SSRF protection (see below)
- Visibility rules and endpoint configurations
- Optional fields like `routePrefix`, `defaultVisibility`, and `accessConfig`

**`baseUrl` requirements:**
- Must use `http` or `https` scheme
- Host must appear in the platform-owned upstream allowlist
- Must not point to loopback addresses (`127.x.x.x`, `::1`, `localhost`)
- Must not point to link-local or cloud metadata addresses (`169.254.x.x`)
- Must not point to wildcard addresses (`0.0.0.0`, `::`)
- Private network addresses (`10.x`, `172.16-31.x`, `192.168.x`) require an explicit platform exception

#### `service list`
List registered services.
```bash
./aussie service list
```

#### `service preview`
Preview visibility settings for a registered service.
```bash
./aussie service preview <service-id>
```

### Command Summary
| Command | Description |
|---------|-------------|
| `login` / `auth login` | Authenticate with IdP |
| `logout` / `auth logout` | Clear stored credentials |
| `auth status` | Show current authentication status |
| `groups create` | Create a new RBAC group |
| `groups list` | List all groups |
| `groups get <id>` | Get a specific group |
| `groups update <id>` | Update a group |
| `groups delete <id>` | Delete a group |
| `keys create` | Create a new API key |
| `keys list` | List API keys |
| `keys revoke <id>` | Revoke an API key |
| `service register -f <file>` | Register a service |
| `service validate -f <file>` | Validate a service configuration |
| `service list` | List registered services |
| `service preview <id>` | Preview service visibility settings |
| `service delete <id>` | Delete a service registration |

### Global Flags
These flags are available for all commands:
| Flag | Short | Description |
|------|-------|-------------|
| `--server` | `-s` | Override the server URL |
| `--help` | `-h` | Show help for the command |

## Troubleshooting

### "Service not found" on pass-through
```bash
# Check registered services
./aussie service list

# Verify serviceId matches URL
# URL: /user-service/... requires serviceId: "user-service"
```

### "Not found" on gateway routing
```bash
# Check registered endpoints
./aussie service preview user-service

# Verify endpoint path and method match
# Endpoint: {"path": "/api/users", "methods": ["GET"]}
# Request must match: GET /gateway/api/users
```

### Reserved paths
These paths cannot be used as service IDs:
- `admin` - Admin API
- `gateway` - Gateway routing
- `q` - Quarkus endpoints (health, metrics)
