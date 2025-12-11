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
- [WebSocket Endpoints](websocket-onboarding.md)
- [CLI Reference](#cli-reference)

## Getting Started

To onboard your service to Aussie, you'll need:
1. An API key from your platform team
2. The Aussie CLI
3. A service configuration file describing your endpoints

## Authentication

### Obtaining an API Key
Contact your platform team to obtain an API key with appropriate permissions:
- Read-only access (`admin:read`): For viewing registered services
- Read/write access (`admin:read`, `admin:write`): For registering and managing services

### Using the CLI with Authentication
Use `auth login` to configure your credentials:
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

### Checking Credentials
Verify your credentials using the CLI:
```bash
./aussie auth status
```
**Output:**
```
Server: http://localhost:1234
Status: Authenticated
Key ID: abc123
Name:   my-api-key
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
The CLI uses configuration files to store settings like the server URL and API key.

**Configuration locations (in order of precedence):**
1. Local `.aussierc` file in the current directory
2. Global `~/.aussie` file in your home directory
3. Default values

**Configuration format (TOML):**
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
./aussie register -f my-service.json
```
To use a different Aussie server:
```bash
./aussie register -f my-service.json -s http://aussie.example.com:8080
```

### Managing Services
```bash
# List all registered services
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
| `PRIVATE` | Restricted to configured IP addresses, domains, or subdomains |

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
    "allowedIps": ["10.0.0.0/8"],
    "allowedDomains": ["admin.internal.example.com"]
  }
}
```

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
./aussie register -f user-service.json

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
./aussie register -f user-service.json

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

## CLI Reference

### Authentication Commands

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
Server: http://localhost:1234
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
| Flag | Short | Default | Description |
|------|-------|---------|-------------|
| `--name` | `-n` | (required) | Name for the API key |
| `--description` | `-d` | | Description of the key's purpose |
| `--ttl` | `-t` | 0 | TTL in days (0 = no expiration) |
| `--permissions` | `-p` | `*` | Permissions (comma-separated) |

#### `keys list`
List all API keys.
```bash
./aussie keys list
```

#### `keys revoke`
Revoke an API key by its ID.
```bash
./aussie keys revoke <key-id>
```

### Service Commands

#### `register`
Register a service with the gateway.
```bash
# Register from a JSON file
./aussie register -f my-service.json

# Register with a specific server
./aussie register -f my-service.json -s https://aussie.example.com
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
- Visibility rules and endpoint configurations
- Optional fields like `routePrefix`, `defaultVisibility`, and `accessConfig`

#### `service list`
List all registered services.
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
| `auth login` | Configure API key credentials |
| `auth logout` | Remove stored credentials |
| `auth status` | Show current authentication status |
| `keys create` | Create a new API key |
| `keys list` | List all API keys |
| `keys revoke <id>` | Revoke an API key |
| `register -f <file>` | Register a service |
| `service validate -f <file>` | Validate a service configuration |
| `service list` | List all registered services |
| `service preview <id>` | Preview service visibility settings |

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
