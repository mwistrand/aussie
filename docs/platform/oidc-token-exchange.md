# OIDC Token Exchange

The OIDC Token Exchange feature enables Aussie to complete the OAuth 2.0 authorization code flow by exchanging authorization codes for tokens with identity providers.

> **Containment status:** The browser-facing helpers remain disabled by default. Their authorization transactions bind a server-configured provider, exact redirect URI, PKCE challenge, nonce, client type, and expiry in one atomically consumed record. Session mode completes with an HttpOnly cookie; the remaining authentication-surface work is still pending.

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
aussie.auth.oidc.public-endpoints-enabled=true
aussie.auth.oidc.token-exchange.enabled=true
```

### Required Settings

Configure the provider and redirect allowlist server-side; callers cannot supply an IdP URL or an unregistered redirect:

```properties
aussie.auth.oidc.token-exchange.provider-id=company-idp
aussie.auth.oidc.token-exchange.authorization-endpoint=https://auth.example.com/oauth/authorize
aussie.auth.oidc.token-exchange.token-endpoint=https://auth.example.com/oauth/token
aussie.auth.oidc.token-exchange.redirect-uris=https://app.example.com/callback

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

Every successful OIDC exchange requires a validated ID token. Session creation additionally requires:
- Session management enabled (`aussie.session.enabled=true`)
- An ID token in the token response
- A matching provider under `aussie.auth.route-auth.providers.*`
- Route-auth token validation enabled (`aussie.auth.route-auth.enabled=true`)

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

### Token Validation

Configure the same provider ID under route authentication so returned ID tokens are cryptographically validated and bound to the transaction:

```properties
aussie.auth.route-auth.enabled=true
aussie.auth.route-auth.providers.company-idp.issuer=https://auth.example.com
aussie.auth.route-auth.providers.company-idp.jwks-uri=https://auth.example.com/.well-known/jwks.json
aussie.auth.route-auth.providers.company-idp.audiences=aussie-gateway
```

### Scopes

Configure the scopes to request:

```properties
aussie.auth.oidc.token-exchange.scopes=openid,profile,email
```

## Complete Example

```properties
# Enable OIDC token exchange
aussie.auth.oidc.public-endpoints-enabled=true
aussie.auth.oidc.token-exchange.enabled=true

# IdP configuration
aussie.auth.oidc.token-exchange.provider-id=company-idp
aussie.auth.oidc.token-exchange.authorization-endpoint=https://auth.example.com/oauth/authorize
aussie.auth.oidc.token-exchange.token-endpoint=https://auth.example.com/oauth/token
aussie.auth.oidc.token-exchange.redirect-uris=https://app.example.com/callback
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

## API Responses

With `create-session=false`, `/auth/oidc/token` returns the validated public-client tokens:

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiI...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "id_token": "eyJhbGciOiJSUzI1NiI...",
  "scope": "openid profile email"
}
```

With `create-session=true`, it returns `204 No Content` and sets the configured session cookie. Access, ID, refresh, and session tokens are not exposed to browser JavaScript.

## Security Considerations

1. **Client Secrets**: Always use environment variables for secrets
2. **PKCE**: Token exchange requires PKCE when enabled
3. **TLS**: Use HTTPS for IdP endpoints outside local development
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
4. Route-auth validation is enabled with a provider matching the ID token issuer and audience
