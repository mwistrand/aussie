import { NextRequest, NextResponse } from 'next/server';

/**
 * OIDC Authorization Endpoint (Demo IdP)
 *
 * This endpoint implements the OAuth 2.0 Authorization Code flow with PKCE support.
 * It receives authorization requests from Aussie (acting as an OAuth client) and
 * redirects the user to the login page for authentication.
 *
 * Query Parameters:
 * - response_type: Must be "code"
 * - client_id: The client identifier (e.g., "aussie-gateway")
 * - redirect_uri: Where to redirect after authorization
 * - state: CSRF protection state (passed through)
 * - code_challenge: PKCE challenge (optional but recommended)
 * - code_challenge_method: Must be "S256" if challenge provided
 * - scope: Requested scopes (optional)
 *
 * Flow:
 * 1. Aussie redirects user here with code_challenge
 * 2. This endpoint redirects to /login with auth params in session/URL
 * 3. User authenticates
 * 4. Login page calls /api/auth/oidc/callback to generate auth code
 * 5. User is redirected back to Aussie with the code
 * 6. Aussie exchanges code for tokens at /api/auth/oidc/token
 */
export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams;

  // Extract OAuth parameters
  const responseType = searchParams.get('response_type');
  const clientId = searchParams.get('client_id');
  const redirectUri = searchParams.get('redirect_uri');
  const state = searchParams.get('state');
  const codeChallenge = searchParams.get('code_challenge');
  const codeChallengeMethod = searchParams.get('code_challenge_method');
  const scope = searchParams.get('scope');

  // Validate required parameters
  if (responseType !== 'code') {
    return NextResponse.json(
      {
        error: 'unsupported_response_type',
        error_description: 'Only "code" response type is supported',
      },
      { status: 400 }
    );
  }

  if (!clientId) {
    return NextResponse.json(
      {
        error: 'invalid_request',
        error_description: 'client_id is required',
      },
      { status: 400 }
    );
  }

  if (!redirectUri) {
    return NextResponse.json(
      {
        error: 'invalid_request',
        error_description: 'redirect_uri is required',
      },
      { status: 400 }
    );
  }

  // Validate PKCE parameters if provided
  if (codeChallenge && codeChallengeMethod !== 'S256') {
    return NextResponse.json(
      {
        error: 'invalid_request',
        error_description: 'Only S256 code_challenge_method is supported',
      },
      { status: 400 }
    );
  }

  // Build login URL with authorization context
  // The login page will use these to complete the authorization
  const host = request.headers.get('host') || 'localhost:3000';
  const protocol = request.headers.get('x-forwarded-proto') || 'http';
  const loginUrl = new URL('/login', `${protocol}://${host}`);

  // Pass authorization parameters to login page
  loginUrl.searchParams.set('flow', 'oidc');
  loginUrl.searchParams.set('client_id', clientId);
  loginUrl.searchParams.set('redirect_uri', redirectUri);
  if (state) loginUrl.searchParams.set('state', state);
  if (codeChallenge) loginUrl.searchParams.set('code_challenge', codeChallenge);
  if (codeChallengeMethod)
    loginUrl.searchParams.set('code_challenge_method', codeChallengeMethod);
  if (scope) loginUrl.searchParams.set('scope', scope);

  // Redirect to login page
  return NextResponse.redirect(loginUrl.toString());
}
