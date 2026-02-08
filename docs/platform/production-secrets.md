# Production Secrets Management

The optional `.env` file in the repository root contains demo keys for local development only. **Never use these keys in production** and never commit real secrets to version control.

## Secrets Inventory

| Secret | Env Variable | Format | Purpose | Rotation Cadence |
|--------|-------------|--------|---------|------------------|
| JWS Signing Key | `AUSSIE_JWS_SIGNING_KEY` | RSA PKCS#8 PEM | Signs session JWS tokens | Quarterly (see [Key Rotation](#key-rotation)) |
| Bootstrap Key | `AUSSIE_BOOTSTRAP_KEY` | String (min 32 chars) | First-time admin setup | Single-use; disable after bootstrap |
| Encryption Key | `AUTH_ENCRYPTION_KEY` | Base64-encoded 256-bit | Encrypts API key records at rest | Annually |
| Cassandra Credentials | `CASSANDRA_USERNAME`, `CASSANDRA_PASSWORD` | String | Database authentication | Per org policy |
| Redis Password | `REDIS_PASSWORD` | String | Redis authentication | Per org policy |
| OIDC Client Secret | `OIDC_CLIENT_SECRET` | String | OAuth2 client authentication | Per IdP policy |

## Key Generation

```bash
# RSA signing key (2048-bit PKCS#8)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out aussie-private.pem

# AES-256 encryption key
openssl rand -base64 32

# Bootstrap key
openssl rand -base64 32
```

## Secret Management by Deployment Model

### Kubernetes

Use [Kubernetes Secrets](https://kubernetes.io/docs/concepts/configuration/secret/) or an external secrets operator:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: aussie-secrets
type: Opaque
stringData:
  AUSSIE_JWS_SIGNING_KEY: |
    -----BEGIN PRIVATE KEY-----
    ...
    -----END PRIVATE KEY-----
  AUTH_ENCRYPTION_KEY: "<base64-encoded-key>"
```

Reference in the deployment:

```yaml
envFrom:
  - secretRef:
      name: aussie-secrets
```

For production workloads, prefer an external secrets operator ([External Secrets Operator](https://external-secrets.io/), [Sealed Secrets](https://sealed-secrets.netlify.app/)) that syncs from a secrets manager (Vault, AWS Secrets Manager, etc.) into Kubernetes Secrets.

### HashiCorp Vault

Store secrets in Vault and inject via the Vault Agent sidecar or Quarkus Vault extension:

```bash
# Store the signing key
vault kv put secret/aussie/signing-keys \
  current="$(cat aussie-private.pem)"

# Store database credentials
vault kv put secret/aussie/cassandra \
  username="aussie-app" \
  password="$(openssl rand -base64 32)"
```

Configure the Quarkus Vault extension in `application.properties`:

```properties
quarkus.vault.url=https://vault.internal:8200
quarkus.vault.authentication.kubernetes.role=aussie-gateway
quarkus.vault.kv-secret-engine-mount-path=secret
quarkus.vault.secret-config-kv-path=aussie
```

### Cloud Provider Secret Managers

**AWS Secrets Manager:**
```bash
aws secretsmanager create-secret \
  --name aussie/signing-key \
  --secret-string file://aussie-private.pem
```

Use the AWS Secrets Manager CSI driver or init container to inject at pod startup.

**GCP Secret Manager / Azure Key Vault:** Follow the equivalent pattern using your cloud provider's secrets CSI driver or SDK integration.

### CI/CD Pipelines

Store secrets as pipeline variables, never in code:

**GitHub Actions:**
```yaml
env:
  AUSSIE_JWS_SIGNING_KEY: ${{ secrets.AUSSIE_JWS_SIGNING_KEY }}
  AUTH_ENCRYPTION_KEY: ${{ secrets.AUTH_ENCRYPTION_KEY }}
  CASSANDRA_PASSWORD: ${{ secrets.CASSANDRA_PASSWORD }}
```

**GitLab CI:** Use [CI/CD Variables](https://docs.gitlab.com/ee/ci/variables/) with the "Masked" and "Protected" flags enabled.

**Jenkins:** Use the [Credentials Plugin](https://plugins.jenkins.io/credentials/) and inject via `withCredentials`.

**General CI/CD best practices:**
- Mark all secrets as masked/protected so they are redacted in logs
- Use environment-scoped secrets (dev, staging, prod)
- Never echo or print secret values in build steps
- Rotate secrets if a pipeline log is accidentally exposed

## Key Rotation

### Automated Rotation (Signing Keys)

Aussie supports automated signing key rotation. Enable it in production:

```properties
aussie.auth.key-rotation.enabled=true
aussie.auth.key-rotation.schedule=0 0 0 1 */3 ?
aussie.auth.key-rotation.grace-period=PT24H
aussie.auth.key-rotation.deprecation-period=P7D
aussie.auth.key-rotation.retention-period=P30D
```

See `application.properties` (key rotation section) for the full configuration reference.

### Manual Rotation (Static Signing Key)

When using a static signing key (`aussie.auth.key-rotation.enabled=false`):

1. Generate a new key: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`
2. Update the secret in your secrets manager
3. Restart Aussie instances (rolling restart recommended)
4. Verify the `/admin/jwks` endpoint serves the new public key
5. Notify downstream services to refresh their JWKS cache

### Encryption Key Rotation

The `AUTH_ENCRYPTION_KEY` encrypts API key records at rest. To rotate:

1. Generate a new key: `openssl rand -base64 32`
2. Update `aussie.auth.encryption.key-id` to a new version identifier (e.g., `v2`)
3. Deploy with both old and new keys available
4. Re-encrypt existing records (migration required)

## `.env` File Security

- The `.env` file is listed in `.gitignore` and must never be committed with real secrets
- It exists solely for local `./gradlew quarkusDev` convenience
- `AUSSIE_AUTH_DANGEROUS_NOOP` must always be `false` in production
- `AUSSIE_BOOTSTRAP_ENABLED` should be `false` after initial setup
