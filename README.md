# Aussie API Gateway

<p align="center">
  <img src="static/img/aussie.svg" alt="Aussie Logo" width="150">
</p>


Aussie is an experimental API Gateway built with Quarkus, designed for exploring modern gateway patterns and proxy architectures.

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

---

## Project Structure

```
aussie/
├── api/          # Quarkus REST API (Java 21)
├── cli/          # Command-line interface (Go)
└── demo/         # Demo application (Next.js)
```

## API

The core API gateway built with [Quarkus](https://quarkus.io/), the Supersonic Subatomic Java Framework.

### Running in dev mode

```shell
cd api
./gradlew quarkusDev
```

> **Note:** Quarkus Dev UI is available at http://localhost:8080/q/dev/

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

---

# Aussie API Details

Aussie is a lightweight API gateway for microservices. It provides two routing strategies to fit different architectural needs.

## Quick Start

```bash
# Start the gateway in dev mode
cd api
./gradlew quarkusDev

# Register a service
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
```

## Routing Strategies

Aussie provides two ways to route traffic to your services:

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

---

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

---

## Comparison

| Feature | Pass-Through (`/{serviceId}/...`) | Gateway (`/gateway/...`) |
|---------|-----------------------------------|--------------------------|
| Service in URL | Yes | No |
| Endpoint registration required | No | Yes |
| Path pattern matching | No | Yes |
| Path rewriting | No | Yes |
| Multiple services, one namespace | No | Yes |
| Setup complexity | Low | Medium |

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

- **Exact**: `/api/users` - matches only `/api/users`
- **Variables**: `/api/users/{id}` - matches `/api/users/123`, captures `id=123`
- **Single wildcard**: `/api/*/info` - matches `/api/users/info`, `/api/orders/info`
- **Multi wildcard**: `/api/**` - matches `/api/anything/here/deeply/nested`

### Visibility

- **PUBLIC**: Accessible from any source
- **PRIVATE**: Restricted by `accessConfig` (IP/domain allowlists)

## Admin API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/services` | GET | List all registered services |
| `/admin/services` | POST | Register a new service |
| `/admin/services/{id}` | GET | Get a specific service |
| `/admin/services/{id}` | DELETE | Unregister a service |

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

