# Token Translation

Token translation maps claims from external Identity Provider (IdP) tokens to Aussie's internal authorization model. This allows integration with any IdP regardless of its claim structure.

## When to Use

Use token translation when your IdP:
- Uses custom claim names (e.g., `groups` instead of `roles`)
- Stores roles in nested claims (e.g., Keycloak's `realm_access.roles`)
- Uses space-delimited scopes for authorization
- Requires prefix stripping or other transformations

## Configuration

Enable token translation and select a provider:

```properties
# Enable translation (default: false)
aussie.auth.token-translation.enabled=true

# Select provider: default, config, or remote
aussie.auth.token-translation.provider=config
```

Environment variables:
```bash
AUSSIE_AUTH_TOKEN_TRANSLATION_ENABLED=true
AUSSIE_AUTH_TOKEN_TRANSLATION_PROVIDER=config
```

## Providers

### Default Provider

Extracts from standard `roles` and `permissions` claims. No additional configuration required.

### Config Provider

Uses a JSON configuration file to define claim extraction and mapping rules.

```properties
aussie.auth.token-translation.config.path=/etc/aussie/token-translation.json
```

#### Configuration Schema

```json
{
  "version": 1,
  "sources": [
    { "name": "groups", "claim": "groups", "type": "array" },
    { "name": "scopes", "claim": "scope", "type": "space-delimited" }
  ],
  "transforms": [
    { "source": "groups", "operations": [{ "type": "strip-prefix", "value": "APP_" }] }
  ],
  "mappings": {
    "roleToPermissions": {
      "admin": ["service.config.*", "apikeys.*"]
    },
    "directPermissions": {
      "billing:read": "service.permissions.read"
    }
  },
  "defaults": {
    "denyIfNoMatch": true,
    "includeUnmapped": false
  }
}
```

#### Sources

Define where to extract values from token claims:

| Field | Description |
|-------|-------------|
| `name` | Identifier for this source (used in transforms) |
| `claim` | Claim path, supports dot notation (e.g., `realm_access.roles`) |
| `type` | One of: `array`, `space-delimited`, `comma-delimited`, `single` |

#### Transforms

Apply transformations to extracted values:

| Type | Description | Example |
|------|-------------|---------|
| `strip-prefix` | Remove prefix from values | `{ "type": "strip-prefix", "value": "ROLE_" }` |
| `replace` | Replace substring | `{ "type": "replace", "from": "_", "to": "-" }` |
| `lowercase` | Convert to lowercase | `{ "type": "lowercase" }` |
| `uppercase` | Convert to uppercase | `{ "type": "uppercase" }` |
| `regex` | Regex replacement | `{ "type": "regex", "pattern": "^app_(.*)", "replacement": "$1" }` |

#### IdP Examples

**Keycloak:**
```json
{
  "version": 1,
  "sources": [
    { "name": "roles", "claim": "realm_access.roles", "type": "array" }
  ],
  "mappings": {
    "roleToPermissions": {
      "admin": ["*"]
    }
  }
}
```

**Auth0:**
```json
{
  "version": 1,
  "sources": [
    { "name": "permissions", "claim": "permissions", "type": "array" },
    { "name": "roles", "claim": "https://myapp.com/roles", "type": "array" }
  ],
  "mappings": {
    "roleToPermissions": {
      "admin": ["service.config.*", "apikeys.*"]
    }
  }
}
```

**Azure AD:**
```json
{
  "version": 1,
  "sources": [
    { "name": "groups", "claim": "groups", "type": "array" },
    { "name": "roles", "claim": "roles", "type": "array" }
  ],
  "transforms": [
    { "source": "groups", "operations": [{ "type": "lowercase" }] }
  ],
  "mappings": {
    "roleToPermissions": {
      "admins": ["*"]
    }
  }
}
```

### Remote Provider

Delegates translation to an external HTTP service.

```properties
aussie.auth.token-translation.remote.url=https://auth-service.internal/translate
aussie.auth.token-translation.remote.timeout=PT0.1S
aussie.auth.token-translation.remote.fail-mode=deny
```

#### Request Format

```http
POST /translate
Content-Type: application/json

{
  "issuer": "https://auth.example.com",
  "subject": "user-123",
  "claims": { ... }
}
```

#### Response Format

```json
{
  "roles": ["admin", "user"],
  "permissions": ["read", "write"]
}
```

#### Fail Modes

| Mode | Description |
|------|-------------|
| `deny` | Reject authentication if translation fails (default) |
| `allow_empty` | Allow with empty roles/permissions if translation fails |

## Caching

Translation results are cached to avoid repeated processing:

```properties
# Cache TTL in seconds (default: 300)
aussie.auth.token-translation.cache.ttl-seconds=300

# Maximum cache entries (default: 10000)
aussie.auth.token-translation.cache.max-size=10000
```

## Custom Providers

Implement the `TokenTranslatorProvider` SPI for custom translation logic:

```java
@ApplicationScoped
public class MyTranslatorProvider implements TokenTranslatorProvider {

    @Override
    public String name() {
        return "my-provider";
    }

    @Override
    public int priority() {
        return 75; // Higher priority than default (100)
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Uni<TranslatedClaims> translate(String issuer, String subject, Map<String, Object> claims) {
        Set<String> roles = extractRoles(claims);
        Set<String> permissions = extractPermissions(claims);
        return Uni.createFrom().item(new TranslatedClaims(roles, permissions, Map.of()));
    }
}
```

Set the provider name in configuration:
```properties
aussie.auth.token-translation.provider=my-provider
```

## Dynamic Configuration Management

For production environments, use the API or CLI to manage translation configurations with version history and rollback support.

### REST API

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

#### Upload Config

```bash
curl -X POST http://localhost:1234/admin/translation-config \
  -H "Content-Type: application/json" \
  -d '{
    "config": { ... },
    "comment": "Added admin role mapping",
    "activate": true
  }'
```

#### Test Translation

```bash
curl -X POST http://localhost:1234/admin/translation-config/test \
  -H "Content-Type: application/json" \
  -d '{
    "claims": { "realm_access": { "roles": ["admin"] } },
    "issuer": "https://auth.example.com",
    "subject": "user-123"
  }'
```

### CLI Commands

```bash
# Upload a configuration
aussie translation-config upload config.json --comment "Initial setup"

# Upload and activate
aussie translation-config upload config.json --comment "Fix mapping"

# Upload without activating
aussie translation-config upload config.json --no-activate

# List all versions
aussie translation-config list

# Get active config
aussie translation-config get

# Get specific version
aussie translation-config get <version-id>

# Validate config file
aussie translation-config validate config.json

# Test translation
aussie translation-config test --claims '{"roles": ["admin"]}'
aussie translation-config test --claims-file sample-claims.json

# Activate a version
aussie translation-config activate <version-id>

# Rollback to version number
aussie translation-config rollback <version-number>

# Delete a version
aussie translation-config delete <version-id>
```

### Required Permissions

| Operation | Permission |
|-----------|------------|
| Read (list, get, validate, test) | `translation.config.read` or `admin` |
| Write (upload, activate, delete) | `translation.config.write` or `admin` |

## Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_TOKEN_TRANSLATION_ENABLED` | `false` | Enable token translation |
| `AUSSIE_AUTH_TOKEN_TRANSLATION_PROVIDER` | `default` | Provider name |
| `AUSSIE_AUTH_TOKEN_TRANSLATION_CONFIG_PATH` | - | Path to config file |
| `AUSSIE_AUTH_TOKEN_TRANSLATION_REMOTE_URL` | - | Remote service URL |
| `AUSSIE_AUTH_TOKEN_TRANSLATION_REMOTE_TIMEOUT` | `PT0.1S` | Remote request timeout |
| `AUSSIE_AUTH_TOKEN_TRANSLATION_REMOTE_FAIL_MODE` | `deny` | Remote failure behavior |
| `AUSSIE_AUTH_TOKEN_TRANSLATION_CACHE_TTL_SECONDS` | `300` | Cache TTL |
| `AUSSIE_AUTH_TOKEN_TRANSLATION_CACHE_MAX_SIZE` | `10000` | Max cache entries |

## Observability

Token translation provides comprehensive observability through metrics, structured logging, and introspection endpoints.

### Metrics

The following Prometheus metrics are exposed when telemetry is enabled:

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie_token_translation_total` | Counter | `provider`, `outcome` | Total translation operations |
| `aussie_token_translation_duration_seconds` | Histogram | `provider`, `outcome` | Translation latency |
| `aussie_token_translation_cache_hits_total` | Counter | - | Cache hit count |
| `aussie_token_translation_cache_misses_total` | Counter | - | Cache miss count |
| `aussie_token_translation_cache_size` | Gauge | - | Current cache size |
| `aussie_token_translation_errors_total` | Counter | `provider`, `error_type` | Translation errors |
| `aussie_token_translation_remote_total` | Counter | `status`, `status_class` | Remote service calls |
| `aussie_token_translation_remote_duration_seconds` | Histogram | `status_class` | Remote service latency |
| `aussie_token_translation_config_reloads_total` | Counter | `success` | Config reload events |

**Outcome values:** `success`, `error`, `fallback`, `empty`

Example Prometheus queries:

```promql
# Cache hit rate
sum(rate(aussie_token_translation_cache_hits_total[5m])) /
(sum(rate(aussie_token_translation_cache_hits_total[5m])) +
 sum(rate(aussie_token_translation_cache_misses_total[5m])))

# Translation latency p99
histogram_quantile(0.99, rate(aussie_token_translation_duration_seconds_bucket[5m]))

# Error rate by provider
rate(aussie_token_translation_errors_total[5m])
```

### Logging

Token translation logs structured information at various levels:

| Level | Events |
|-------|--------|
| INFO | Service initialization, config load/reload, cache invalidation |
| DEBUG | Cache hits/misses, translation results, remote calls |
| WARN | Translation failures, remote errors, missing config |
| ERROR | Config load failures, critical errors |

Example log output:
```
INFO  Token translation service initialized: enabled=true, provider=config, cache.ttl=300s, cache.maxSize=10000
DEBUG Translation cache miss: subject=user-123, provider=config
DEBUG Translation complete: issuer=https://auth.example.com, subject=user-123, provider=config, roles=[admin], permissions=[service.config.*], duration=2ms
```

### Introspection Endpoints

#### Get Service Status

```bash
GET /admin/translation-config/status
```

Returns current service status including provider health and cache statistics:

```json
{
  "enabled": true,
  "activeProvider": "config",
  "providerHealthy": true,
  "cache": {
    "currentSize": 1234,
    "maxSize": 10000,
    "ttlSeconds": 300
  }
}
```

CLI equivalent:
```bash
aussie translation-config status
```

#### Invalidate Cache

```bash
POST /admin/translation-config/cache/invalidate
```

Forces re-translation for all subsequent requests. Returns `204 No Content`.

CLI equivalent:
```bash
aussie translation-config cache-invalidate
```

### Health Checks

Token translation providers participate in the health check system. The config and remote providers report their availability status.

Example health check response:
```json
{
  "status": "UP",
  "checks": [
    {
      "name": "token-translator-config",
      "status": "UP",
      "data": {
        "provider": "config",
        "configVersion": 1,
        "sourcesCount": 2,
        "transformsCount": 1
      }
    }
  ]
}
```

## Troubleshooting

### Translation Not Working

1. Verify translation is enabled:
   ```bash
   aussie translation-config status
   ```

2. Check provider health in logs or health endpoint

3. Test translation with sample claims:
   ```bash
   aussie translation-config test --claims '{"realm_access": {"roles": ["admin"]}}'
   ```

### Cache Issues

1. Check cache status:
   ```bash
   aussie translation-config status
   ```

2. Invalidate cache after config changes:
   ```bash
   aussie translation-config cache-invalidate
   ```

### Remote Provider Timeout

1. Check remote service latency in metrics:
   ```promql
   histogram_quantile(0.99, rate(aussie_token_translation_remote_duration_seconds_bucket[5m]))
   ```

2. Increase timeout if needed:
   ```properties
   aussie.auth.token-translation.remote.timeout=PT0.5S
   ```

3. Consider using `allow_empty` fail mode for graceful degradation:
   ```properties
   aussie.auth.token-translation.remote.fail-mode=allow_empty
   ```
