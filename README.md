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
aussie register -f my-service.json
```

To use a different Aussie server:

```bash
aussie register -f my-service.json -s http://aussie.example.com:8080
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

## CLI

A Go-based command-line interface for interacting with the Aussie API gateway.

### Building

```shell
cd cli
go build -o aussie .
```

### Commands

```shell
# Show help
./aussie --help

# Show version
./aussie version

# Register a service
./aussie register -f service.json

# Register with a specific server
./aussie register -f service.json -s http://aussie.example.com:8080

# Validate a service configuration file
./aussie service validate -f service.json

# Preview visibility settings for a registered service
./aussie service preview my-service
```

#### Service Validation

Validate your service configuration before registering:

```shell
./aussie service validate -f my-service.json
```

This checks:
- Required fields (`serviceId`, `displayName`, `baseUrl`)
- Valid URL format for `baseUrl`
- Valid service ID (alphanumeric, hyphens, underscores only)
- Valid visibility values (`PUBLIC` or `PRIVATE`)
- Valid HTTP methods in visibility rules
- Route prefix starts with `/`
- Pattern paths start with `/`

#### Service Preview

View the visibility configuration for a registered service:

```shell
./aussie service preview user-service
```

This displays:
- Service information (ID, base URL, route prefix)
- Default visibility setting
- Visibility rules with patterns and methods
- Access control configuration (allowed IPs, domains, subdomains)
- Summary of public vs private endpoints

### Configuration

The CLI loads configuration from (in order of precedence):

1. Command-line flags (`-s` / `--server`)
2. Local `.aussierc` file (in current directory)
3. Global `~/.aussie` config file

Create a config file to avoid specifying the server each time:

```toml
# .aussierc (project-local) or ~/.aussie (global)
host = "http://localhost:8080"
```

This works on all platforms (Linux, macOS, Windows).

## Demo

A Next.js application for testing and demonstrating the Aussie API gateway capabilities.

### Running

```shell
cd demo
npm install
npm run dev
```

The demo app will be available at http://localhost:3000
