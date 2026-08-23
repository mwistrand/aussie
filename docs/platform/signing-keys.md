# Signing keys and issued-token profile

Aussie has one signing authority. Route tokens and session-derived tokens use the
active key held by `SigningKeyRegistry`; `/auth/.well-known/jwks.json` publishes
the public half of the same snapshot. If token issuance is enabled, startup and
readiness fail unless exactly one sign-capable active key appears in that JWKS.

## Token profiles

All timestamps use integer epoch seconds. Both profiles use RS256 and carry a
`kid` that identifies a published JWKS key.

| Claim | Route token (`aussie+jwt-v1`) | Session token (`aussie+session-jwt-v1`) |
|---|---|---|
| `iss`, `sub`, `jti`, `iat`, `nbf`, `exp` | Required, gateway-owned | Required, gateway-owned |
| `aud` | Route/default audience when configured or required | Configured session audience |
| `aussie_token_profile` | `aussie+jwt-v1` | `aussie+session-jwt-v1` |
| Provenance | `original_iss`, `original_provider` | `original_iss`, `sid` |
| Lifetime ceiling | Minimum of configured TTL, platform maximum, and validated identity expiry | Minimum of configured TTL and session expiry |

Configured forwarded claims cannot replace standard, provenance, session, or
profile claims. Consumers must enforce issuer, audience, algorithm, expiration,
not-before, and the expected profile value.

## Key state machine

```text
PENDING (published, never signs)
   -> ACTIVE (published, exactly one, signs)
   -> DEPRECATED (published, verifies only)
   -> RETIRED (not published)
   -> deleted after retention
```

`grace-period` is the publish-before-use window. `deprecation-period` must be at
least the maximum issued-token lifetime, which startup validates. Activation is
one repository compare-and-set: it verifies the expected active key, deprecates
that key, and activates the pending key in one distributed transaction. A stale
or concurrent activation changes nothing.

The built-in `config` repository supports local development and static keys.
Process-local rotation is rejected in normal mode. A production rotating-key
repository must persist protected private-key material or KMS/HSM/Vault
references, implement the atomic activation contract, and share state across all
instances.

## Rotation procedure

1. Generate/register a pending key. Confirm its `kid` is present in JWKS on every instance.
2. Wait at least `grace-period` and downstream cache maximum age.
3. Activate it once. A CAS conflict means another actor won; refresh state before retrying.
4. Confirm new tokens use the new `kid`, the old key is deprecated, and `aussie_signing_key_ready` remains `1`.
5. Retire only after `deprecation-period`; delete only after `retention-period`.

Manual rotation activates immediately and is reserved for compromise response.
Static-key mode cannot provide a multi-key overlap across a rolling restart; use
a durable rotating repository when uninterrupted downstream verification matters.

## Incident and rollback

`SigningKeyUnavailable` fires when required issuance has no active key matching
JWKS. Protected routes fail closed while the gauge is `0`.

1. Stop automated/manual rotation and inspect the durable repository for active-key count and last transition.
2. If the new private key is unavailable but the prior material is uncompromised, register that material under a new `kid`, publish it as pending, and activate it through the normal repository CAS.
3. If compromise is suspected, do not restore the key. Register, publish, and emergency-activate a new key; expect tokens signed by the compromised key to fail after retirement.
4. Verify JWKS and a newly issued token end to end before returning instances to service.
5. Record the affected `kid`, transition time, token-lifetime window, and downstream cache behavior in the incident timeline.

## Incoming provider JWKS policy

JWKS fetches use the shared egress/TLS policy, reject redirects and non-JSON
responses, and enforce response-size, key-count, duplicate-`kid`, key-use,
algorithm, and minimum-key-size limits. Stale fallback is bounded by
`aussie.resiliency.jwks.maximum-stale`; set it to `PT0` to disable fallback.
Validator consumers are keyed by the SHA-256 public-key thumbprint, so replacing
key material while reusing a `kid` cannot retain the old verifier.
