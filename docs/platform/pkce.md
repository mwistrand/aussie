# PKCE Support for OIDC Flows

This document describes Aussie's PKCE (Proof Key for Code Exchange) support for protecting authorization code flows against interception attacks.

## Overview

PKCE (RFC 7636) is a security extension to the OAuth 2.0 authorization code flow that protects against authorization code interception attacks. It's particularly important for:

- Single Page Applications (SPAs)
- Mobile applications
- Any client that cannot securely store a client secret

Aussie requires PKCE with the S256 challenge method for all OIDC authorization flows by default.

## Configuration

### Application Properties

```properties
# Enable/disable PKCE support (default: true)
aussie.auth.pkce.enabled=true

# Require PKCE for all authorization requests (default: true)
# When false, PKCE is optional but recommended
aussie.auth.pkce.required=true

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
  &idp_url=https://idp.example.com/authorize
  &code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
  &code_challenge_method=S256
  &state=optional-client-state
```

### 3. Aussie Stores Challenge

Aussie stores the code challenge in Redis (keyed by a generated state parameter) and redirects to the IdP.

### 4. User Authenticates

The user authenticates with the IdP and is redirected back to Aussie with an authorization code.

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

### 6. Aussie Verifies PKCE

Aussie:
1. Retrieves the stored code challenge (one-time use)
2. Computes `BASE64URL(SHA256(code_verifier))`
3. Compares against the stored challenge
4. If valid, completes the token exchange

## Testing with the Demo App

The demo app includes a full OIDC PKCE flow for testing.

### Start the Environment

```bash
# Start Aussie and the demo app
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
curl -v "http://localhost:8080/auth/oidc/authorize?\
redirect_uri=http://localhost:8080/\
&idp_url=http://localhost:3000/api/auth/oidc/authorize\
&code_challenge=${CODE_CHALLENGE}\
&code_challenge_method=S256"
```

3. **Complete authentication** in the browser at the demo app login page.

4. **Exchange code for tokens**:

```bash
curl -X POST "http://localhost:8080/auth/oidc/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "code=${AUTHORIZATION_CODE}" \
  -d "code_verifier=${CODE_VERIFIER}" \
  -d "state=${STATE}" \
  -d "redirect_uri=http://localhost:8080/"
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
