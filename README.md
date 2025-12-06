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

> **Important:** Save the `key` value immediately. It is only shown once and cannot be retrieved later.

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

The CLI supports authentication via environment variable or command flag:

```bash
# Set your API key as an environment variable
export AUSSIE_API_KEY="aussie_xxxxxxxxxxxx"

# Register a service (uses AUSSIE_API_KEY)
./aussie register -f my-service.json

# Or pass the key directly
./aussie register -f my-service.json --api-key aussie_xxxxxxxxxxxx
```

#### Obtaining an API Key

Contact your platform team to obtain an API key with appropriate permissions:
- **Read-only access**: For viewing registered services
- **Read/write access**: For registering and managing services

### Bootstrap Mode (First-Time Setup)

When deploying Aussie for the first time, you need an admin API key to access the admin endpoints, but you can't create one without authentication - a classic chicken-and-egg problem. Bootstrap mode solves this.

#### Enabling Bootstrap

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

#### Using the Bootstrap Key

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

#### Recovery Mode

If you've lost all admin keys, you can use recovery mode to create a new bootstrap key even when admin keys exist:

```bash
# WARNING: Use only for emergency recovery
export AUSSIE_BOOTSTRAP_ENABLED=true
export AUSSIE_BOOTSTRAP_KEY="new-secure-recovery-key-at-least-32-chars"
export AUSSIE_BOOTSTRAP_RECOVERY_MODE=true
```

Recovery mode is logged with a security warning - review your system if you didn't initiate this.

#### Security Considerations

| Practice | Description |
|----------|-------------|
| **Use a strong key** | Minimum 32 characters, randomly generated |
| **Short-lived keys** | Bootstrap keys expire in ≤24 hours by design |
| **Immediate rotation** | Create a permanent key and disable bootstrap immediately |
| **Audit logs** | All bootstrap operations are logged to `aussie.audit.bootstrap` |
| **Recovery mode caution** | Only use when absolutely necessary |

#### Configuration Reference

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `aussie.bootstrap.enabled` | `AUSSIE_BOOTSTRAP_ENABLED` | `false` | Enable bootstrap mode |
| `aussie.bootstrap.key` | `AUSSIE_BOOTSTRAP_KEY` | - | Bootstrap key (min 32 chars) |
| `aussie.bootstrap.ttl` | `AUSSIE_BOOTSTRAP_TTL` | `PT24H` | Bootstrap key TTL (max: 24h) |
| `aussie.bootstrap.recovery-mode` | `AUSSIE_BOOTSTRAP_RECOVERY_MODE` | `false` | Allow bootstrap with existing keys |

### Custom Authentication Providers

Platform teams can integrate custom authentication systems (OAuth, SAML, custom headers, etc.) by implementing the `HttpAuthenticationMechanism` interface from Quarkus Security.

#### Architecture Overview

Aussie uses Quarkus Security for authentication with `@RolesAllowed` annotations for authorization:

```
┌─────────────────────────────────────────────────────────────────┐
│  Incoming Request                                               │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  HttpAuthenticationMechanism                                     │
│  - Extracts credentials from request                            │
│  - Creates AuthenticationRequest                                │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  IdentityProvider                                               │
│  - Validates credentials                                        │
│  - Maps permissions to Quarkus Security roles                   │
│  - Returns SecurityIdentity                                     │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  @RolesAllowed Enforcement                                      │
│  - Checks SecurityIdentity roles                                │
│  - Returns 403 Forbidden if insufficient permissions            │
└─────────────────────────────────────────────────────────────────┘
```

#### Role Mapping

Permissions are mapped to Quarkus Security roles:
- `admin:read` → `admin-read` role
- `admin:write` → `admin-write` role
- `*` → `admin` role (full access)

#### Creating a Custom Provider

1. **Create an Authentication Request** (credential holder):

```java
public class MyAuthRequest extends BaseAuthenticationRequest {
    private final String token;

    public MyAuthRequest(String token) {
        this.token = token;
    }

    public String getToken() { return token; }
}
```

2. **Implement the Authentication Mechanism** (extracts credentials):

```java
@ApplicationScoped
@Priority(2) // Higher priority than default API key auth
public class MyAuthMechanism implements HttpAuthenticationMechanism {

    @Override
    public Uni<SecurityIdentity> authenticate(
            RoutingContext context,
            IdentityProviderManager identityProviderManager) {

        String token = context.request().getHeader("X-My-Auth-Token");
        if (token == null) {
            return Uni.createFrom().nullItem(); // Try next mechanism
        }

        return identityProviderManager.authenticate(new MyAuthRequest(token));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(MyAuthRequest.class);
    }
}
```

3. **Implement the Identity Provider** (validates and creates identity):

```java
@ApplicationScoped
public class MyIdentityProvider implements IdentityProvider<MyAuthRequest> {

    @Inject
    PermissionRoleMapper roleMapper;

    @Override
    public Class<MyAuthRequest> getRequestType() {
        return MyAuthRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            MyAuthRequest request,
            AuthenticationRequestContext context) {

        return context.runBlocking(() -> {
            // Validate token against your auth system
            UserInfo user = validateWithMyAuthSystem(request.getToken());

            // Map permissions to roles
            Set<String> roles = roleMapper.toRoles(user.getPermissions());

            return QuarkusSecurityIdentity.builder()
                    .setPrincipal(new MyPrincipal(user.getId(), user.getName()))
                    .addRoles(roles)
                    .build();
        });
    }
}
```

#### Integration Patterns

| Auth System | Implementation Approach |
|-------------|------------------------|
| **OAuth2/OIDC** | Use `quarkus-oidc` extension or validate JWT tokens |
| **SAML** | Parse SAML assertions, map attributes to roles |
| **Custom Header** | Extract header value, validate against your service |
| **mTLS** | Extract client certificate info from request |

#### Testing Your Provider

```java
@QuarkusTest
class MyAuthProviderTest {

    @Test
    void authenticatedRequestSucceeds() {
        given()
            .header("X-My-Auth-Token", "valid-token")
        .when()
            .get("/admin/services")
        .then()
            .statusCode(200);
    }
}
```

#### Security Best Practices

1. **Always validate tokens** - Never trust client-provided data
2. **Use secure token transmission** - Require HTTPS in production
3. **Log authentication events** - Track successes and failures
4. **Set appropriate TTLs** - Configure `aussie.auth.api-keys.max-ttl`
5. **Rotate credentials** - Plan for key rotation in your implementation

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

