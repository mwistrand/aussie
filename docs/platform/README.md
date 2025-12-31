# Aussie Platform Guide

This guide is for platform teams deploying and operating the Aussie API Gateway.

## Table of Contents
- [Setup](#setup)
- [Authentication Configuration](#authentication-configuration)
- [Bootstrap Mode](#bootstrap-mode-first-time-setup)
- [IdP Integration (RBAC)](#idp-integration-rbac)
- [Group-Based Access Control](#group-based-access-control)
- [Access Control](#access-control)
- [Request Forwarding](#request-forwarding)
- [Request Size Limits](#request-size-limits)
- [Per-Route Authentication](#per-route-authentication)
- [WebSocket Configuration](websocket-configuration.md)
- [Token Revocation](token-revocation.md)
- [Token Translation](token-translation.md)
- [PKCE](pkce.md)
- [Admin API](#admin-api)
- [Service Permission Policies](#service-permission-policies)
- [Environment Variables Reference](#environment-variables-reference)

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
# Create a key with full admin access
./aussie keys create --name my-team-key --description "API key for My Team" --ttl 90

# Create a key with specific permissions for service-level access
./aussie keys create --name service-admin --permissions "my-service.admin" --ttl 30

# Create a key with multiple permissions
./aussie keys create --name team-lead --permissions "*,my-service.lead" --ttl 90
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
Permissions control what operations an API key can perform. They work at two levels:

**Aussie-level access** (gateway operations):
| Permission | Description |
|------------|-------------|
| `*` | Full admin access - can perform all gateway and service operations |

**Service-level access** (per-service operations):
Service-level permissions are defined by your organization and mapped to operations via each service's permission policy. For example:
| Permission | Typical Usage |
|------------|---------------|
| `my-service.admin` | Full access to my-service configuration |
| `my-service.lead` | Can update my-service configuration |
| `my-service.readonly` | Can read my-service configuration |

See [Service Permission Policies](#service-permission-policies) for details on configuring service-level access.

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
Once created, add your bootstrap key to your configuration and create a permanent admin key:

**~/.aussierc:**
```toml
host = "http://localhost:8080"
api_key = "your-secure-bootstrap-key-at-least-32-chars"
```

```bash
# Create a permanent admin key
./aussie keys create --name primary-admin --permissions "*" --ttl 365
```
Save the returned key securely - the bootstrap key will expire automatically within 24 hours. Update your `~/.aussierc` with the new permanent key.

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

## IdP Integration (RBAC)

Aussie supports Role-Based Access Control (RBAC) through integration with your organization's Identity Provider (IdP). Instead of manually distributing API keys, developers can authenticate using their organization's SSO (SAML, OIDC, etc.) and receive short-lived tokens.

### Architecture Overview

```
┌──────────┐     ┌───────────────┐     ┌──────────┐     ┌──────────┐
│   CLI    │     │  Translation  │     │  Aussie  │     │ Backend  │
│          │     │    Layer      │     │ Gateway  │     │ Services │
└────┬─────┘     └──────┬────────┘     └────┬─────┘     └────┬─────┘
     │                  │                   │                │
     │ 1. aussie login  │                   │                │
     │─────────────────>│                   │                │
     │                  │ 2. Authenticate   │                │
     │                  │    with IdP       │                │
     │ 3. JWT Token     │                   │                │
     │<─────────────────│                   │                │
     │                  │                   │                │
     │ 4. API Request + JWT                 │                │
     │─────────────────────────────────────>│                │
     │                  │                   │ 5. Expand      │
     │                  │                   │    groups to   │
     │                  │                   │    permissions │
     │                  │                   │─────────────────>
     │ 6. Response      │                   │                │
     │<─────────────────────────────────────│                │
```

### Translation Layer

Platform teams must provide a **translation layer** between the CLI and their IdP. This layer:

1. Receives authentication requests from the Aussie CLI
2. Delegates to the organization's IdP (SAML, OIDC, etc.)
3. Maps IdP claims (roles, groups) to Aussie groups
4. Returns an Aussie-compatible JWT

#### Required Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/auth/aussie/login` | GET | Initiate auth flow (redirect to IdP or return device code) |
| `/auth/aussie/login` | POST | Exchange IdP credentials for Aussie JWT |
| `/auth/aussie/logout` | POST | Invalidate session (optional) |
| `/auth/aussie/refresh` | POST | Refresh token (optional) |

#### Expected JWT Format

Your translation layer must return a JWT with this structure:

```json
{
  "sub": "user@example.com",
  "name": "User Name",
  "groups": ["demo-service.admin", "demo-service.dev"],
  "iat": 1702656000,
  "exp": 1702659600
}
```

### Authentication Flows

**Browser Flow (Default)**
1. CLI opens browser to `login_url?callback=http://127.0.0.1:PORT/callback`
2. Translation layer redirects to IdP
3. User authenticates with IdP
4. IdP redirects back to translation layer
5. Translation layer generates Aussie JWT
6. Redirects to CLI callback with `?token=JWT`

**Device Code Flow (Headless)**
1. CLI POSTs to `login_url?flow=device_code`
2. Translation layer returns device code and verification URL
3. User opens verification URL, enters code, authenticates
4. CLI polls for token
5. Translation layer returns Aussie JWT when auth completes

### Group Mapping Example

Map your IdP roles/groups to Aussie groups in your translation layer:

```javascript
// Example mapping in translation layer
const IDP_TO_AUSSIE_GROUPS = {
  'Engineering/Platform': ['platform-team'],
  'Engineering/Backend': ['demo-service.admin', 'demo-service.dev'],
  'Engineering/Frontend': ['demo-service.dev'],
  'QA': ['demo-service.dev', 'demo-service.readonly'],
};

function mapIdpGroupsToAussie(idpGroups) {
  return idpGroups
    .flatMap(g => IDP_TO_AUSSIE_GROUPS[g] || [])
    .filter((v, i, a) => a.indexOf(v) === i); // Dedupe
}
```

### Token TTL Configuration

Configure token TTL limits in `application.properties` or via environment variables:

```properties
# Maximum allowed TTL for JWT tokens (ISO-8601 duration)
aussie.auth.route-auth.jws.max-token-ttl=PT24H

# Default TTL for issued tokens
aussie.auth.route-auth.jws.token-ttl=PT5M
```

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_MAX_TOKEN_TTL` | `PT24H` | Maximum allowed JWT token TTL |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_TOKEN_TTL` | `PT5M` | Default TTL for issued tokens |

### API Key Fallback

API key authentication can be enabled as a fallback for emergencies or teams without IdP integration:

```bash
# Enable API key authentication (disabled by default)
export AUSSIE_API_KEYS_ENABLED=true

# Maximum TTL for API keys
export AUSSIE_AUTH_API_KEYS_MAX_TTL=P365D
```

**Security Note**: API keys are disabled by default. Only enable for critical teams that need fallback authentication when the IdP is unavailable.

## Group-Based Access Control

Groups provide a mapping between organizational roles (from your IdP) and Aussie permissions.

### Defining Groups

Use the Admin API to define groups:

```bash
# Create a group
curl -X POST https://aussie.example.com/admin/groups \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "demo-service.admin",
    "displayName": "Demo Service Admins",
    "permissions": ["demo-service.admin", "demo-service.lead", "demo-service.dev"]
  }'

# List groups
curl https://aussie.example.com/admin/groups \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Update a group
curl -X PUT https://aussie.example.com/admin/groups/demo-service.admin \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "permissions": ["demo-service.admin", "demo-service.lead", "demo-service.dev", "metrics.read"]
  }'

# Delete a group
curl -X DELETE https://aussie.example.com/admin/groups/demo-service.admin \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Example Groups

Groups follow a `<service-id>.<role>` naming convention. Here are example groups for a demo service:

| Group | Permissions | Description |
|-------|-------------|-------------|
| `platform-team` | `*` | Full platform admin access |
| `demo-service.admin` | `demo-service.admin`, `demo-service.lead`, `demo-service.dev` | Full service admin |
| `demo-service.lead` | `demo-service.lead`, `demo-service.dev` | Lead developer access |
| `demo-service.dev` | `demo-service.dev` | Basic developer access |
| `demo-service.readonly` | `demo-service.readonly` | Read-only access |

### How Group Expansion Works

When a user authenticates with a token containing groups, Aussie expands those groups to permissions:

```
Token claims:
  groups: ["demo-service.admin", "demo-service.dev"]

Group mappings (from database):
  demo-service.admin → ["service.config.update", "service.config.read", "service.config.delete", "service.permissions.write"]
  demo-service.dev   → ["service.config.update", "service.config.read"]

Effective permissions:
  ["service.config.update", "service.config.read", "service.config.delete", "service.permissions.write"]
```

Direct permissions in the token are merged with expanded group permissions.

### Group Management CLI Commands

Groups can be managed via the CLI in addition to the Admin API:

#### Create a group
```bash
aussie groups create --id demo-service.admin \
  --display-name "Demo Service Admins" \
  --description "Full admin access for demo-service" \
  --permissions "service.config.update,service.config.read,service.config.delete,service.permissions.write"

# Create with minimal options
aussie groups create --id demo-service.dev --permissions "service.config.update,service.config.read"
```

| Flag | Short | Required | Description |
|------|-------|----------|-------------|
| `--id` | | Yes | Unique identifier for the group |
| `--display-name` | `-d` | No | Human-readable name (defaults to id) |
| `--description` | | No | Description of the group's purpose |
| `--permissions` | `-p` | No | Comma-separated list of permissions |

#### List groups
```bash
aussie groups list
```

#### Get a group
```bash
aussie groups get demo-service.admin
```

#### Update a group
```bash
# Update permissions
aussie groups update demo-service.admin --permissions "*" # DANGEROUS

# Update display name
aussie groups update demo-service.admin --display-name "Demo Service Administrators"
```

| Flag | Short | Description |
|------|-------|-------------|
| `--display-name` | `-d` | New display name |
| `--description` | | New description |
| `--permissions` | `-p` | New set of permissions (replaces existing) |

#### Delete a group
```bash
aussie groups delete demo-service.readonly
```

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
| `aud` | Audience claim (if configured per-endpoint or via default) |
| `original_iss` | Original token issuer |
| `iat`, `exp` | Issued/expiration times |
| Forwarded claims | Configurable (email, name, groups, roles, etc.) |

### Audience Configuration
Configure audience claims to prevent cross-service token replay attacks:
```bash
# Optional: Default audience for all endpoints without explicit audience
export AUSSIE_AUTH_ROUTE_AUTH_JWS_DEFAULT_AUDIENCE=aussie-gateway

# Optional: Require audience claim in all issued tokens
# When true and no audience is configured, the serviceId is used
export AUSSIE_AUTH_ROUTE_AUTH_JWS_REQUIRE_AUDIENCE=true
```

Services can also configure per-endpoint audiences in their registration. See the [Token Audience Validation](../api/token-audience.md) guide for details.

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
./aussie service register -f my-service.json

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

### Translation Config Management
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/translation-config` | GET | List all config versions |
| `/admin/translation-config` | POST | Upload a new config version |
| `/admin/translation-config/active` | GET | Get the active config |
| `/admin/translation-config/{versionId}` | GET | Get a specific version |
| `/admin/translation-config/{versionId}/activate` | PUT | Activate a version |
| `/admin/translation-config/rollback/{versionNumber}` | POST | Rollback to version |
| `/admin/translation-config/{versionId}` | DELETE | Delete a version |
| `/admin/translation-config/validate` | POST | Validate config |
| `/admin/translation-config/test` | POST | Test translation |

**CLI equivalents:**
```bash
aussie translation-config upload config.json
aussie translation-config list
aussie translation-config get
aussie translation-config validate config.json
aussie translation-config test --claims '{"roles": ["admin"]}'
aussie translation-config activate <version-id>
aussie translation-config rollback <version-number>
aussie translation-config delete <version-id>
```

See [Token Translation](token-translation.md) for full documentation.

## Service Permission Policies

Service permission policies control which permissions are allowed to perform specific operations on a service. This enables fine-grained access control where different teams or roles can have different levels of access to each service's configuration.

### How It Works

1. **API keys have permissions** - When you create an API key with `--permissions "my-service.lead"`, that permission is available for authorization
2. **Services define permission policies** - Each service can specify which permissions are allowed for each operation
3. **Aussie checks authorization** - When a request comes in, Aussie checks if the API key's permissions match what the service allows

### Defining a Permission Policy

Include a `permissionPolicy` in your service configuration:

```json
{
  "serviceId": "my-service",
  "baseUrl": "http://my-service:3000",
  "permissionPolicy": {
    "permissions": {
      "service.config.read": {
        "anyOfPermissions": ["my-service.readonly", "my-service.lead", "my-service.admin"]
      },
      "service.config.update": {
        "anyOfPermissions": ["my-service.lead", "my-service.admin"]
      },
      "service.config.delete": {
        "anyOfPermissions": ["my-service.admin"]
      },
      "service.permissions.write": {
        "anyOfPermissions": ["my-service.admin"]
      }
    }
  }
}
```

### Available Operations

| Operation | Description |
|-----------|-------------|
| `service.config.read` | Read the service configuration |
| `service.config.write` | Update the service configuration |
| `service.config.delete` | Delete/unregister the service |
| `service.permissions.read` | Read the service's permission policy |
| `service.permissions.write` | Update the service's permission policy |

### Default Policy

Services without an explicit permission policy use the default policy, which requires the `aussie:admin` permission (granted by `*`) for all operations. This ensures new services are secure by default.

### Example: Team-Based Access

```bash
# Create keys for different roles
./aussie keys create --name ops-admin --permissions "*" --ttl 365
./aussie keys create --name team-lead --permissions "my-service.lead,other-service.lead" --ttl 90
./aussie keys create --name developer --permissions "my-service.readonly" --ttl 30
```

With the permission policy above:
- `ops-admin` can do everything (wildcard grants `aussie:admin`)
- `team-lead` can read and update my-service config, but not delete it
- `developer` can only read my-service config

## Environment Variables Reference

### Authentication & Authorization

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_ENABLED` | `true` | Enable/disable authentication |
| `AUSSIE_AUTH_ADMIN_PATHS_ONLY` | `true` | Only require auth for admin paths |
| `AUSSIE_AUTH_DANGEROUS_NOOP` | `false` | Disable authentication (NEVER use in production) |
| `AUSSIE_API_KEYS_ENABLED` | `false` | Enable API key authentication (fallback) |
| `AUSSIE_AUTH_API_KEYS_MAX_TTL` | `P365D` | Maximum TTL for API keys |

### Token Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_ROUTE_AUTH_ENABLED` | `false` | Enable per-route authentication |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_TOKEN_TTL` | `PT5M` | Default TTL for issued tokens |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_MAX_TOKEN_TTL` | `PT24H` | Maximum allowed JWT token TTL |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_ISSUER` | `aussie-gateway` | Issuer claim for JWS tokens |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_DEFAULT_AUDIENCE` | - | Default audience claim for issued tokens |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_REQUIRE_AUDIENCE` | `false` | Require audience claim in all tokens |
| `AUSSIE_JWS_SIGNING_KEY` | - | RSA signing key (base64-encoded PKCS#8 PEM) |

### Bootstrap Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_BOOTSTRAP_ENABLED` | `false` | Enable bootstrap mode |
| `AUSSIE_BOOTSTRAP_KEY` | - | Bootstrap key (min 32 chars) |
| `AUSSIE_BOOTSTRAP_TTL` | `PT24H` | Bootstrap key TTL (max: 24h) |
| `AUSSIE_BOOTSTRAP_RECOVERY_MODE` | `false` | Allow bootstrap with existing keys |

### Storage Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `CASSANDRA_CONTACT_POINTS` | `cassandra:9042` | Cassandra contact points |
| `CASSANDRA_RUN_MIGRATIONS` | `false` | Run database migrations on startup |
| `REDIS_HOSTS` | `redis://localhost:6379` | Redis connection string |

### Gateway Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_GATEWAY_FORWARDING_USE_RFC7239` | `true` | Use RFC 7239 Forwarded headers |
| `AUSSIE_GATEWAY_LIMITS_MAX_BODY_SIZE` | `10485760` | Maximum request body size (bytes) |
| `AUSSIE_GATEWAY_LIMITS_MAX_HEADER_SIZE` | `8192` | Maximum single header size (bytes) |
| `AUSSIE_GATEWAY_CORS_ENABLED` | `true` | Enable CORS support |
| `AUSSIE_GATEWAY_CORS_ALLOWED_ORIGINS` | `*` | Allowed CORS origins |

### Rate Limiting

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_RATE_LIMITING_ENABLED` | `true` | Enable rate limiting |
| `AUSSIE_RATE_LIMITING_ALGORITHM` | `BUCKET` | Algorithm: BUCKET, FIXED_WINDOW, SLIDING_WINDOW |
| `AUSSIE_RATE_LIMITING_DEFAULT_REQUESTS_PER_WINDOW` | `100` | Default requests per window |
| `AUSSIE_RATE_LIMITING_WINDOW_SECONDS` | `60` | Window duration in seconds |

### Token Revocation

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_REVOCATION_ENABLED` | `true` | Enable token revocation checks |
| `AUSSIE_AUTH_REVOCATION_CHECK_USER_REVOCATION` | `true` | Enable user-level revocation |
| `AUSSIE_AUTH_REVOCATION_CHECK_THRESHOLD` | `PT30S` | Skip check for tokens expiring within this threshold |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_ENABLED` | `true` | Enable bloom filter optimization |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_EXPECTED_INSERTIONS` | `100000` | Expected number of revoked tokens |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_FALSE_POSITIVE_PROBABILITY` | `0.001` | Bloom filter false positive rate |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_REBUILD_INTERVAL` | `PT1H` | Bloom filter rebuild interval |
| `AUSSIE_AUTH_REVOCATION_CACHE_ENABLED` | `true` | Enable local revocation cache |
| `AUSSIE_AUTH_REVOCATION_CACHE_MAX_SIZE` | `10000` | Maximum cache entries |
| `AUSSIE_AUTH_REVOCATION_CACHE_TTL` | `PT5M` | Cache entry TTL |
| `AUSSIE_AUTH_REVOCATION_PUBSUB_ENABLED` | `true` | Enable pub/sub for multi-instance sync |
| `AUSSIE_AUTH_REVOCATION_PUBSUB_CHANNEL` | `aussie:revocation:events` | Redis pub/sub channel name |

See [Token Revocation](token-revocation.md) for implementation details.

### PKCE Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_PKCE_ENABLED` | `true` | Enable PKCE support |
| `AUSSIE_AUTH_PKCE_REQUIRED` | `true` | Require PKCE for authorization requests |
| `AUSSIE_AUTH_PKCE_CHALLENGE_TTL` | `PT10M` | Challenge TTL (time-to-live) |
| `AUSSIE_AUTH_PKCE_STORAGE_PROVIDER` | `redis` | Storage provider: redis, memory |
| `AUSSIE_AUTH_PKCE_STORAGE_REDIS_KEY_PREFIX` | `aussie:pkce:` | Redis key prefix |

See [PKCE](pkce.md) for implementation details.

### Telemetry

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_TELEMETRY_ENABLED` | `false` | Master toggle for telemetry |
| `AUSSIE_TELEMETRY_TRACING_ENABLED` | `false` | Enable distributed tracing |
| `AUSSIE_TELEMETRY_METRICS_ENABLED` | `false` | Enable metrics collection |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry exporter endpoint |
