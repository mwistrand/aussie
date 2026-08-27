# Authentication surface

Aussie parses `Authorization` and the configured session cookie in one credential dispatcher. A request with both, or with duplicate `Authorization` headers, is rejected before any credential is validated.

## Endpoint policy inventory

| Surface | Policy | Notes |
|---|---|---|
| `/admin/*` | Required authentication plus endpoint permission | API key, configured JWT, or session |
| `/gateway/*`, `/{serviceId}/*` | Route policy | Public registered routes ignore credentials; protected routes authenticate and fail closed |
| `/q/*`, `/auth/.well-known/jwks.json` | Anonymous | Operational health and public verification keys |
| `/auth/oidc/*` | Browser-only, disabled by default | Requires `aussie.auth.oidc.public-endpoints-enabled=true` and binds exchange to the initiating browser |
| `/auth/session*` | Browser session | Creation is disabled by default; inspection, refresh, and logout require a valid session where applicable |
| Internal-only HTTP endpoints | None | No internal-only HTTP surface is currently exposed |

`CredentialAuthenticationMechanismTest`, `AuthenticationIntegrationTest`, and `AuthSurfaceContainmentTest` verify the credential grammar, conflicts, admin boundary, and disabled identity-construction endpoints.

## API-key format and migration

New and bootstrap API keys use `aussie_v1_` followed by exactly 43 Base64URL characters. Unprefixed credentials are rejected by default, so a Bearer credential cannot be guessed to be an API key merely because it is not a JWT.

For an existing deployment, use a bounded rotation window:

1. Set `aussie.auth.api-keys.accept-legacy-format=true`.
2. Issue and distribute replacement keys; all newly issued keys use `aussie_v1_`.
3. Revoke the legacy keys.
4. Remove the override or set it back to `false`.

The compatibility switch logs a warning on first use. Do not leave it enabled after rotation.
