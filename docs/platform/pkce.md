# PKCE Support for OIDC Flows

This document describes Aussie's PKCE (Proof Key for Code Exchange) support for protecting authorization code flows against interception attacks.

## Overview

PKCE (RFC 7636) is a security extension to the OAuth 2.0 authorization code flow that protects against authorization code interception attacks. The browser-facing Aussie helpers use it for:

- Single Page Applications (SPAs)
- Browser applications that cannot securely store a client secret

Aussie requires PKCE with the S256 challenge method for all OIDC authorization flows by default.

The public OIDC helpers remain disabled by default through `aussie.auth.oidc.public-endpoints-enabled=false`. When enabled, the PKCE challenge is stored with the provider, exact redirect URI, nonce, client type, expiry, and initiating browser in one atomically consumed authorization transaction.

## Configuration

### Application Properties

```properties
# Enable/disable PKCE support (default: true)
aussie.auth.pkce.enabled=true

# Challenge TTL - how long a PKCE challenge remains valid (default: 10 minutes)
aussie.auth.pkce.challenge-ttl=PT10M

# Storage provider: redis (production) or memory (dev/test)
aussie.auth.pkce.storage.provider=redis
aussie.auth.pkce.storage.redis.key-prefix=aussie:pkce:
```

### Development Profile

For development, in-memory storage is used by default:

```properties
%dev.aussie.auth.pkce.storage.provider=memory
```

## Authorization Flow with PKCE

### 1. Client Generates PKCE Parameters

The client (SPA, mobile app) generates:

```javascript
// Generate code verifier (43-128 random characters)
const codeVerifier = generateRandomString(64);

// Generate code challenge: BASE64URL(SHA256(verifier))
const codeChallenge = base64url(sha256(codeVerifier));
```

### 2. Authorization Request

Client initiates authorization by redirecting to Aussie:

```
GET /auth/oidc/authorize
  ?redirect_uri=https://app.example.com/callback
  &code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
  &code_challenge_method=S256
```

### 3. Aussie Stores the Transaction

Aussie stores the complete one-time authorization transaction in Redis (keyed by a generated state parameter), sets an HttpOnly initiation cookie, and redirects to the server-configured IdP with a generated nonce. Caller-supplied state is rejected.

### 4. User Authenticates

The user authenticates with the IdP and is redirected to the registered client redirect URI with an authorization code and Aussie's state value.

### 5. Token Exchange with Verifier

The client exchanges the authorization code for tokens, providing the code verifier:

```
POST /auth/oidc/token
Content-Type: application/x-www-form-urlencoded

code=authorization-code-from-idp
&code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
&state=state-from-authorize
&redirect_uri=https://app.example.com/callback
```

The exchange must come from the same browser and include the initiation cookie. Cross-site browser clients must use HTTPS, set `aussie.session.cookie.same-site=None`, enable credentialed CORS for the exact client origin, and send a credentialed request.

### 6. Aussie Verifies PKCE

Aussie:
1. Atomically consumes the stored transaction
2. Computes `BASE64URL(SHA256(code_verifier))`
3. Verifies the PKCE challenge and exact redirect URI
4. Exchanges with the stored provider configuration
5. Validates the returned ID token signature, provider, audience, authorized party, nonce, and time claims

## Manual Test

Configure the authorization endpoint and exact redirect URI before starting the flow. The caller cannot override the IdP URL.

### Start the Environment

```bash
# Start Aussie and the configured identity provider
make dev
```

### Test PKCE Flow

1. **Generate PKCE parameters** (in browser console or using a tool):

```javascript
// Generate code verifier
const array = new Uint8Array(64);
crypto.getRandomValues(array);
const codeVerifier = btoa(String.fromCharCode(...array))
  .replace(/\+/g, '-')
  .replace(/\//g, '_')
  .replace(/=/g, '');

// Generate code challenge
const encoder = new TextEncoder();
const data = encoder.encode(codeVerifier);
const digest = await crypto.subtle.digest('SHA-256', data);
const codeChallenge = btoa(String.fromCharCode(...new Uint8Array(digest)))
  .replace(/\+/g, '-')
  .replace(/\//g, '_')
  .replace(/=/g, '');

console.log('Code Verifier:', codeVerifier);
console.log('Code Challenge:', codeChallenge);
```

2. **Start authorization flow**:

```bash
# Redirect to Aussie's OIDC authorize endpoint
curl -c oidc-cookies.txt -v "http://localhost:1234/auth/oidc/authorize?\
redirect_uri=http://localhost:3000/callback\
&code_challenge=${CODE_CHALLENGE}\
&code_challenge_method=S256"
```

3. **Complete authentication** in the browser at the demo app login page.

4. **Exchange code for tokens**:

```bash
curl -b oidc-cookies.txt -X POST "http://localhost:1234/auth/oidc/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "code=${AUTHORIZATION_CODE}" \
  -d "code_verifier=${CODE_VERIFIER}" \
  -d "state=${STATE}" \
  -d "redirect_uri=http://localhost:3000/callback"
```

## Demo App OIDC Endpoints

The demo app implements a complete OAuth 2.0 / OIDC provider for testing:

| Endpoint | Purpose |
|----------|---------|
| `GET /api/auth/oidc/authorize` | Authorization endpoint - redirects to login |
| `POST /api/auth/oidc/token` | Token endpoint - exchanges code for tokens |
| `POST /api/auth/oidc/callback` | Internal - generates auth code after login |
| `GET /.well-known/jwks.json` | JWKS endpoint for token validation |

## Error Responses

### Missing PKCE Challenge

```json
{
  "error": "pkce_required",
  "error_description": "PKCE with S256 challenge method is required"
}
```

### Invalid Challenge Method

```json
{
  "error": "invalid_request",
  "error_description": "Only S256 challenge method is supported"
}
```

### PKCE Verification Failed

```json
{
  "error": "invalid_grant",
  "error_description": "PKCE verification failed"
}
```

## Security Considerations

1. **Only S256 is supported** - The plain method provides no security and is rejected.

2. **One-time use** - Challenges are consumed on verification to prevent replay attacks.

3. **Short TTL** - Challenges expire after 10 minutes (configurable).

4. **Atomic operations** - Redis GETDEL ensures atomic retrieve-and-delete.

5. **Browser binding** - A short-lived HttpOnly cookie binds token exchange to the browser that initiated authorization.

## SPI: Custom Storage Provider

Platform teams can implement custom PKCE challenge storage by:

1. Implementing `PkceChallengeRepository`:

```java
public interface PkceChallengeRepository {
    Uni<Void> store(String state, String challenge, Duration ttl);
    Uni<Optional<String>> consumeChallenge(String state);
}
```

2. Creating a CDI producer or using the `aussie.auth.pkce.storage.provider` configuration.

See `RedisPkceChallengeRepository` and `InMemoryPkceChallengeRepository` for reference implementations.
