import { NextRequest, NextResponse } from 'next/server';
import { createAuthorizationCode, USER_GROUPS, TokenClaims } from '@/lib/auth';

/**
 * OIDC Callback Endpoint (Demo IdP)
 *
 * This endpoint is called after successful user authentication to generate
 * an authorization code and redirect back to the client (Aussie).
 *
 * POST Body:
 * - username: Authenticated user's username
 * - group: Selected user group (optional)
 * - client_id: OAuth client ID
 * - redirect_uri: Where to redirect with the code
 * - state: CSRF protection state to pass back
 * - code_challenge: PKCE challenge (optional)
 * - code_challenge_method: PKCE method (optional)
 *
 * Response:
 * - Redirect to redirect_uri with code and state parameters
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json();

    const {
      username,
      group,
      client_id: clientId,
      redirect_uri: redirectUri,
      state,
      code_challenge: codeChallenge,
      code_challenge_method: codeChallengeMethod,
    } = body;

    // Validate required fields
    if (!username) {
      return NextResponse.json(
        { error: 'Username is required' },
        { status: 400 }
      );
    }

    if (!clientId) {
      return NextResponse.json(
        { error: 'client_id is required' },
        { status: 400 }
      );
    }

    if (!redirectUri) {
      return NextResponse.json(
        { error: 'redirect_uri is required' },
        { status: 400 }
      );
    }

    // Build user claims
    const normalizedUsername = username.trim().toLowerCase();
    let groups: string[] = [];

    // Check for predefined user groups
    if (USER_GROUPS[normalizedUsername]) {
      groups = [...USER_GROUPS[normalizedUsername]];
    }

    // Add selected group
    if (group) {
      groups = [...new Set([...groups, group])];
    }

    const claims: TokenClaims = {
      sub: username.trim(),
      name: username.trim(),
      email: `${normalizedUsername}@demo.local`,
      groups,
      permissions: group === 'admin' ? ['*'] : [],
    };

    // Create authorization code
    const code = createAuthorizationCode({
      clientId,
      redirectUri,
      codeChallenge,
      codeChallengeMethod,
      claims,
      state,
    });

    // Build redirect URL
    const callbackUrl = new URL(redirectUri);
    callbackUrl.searchParams.set('code', code);
    if (state) {
      callbackUrl.searchParams.set('state', state);
    }

    return NextResponse.json({
      success: true,
      redirectUrl: callbackUrl.toString(),
    });
  } catch (error) {
    console.error('OIDC callback error:', error);
    return NextResponse.json(
      { error: 'Failed to process authorization' },
      { status: 500 }
    );
  }
}
