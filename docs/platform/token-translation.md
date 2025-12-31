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
