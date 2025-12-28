# OIDC Token Exchange

The OIDC Token Exchange feature enables Aussie to complete the OAuth 2.0 authorization code flow by exchanging authorization codes for tokens with identity providers.

## Overview

When a user authenticates with an identity provider (IdP), Aussie can:
1. Receive the authorization code via the `/auth/oidc/token` endpoint
2. Exchange the code for tokens with the IdP
3. Optionally create an Aussie session from the ID token
4. Store refresh tokens for automatic renewal

This feature works in conjunction with [PKCE](pkce.md) for secure authorization flows.

## Configuration

### Enable Token Exchange

Token exchange is **disabled by default**. Enable it with:

```properties
aussie.auth.oidc.token-exchange.enabled=true
```

### Required Settings

Configure your IdP's token endpoint and OAuth2 client credentials:

```properties
# IdP token endpoint
aussie.auth.oidc.token-exchange.token-endpoint=https://auth.example.com/oauth/token

# OAuth2 client credentials
aussie.auth.oidc.token-exchange.client-id=${OIDC_CLIENT_ID}
aussie.auth.oidc.token-exchange.client-secret=${OIDC_CLIENT_SECRET}
```

### Client Authentication

Aussie supports two client authentication methods:

| Method | Description |
|--------|-------------|
| `client_secret_basic` | HTTP Basic auth (recommended, default) |
| `client_secret_post` | Credentials in request body |

```properties
aussie.auth.oidc.token-exchange.client-auth-method=client_secret_basic
```

### Session Integration

When enabled, Aussie creates a session from the ID token claims:

```properties
# Create session after successful token exchange (default: true)
aussie.auth.oidc.token-exchange.create-session=true
```

Session creation requires:
- Session management enabled (`aussie.session.enabled=true`)
- An ID token in the token response

### Refresh Token Storage

Refresh tokens can be stored in Redis for automatic renewal:

```properties
# Store refresh tokens (default: true)
aussie.auth.oidc.token-exchange.refresh-token.store=true

# Default TTL when token doesn't specify expiry (default: 7 days)
aussie.auth.oidc.token-exchange.refresh-token.default-ttl=PT168H

# Redis key prefix
aussie.auth.oidc.token-exchange.refresh-token.key-prefix=aussie:oidc:refresh:
```

### Timeouts

```properties
# HTTP timeout for token exchange requests (default: 10 seconds)
aussie.auth.oidc.token-exchange.timeout=PT10S
```

### Scopes

Configure the scopes to request:

```properties
aussie.auth.oidc.token-exchange.scopes=openid,profile,email
```

## Complete Example

```properties
# Enable OIDC token exchange
aussie.auth.oidc.token-exchange.enabled=true

# IdP configuration
aussie.auth.oidc.token-exchange.token-endpoint=https://auth.example.com/oauth/token
aussie.auth.oidc.token-exchange.client-id=${OIDC_CLIENT_ID}
aussie.auth.oidc.token-exchange.client-secret=${OIDC_CLIENT_SECRET}
aussie.auth.oidc.token-exchange.client-auth-method=client_secret_basic

# Request openid and profile scopes
aussie.auth.oidc.token-exchange.scopes=openid,profile,email

# Create Aussie session from ID token
aussie.auth.oidc.token-exchange.create-session=true

# Store refresh tokens
aussie.auth.oidc.token-exchange.refresh-token.store=true
aussie.auth.oidc.token-exchange.refresh-token.default-ttl=PT168H
```

## Custom Token Exchange Providers

For IdPs with non-standard token endpoints, you can implement a custom provider using the SPI.

### SPI Interface

```java
public interface OidcTokenExchangeProvider {
    String name();
    default int priority() { return 0; }
    default boolean isAvailable() { return true; }
    default Optional<HealthCheckResponse> healthCheck() { return Optional.empty(); }
    Uni<OidcTokenExchangeResponse> exchange(OidcTokenExchangeRequest request);
}
```

### Example Implementation

```java
@ApplicationScoped
public class CustomIdpTokenExchangeProvider implements OidcTokenExchangeProvider {

    @Override
    public String name() {
        return "custom-idp";
    }

    @Override
    public int priority() {
        return 200; // Higher than default (100)
    }

    @Override
    public boolean isAvailable() {
        // Return true when this provider should be used
        return true;
    }

    @Override
    public Uni<OidcTokenExchangeResponse> exchange(OidcTokenExchangeRequest request) {
        // Implement custom token exchange logic
        // ...
    }
}
```

### Selecting a Provider

Configure which provider to use:

```properties
aussie.auth.oidc.token-exchange.provider=custom-idp
```

If the configured provider is unavailable, Aussie falls back to the highest-priority available provider.

## API Response

The `/auth/oidc/token` endpoint returns:

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiI...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "id_token": "eyJhbGciOiJSUzI1NiI...",
  "scope": "openid profile email",
  "session_id": "abc123..."
}
```

Notes:
- `session_id` is included only when session creation is enabled
- `refresh_token` is **not** returned (stored server-side)

## Security Considerations

1. **Client Secrets**: Always use environment variables for secrets
2. **PKCE**: Token exchange requires PKCE when enabled
3. **TLS**: All IdP communication uses HTTPS
4. **Refresh Tokens**: Stored server-side, never exposed to clients

## Troubleshooting

### Token exchange fails with "feature disabled"

Enable token exchange:
```properties
aussie.auth.oidc.token-exchange.enabled=true
```

### Token exchange fails with "token endpoint not configured"

Configure the IdP token endpoint:
```properties
aussie.auth.oidc.token-exchange.token-endpoint=https://...
```

### PKCE verification fails

Ensure:
1. PKCE is enabled and the challenge was stored
2. The `code_verifier` matches the original `code_challenge`
3. The state parameter is valid and not expired

### Session not created

Check:
1. Session management is enabled: `aussie.session.enabled=true`
2. Session creation is enabled: `aussie.auth.oidc.token-exchange.create-session=true`
3. The IdP returns an ID token
