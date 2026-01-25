import * as jose from 'jose';
import crypto from 'crypto';

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
  groups?: string[];
  permissions?: string[];
}

// Simulated group mappings (in production, this comes from IdP claims)
// Maps demo users to their groups
// Groups follow the pattern: <service-id>.<role>
// Note: Usernames are derived from role labels (lowercase, no spaces)
export const USER_GROUPS: Record<string, string[]> = {
  // Role-derived usernames (from demo login role selection)
  admin: ['platform-team', 'demo-service.admin'],
  lead: ['demo-service.lead', 'demo-service.dev'],
  developer: ['demo-service.dev'],
  readonly: ['demo-service.readonly'],
  // Legacy named users (for backwards compatibility)
  alice: ['demo-service.admin', 'demo-service.dev'],
  bob: ['demo-service.dev'],
  guest: ['demo-service.readonly'],
};

// Default TTL settings
const DEFAULT_TTL_SECONDS = 3600; // 1 hour default
const MAX_TTL_SECONDS = 86400; // 24 hours max

/**
 * Generate a signed JWT token for the demo login flow.
 *
 * @param claims - The claims to include in the token
 * @param ttlSeconds - Optional TTL in seconds (default: 3600, max: 86400)
 * @returns The signed JWT token
 */
export async function generateToken(
  claims: TokenClaims,
  ttlSeconds: number = DEFAULT_TTL_SECONDS
): Promise<string> {
  const { privateKey } = await getKeyPair();

  // Enforce TTL limits
  const effectiveTtl = Math.min(
    Math.max(ttlSeconds, 60), // Minimum 60 seconds
    MAX_TTL_SECONDS
  );

  const now = Math.floor(Date.now() / 1000);
  const expiry = now + effectiveTtl;

  const jwt = await new jose.SignJWT({
    sub: claims.sub,
    name: claims.name,
    email: claims.email || `${claims.sub}@demo.local`,
    groups: claims.groups || [],
    permissions: claims.permissions || [],
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
 * Verify a JWT token and return the claims.
 *
 * @param token - The JWT token to verify
 * @returns The token claims or null if invalid
 */
export async function verifyToken(
  token: string
): Promise<(TokenClaims & { exp: number; iat: number }) | null> {
  try {
    const { publicKey } = await getKeyPair();
    const { payload } = await jose.jwtVerify(token, publicKey, {
      issuer: 'demo-app',
      audience: 'aussie-gateway',
    });

    // Cast through unknown since JWTPayload doesn't include our custom fields
    return payload as unknown as TokenClaims & { exp: number; iat: number };
  } catch {
    return null;
  }
}

// Token blacklist for logout functionality
// In production, use Redis or similar distributed cache
const tokenBlacklist = new Set<string>();

/**
 * Add a token to the blacklist.
 * The token will be rejected until it expires naturally.
 *
 * @param token - The token to blacklist
 * @param expiry - The token's expiry timestamp (Unix seconds)
 */
export function addToBlacklist(token: string, expiry: number): void {
  tokenBlacklist.add(token);

  // Clean up after expiry (no need to keep expired tokens)
  const ttlMs = expiry * 1000 - Date.now();
  if (ttlMs > 0) {
    setTimeout(() => tokenBlacklist.delete(token), ttlMs);
  }
}

/**
 * Check if a token is blacklisted.
 *
 * @param token - The token to check
 * @returns true if the token is blacklisted
 */
export function isBlacklisted(token: string): boolean {
  return tokenBlacklist.has(token);
}

// Device code storage for device_code flow
// Maps device_code -> { userCode, status, token, expiresAt }
interface DeviceCodeEntry {
  userCode: string;
  status: 'pending' | 'authorized' | 'expired';
  token?: string;
  claims?: TokenClaims;
  expiresAt: number;
}

const deviceCodes = new Map<string, DeviceCodeEntry>();

/**
 * Generate a user-friendly code for device code flow.
 */
export function generateUserCode(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let code = '';
  for (let i = 0; i < 8; i++) {
    if (i === 4) code += '-';
    code += chars[Math.floor(Math.random() * chars.length)];
  }
  return code;
}

/**
 * Create a new device code entry for the device code flow.
 *
 * @returns The device code and user code
 */
export function createDeviceCode(): {
  deviceCode: string;
  userCode: string;
  expiresIn: number;
} {
  const deviceCode = crypto.randomUUID();
  const userCode = generateUserCode();
  const expiresIn = 300; // 5 minutes

  deviceCodes.set(deviceCode, {
    userCode,
    status: 'pending',
    expiresAt: Date.now() + expiresIn * 1000,
  });

  // Clean up after expiry
  setTimeout(() => {
    const entry = deviceCodes.get(deviceCode);
    if (entry && entry.status === 'pending') {
      entry.status = 'expired';
    }
    // Remove after additional grace period
    setTimeout(() => deviceCodes.delete(deviceCode), 60000);
  }, expiresIn * 1000);

  return { deviceCode, userCode, expiresIn };
}

/**
 * Get a device code entry.
 */
export function getDeviceCode(deviceCode: string): DeviceCodeEntry | undefined {
  return deviceCodes.get(deviceCode);
}

/**
 * Find a device code by user code.
 */
export function findDeviceCodeByUserCode(
  userCode: string
): { deviceCode: string; entry: DeviceCodeEntry } | undefined {
  for (const [deviceCode, entry] of deviceCodes.entries()) {
    if (entry.userCode === userCode.toUpperCase().replace(/-/g, '')) {
      return { deviceCode, entry };
    }
  }
  return undefined;
}

/**
 * Authorize a device code with user claims.
 */
export async function authorizeDeviceCode(
  deviceCode: string,
  claims: TokenClaims
): Promise<boolean> {
  const entry = deviceCodes.get(deviceCode);
  if (!entry || entry.status !== 'pending') {
    return false;
  }

  const token = await generateToken(claims);
  entry.status = 'authorized';
  entry.token = token;
  entry.claims = claims;
  return true;
}

/**
 * Parse the redirect URL from the query string, with validation.
 */
export function parseRedirectUrl(redirect: string | null): string {
  if (!redirect) {
    return 'http://localhost:3000/';
  }

  // Allow known safe origins (preserve full URL for proper redirect)
  const allowedOrigins = [
    'http://localhost:3000',
    'http://127.0.0.1:3000',
  ];

  try {
    const url = new URL(redirect);
    // Check if origin is in allowed list
    if (allowedOrigins.some((origin) => url.origin === origin)) {
      return redirect; // Return full URL for allowed origins
    }
  } catch {
    // Not a valid absolute URL, check if it's a relative path
    if (redirect.startsWith('/') && !redirect.startsWith('//')) {
      return `http://localhost:3000${redirect}`;
    }
  }

  // Default to demo app
  return 'http://localhost:3000/';
}

// =============================================================================
// PKCE (Proof Key for Code Exchange) Utilities
// =============================================================================

/**
 * Generate a cryptographically secure code verifier for PKCE.
 * Per RFC 7636, the verifier must be 43-128 characters using unreserved chars.
 *
 * @returns URL-safe base64 encoded random string (86 characters)
 */
export function generateCodeVerifier(): string {
  const buffer = crypto.randomBytes(64);
  return buffer.toString('base64url');
}

/**
 * Generate an S256 code challenge from a verifier.
 * Computes: BASE64URL(SHA256(verifier))
 *
 * @param verifier - The code verifier
 * @returns The code challenge
 */
export function generateCodeChallenge(verifier: string): string {
  const hash = crypto.createHash('sha256').update(verifier, 'ascii').digest();
  return hash.toString('base64url');
}

/**
 * Verify that a code verifier matches a code challenge.
 *
 * @param verifier - The code verifier from the token request
 * @param challenge - The stored code challenge
 * @returns true if the verifier matches the challenge
 */
export function verifyCodeChallenge(
  verifier: string,
  challenge: string
): boolean {
  const computedChallenge = generateCodeChallenge(verifier);
  return computedChallenge === challenge;
}

// =============================================================================
// Authorization Code Storage (for OIDC Authorization Code Flow)
// =============================================================================

interface AuthorizationCodeEntry {
  code: string;
  clientId: string;
  redirectUri: string;
  codeChallenge?: string;
  codeChallengeMethod?: string;
  claims: TokenClaims;
  state?: string;
  expiresAt: number;
  used: boolean;
}

const authorizationCodes = new Map<string, AuthorizationCodeEntry>();

/**
 * Create an authorization code for the OIDC authorization code flow.
 *
 * @param params - Authorization parameters
 * @returns The authorization code
 */
export function createAuthorizationCode(params: {
  clientId: string;
  redirectUri: string;
  codeChallenge?: string;
  codeChallengeMethod?: string;
  claims: TokenClaims;
  state?: string;
}): string {
  const code = crypto.randomBytes(32).toString('base64url');
  const expiresIn = 60; // 1 minute - authorization codes should be short-lived

  authorizationCodes.set(code, {
    code,
    clientId: params.clientId,
    redirectUri: params.redirectUri,
    codeChallenge: params.codeChallenge,
    codeChallengeMethod: params.codeChallengeMethod,
    claims: params.claims,
    state: params.state,
    expiresAt: Date.now() + expiresIn * 1000,
    used: false,
  });

  // Clean up after expiry
  setTimeout(() => {
    authorizationCodes.delete(code);
  }, expiresIn * 1000 + 5000); // Add 5s grace period

  return code;
}

/**
 * Exchange an authorization code for tokens.
 *
 * @param code - The authorization code
 * @param clientId - The client ID (must match)
 * @param redirectUri - The redirect URI (must match)
 * @param codeVerifier - The PKCE code verifier (required if challenge was provided)
 * @returns The token claims or null if invalid
 */
export function exchangeAuthorizationCode(
  code: string,
  clientId: string,
  redirectUri: string,
  codeVerifier?: string
): { claims: TokenClaims; error?: never } | { error: string; claims?: never } {
  const entry = authorizationCodes.get(code);

  if (!entry) {
    return { error: 'invalid_grant: Authorization code not found' };
  }

  if (entry.used) {
    // Code replay attack - invalidate and reject
    authorizationCodes.delete(code);
    return { error: 'invalid_grant: Authorization code already used' };
  }

  if (Date.now() > entry.expiresAt) {
    authorizationCodes.delete(code);
    return { error: 'invalid_grant: Authorization code expired' };
  }

  if (entry.clientId !== clientId) {
    return { error: 'invalid_grant: Client ID mismatch' };
  }

  if (entry.redirectUri !== redirectUri) {
    return { error: 'invalid_grant: Redirect URI mismatch' };
  }

  // Verify PKCE if challenge was provided
  if (entry.codeChallenge) {
    if (!codeVerifier) {
      return { error: 'invalid_grant: Code verifier required' };
    }

    if (entry.codeChallengeMethod !== 'S256') {
      return { error: 'invalid_request: Only S256 challenge method supported' };
    }

    if (!verifyCodeChallenge(codeVerifier, entry.codeChallenge)) {
      return { error: 'invalid_grant: PKCE verification failed' };
    }
  }

  // Mark as used (one-time use)
  entry.used = true;

  return { claims: entry.claims };
}
