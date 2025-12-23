# Token Audience Validation

This guide covers implementing audience validation in your backend service to prevent cross-service token replay attacks.

## Overview

When Aussie issues tokens for authenticated endpoints, it can include an `aud` (audience) claim that identifies the intended recipient service. Your backend should validate this claim to ensure tokens issued for other services cannot be replayed against your API.

## Configuration Options

### Per-Endpoint Audience

Configure audience at the endpoint level in your service registration:

```json
{
  "serviceId": "billing-service",
  "baseUrl": "http://billing:3000",
  "endpoints": [
    {
      "path": "/api/invoices/{id}",
      "methods": ["GET", "PUT"],
      "authRequired": true,
      "audience": "billing-service"
    }
  ]
}
```

### Platform Default Audience

Platform teams can configure a default audience for all endpoints without explicit configuration:

```bash
export AUSSIE_AUTH_ROUTE_AUTH_JWS_DEFAULT_AUDIENCE=my-platform
```

### Required Audience Mode

When enabled, all issued tokens include an audience claim. If no audience is configured, the service ID is used:

```bash
export AUSSIE_AUTH_ROUTE_AUTH_JWS_REQUIRE_AUDIENCE=true
```

### Priority Order

Audience is resolved in this order:
1. Per-endpoint audience (highest priority)
2. Platform default audience
3. Service ID (when `require-audience=true`)

## Backend Validation

### Java/Quarkus with MicroProfile JWT

```java
import org.eclipse.microprofile.jwt.JsonWebToken;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

@Path("/api/invoices")
public class InvoiceResource {

    private static final String EXPECTED_AUDIENCE = "billing-service";

    @Inject
    JsonWebToken jwt;

    @GET
    @Path("/{id}")
    public Response getInvoice(@PathParam("id") String id) {
        if (!jwt.getAudience().contains(EXPECTED_AUDIENCE)) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("Token not intended for this service")
                .build();
        }
        // Process request...
    }
}
```

### Java/Spring Boot

```java
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private static final String EXPECTED_AUDIENCE = "billing-service";

    @GetMapping("/{id}")
    public ResponseEntity<?> getInvoice(
            @PathParam("id") String id,
            @AuthenticationPrincipal Jwt jwt) {

        if (!jwt.getAudience().contains(EXPECTED_AUDIENCE)) {
            return ResponseEntity.status(403)
                .body("Token not intended for this service");
        }
        // Process request...
    }
}
```

### Node.js/Express

```javascript
const jwt = require('jsonwebtoken');

const EXPECTED_AUDIENCE = 'billing-service';

function validateAudience(req, res, next) {
    const token = req.headers.authorization?.replace('Bearer ', '');
    if (!token) {
        return res.status(401).json({ error: 'No token provided' });
    }

    try {
        const decoded = jwt.verify(token, publicKey, {
            issuer: 'aussie-gateway',
            audience: EXPECTED_AUDIENCE
        });
        req.user = decoded;
        next();
    } catch (err) {
        if (err.name === 'JsonWebTokenError' && err.message.includes('audience')) {
            return res.status(403).json({ error: 'Token not intended for this service' });
        }
        return res.status(401).json({ error: 'Invalid token' });
    }
}

app.get('/api/invoices/:id', validateAudience, (req, res) => {
    // Process request...
});
```

### Go

```go
import (
    "github.com/golang-jwt/jwt/v5"
)

const expectedAudience = "billing-service"

func validateAudience(tokenString string) (*jwt.Token, error) {
    return jwt.Parse(tokenString, keyFunc, jwt.WithAudience(expectedAudience))
}

func invoiceHandler(w http.ResponseWriter, r *http.Request) {
    token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")

    claims, err := validateAudience(token)
    if err != nil {
        if errors.Is(err, jwt.ErrTokenInvalidAudience) {
            http.Error(w, "Token not intended for this service", http.StatusForbidden)
            return
        }
        http.Error(w, "Invalid token", http.StatusUnauthorized)
        return
    }
    // Process request...
}
```

## Security Considerations

### Why Validate Audience?

Without audience validation, an attacker who obtains a token issued for Service A could replay it against Service B. Both services would accept the token as valid since it's signed by Aussie.

With audience validation:
- Service A only accepts tokens with `aud: service-a`
- Service B only accepts tokens with `aud: service-b`
- Cross-service replay attacks are prevented

### Recommended Practices

1. **Always validate audience** in services handling sensitive data
2. **Use unique audience values** per service (the service ID is a good default)
3. **Return 403 Forbidden** for audience mismatch (not 401 Unauthorized)
4. **Log audience mismatches** for security monitoring

## Troubleshooting

### Token Missing Audience Claim

If tokens don't include an audience claim:
1. Verify `authRequired: true` on the endpoint
2. Check if `audience` is configured on the endpoint or as a platform default
3. Enable `require-audience` to force audience on all tokens

### Audience Mismatch Errors

If you receive 403 errors due to audience mismatch:
1. Verify the endpoint's configured audience matches your validation
2. Check if you're using the platform default vs per-endpoint audience
3. Inspect the token claims using a tool like [jwt.io](https://jwt.io)

### Debugging Token Claims

Decode the Aussie-issued token to inspect claims:

```bash
# Extract and decode the token (requires jq)
echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq .
```

Expected output with audience:
```json
{
  "iss": "aussie-gateway",
  "sub": "user@example.com",
  "aud": "billing-service",
  "exp": 1702659600,
  "iat": 1702656000,
  "original_iss": "https://idp.example.com"
}
```
