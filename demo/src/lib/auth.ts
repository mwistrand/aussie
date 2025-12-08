import * as jose from 'jose';

// Demo app RSA key pair for signing tokens
// In production, these would be loaded from environment/secrets

// Generate a consistent key pair for demo purposes
// This uses a deterministic approach for development
let keyPair: { privateKey: jose.KeyLike; publicKey: jose.KeyLike } | null = null;
let jwks: jose.JSONWebKeySet | null = null;

async function getKeyPair() {
  if (!keyPair) {
    // Generate RSA key pair
    const { privateKey, publicKey } = await jose.generateKeyPair('RS256', {
      extractable: true,
    });
    keyPair = { privateKey, publicKey };
  }
  return keyPair;
}

/**
 * Get the JWKS (JSON Web Key Set) for token validation.
 * Aussie uses this to validate tokens from the demo app.
 */
export async function getJwks(): Promise<jose.JSONWebKeySet> {
  if (!jwks) {
    const { publicKey } = await getKeyPair();
    const jwk = await jose.exportJWK(publicKey);
    jwk.kid = 'demo-key-1';
    jwk.alg = 'RS256';
    jwk.use = 'sig';
    jwks = { keys: [jwk] };
  }
  return jwks;
}

export interface TokenClaims {
  sub: string;
  name: string;
  email?: string;
  permissions: string[];
}

/**
 * Generate a signed JWT token for the demo login flow.
 *
 * @param claims - The claims to include in the token
 * @returns The signed JWT token
 */
export async function generateToken(claims: TokenClaims): Promise<string> {
  const { privateKey } = await getKeyPair();

  const now = Math.floor(Date.now() / 1000);
  const expiry = now + 5 * 60; // 5 minutes

  const jwt = await new jose.SignJWT({
    sub: claims.sub,
    name: claims.name,
    email: claims.email || `${claims.sub}@demo.local`,
    permissions: claims.permissions,
  })
    .setProtectedHeader({ alg: 'RS256', kid: 'demo-key-1' })
    .setIssuedAt(now)
    .setExpirationTime(expiry)
    .setIssuer('demo-app')
    .setAudience('aussie-gateway')
    .sign(privateKey);

  return jwt;
}

/**
 * Parse the redirect URL from the query string, with validation.
 */
export function parseRedirectUrl(redirect: string | null): string {
  if (!redirect) {
    return '/';
  }

  // Only allow relative URLs or URLs to known safe domains
  // This prevents open redirect vulnerabilities
  try {
    const url = new URL(redirect, 'http://localhost');
    if (url.origin === 'http://localhost' || url.origin === 'http://demo:3000') {
      return url.pathname + url.search;
    }
  } catch {
    // Invalid URL, use default
  }

  return '/';
}
